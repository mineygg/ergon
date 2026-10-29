package com.mineygg.ergon

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mineygg.ergon.core.ExchangeAward
import com.mineygg.ergon.core.RunBus
import com.mineygg.ergon.core.Runner
import com.mineygg.ergon.core.Updater
import com.mineygg.ergon.core.WorkshopApi
import com.mineygg.ergon.data.Settings
import com.mineygg.ergon.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The exchange shop as shown in the priority menu. */
sealed interface ShopState {
    object Loading : ShopState

    data class Ready(val points: Int, val awards: List<ExchangeAward>) : ShopState

    data class Failed(val message: String) : ShopState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as ClaimApp).store

    val settings = store.settings
    val creds = store.creds
    val checkinReport = store.checkinReport
    val redeemReport = store.redeemReport
    val lastCodeCheck = store.lastCodeCheck
    val history = store.history
    val workshopInfo = store.workshopInfo
    val disclaimerAccepted = store.disclaimerAccepted
    val running = RunBus.running
    val active = RunBus.active
    val status = RunBus.status
    val live = RunBus.live
    val log = RunBus.log
    val updateState = Updater.state

    private val _shop = MutableStateFlow<ShopState>(ShopState.Loading)
    val shop: StateFlow<ShopState> = _shop.asStateFlow()

    init {
        Updater.restore(app, store)
        if (store.settings.value.checkUpdates) checkUpdates(manual = false)
    }

    fun checkUpdates(manual: Boolean = true) {
        viewModelScope.launch { Updater.check(getApplication(), store, manual) }
    }

    fun startUpdate() {
        viewModelScope.launch { Updater.downloadAndInstall(getApplication()) }
    }

    /** Games already done today are never sent to the server again. */
    fun runCheckin() {
        if (RunBus.running.value) return
        val app = getApplication<Application>()
        viewModelScope.launch { Runner(app, store).runCheckin("manual") }
    }

    /** Forced full pass over every code page. */
    fun runRedeem() {
        if (RunBus.running.value) return
        val app = getApplication<Application>()
        viewModelScope.launch { Runner(app, store).runRedeem("manual", force = true) }
    }

    /** Forced: also retries tasks that were marked as needing the player (completed ones are never redone). */
    fun runWorkshop() {
        if (RunBus.running.value) return
        val app = getApplication<Application>()
        viewModelScope.launch { Runner(app, store).runWorkshop("manual", force = true) }
    }

    /** Reads the exchange shop (read-only, so it doesn't need the run lock) for the priority menu. */
    fun loadShop() {
        val cr = store.creds.value
        if (!cr.hasHoyolab || cr.hoyolabExpired) {
            _shop.value = ShopState.Failed("Sign in with HoYoLAB first.")
            return
        }
        _shop.value = ShopState.Loading
        viewModelScope.launch {
            val (indexRes, act) = WorkshopApi.index(cr)
            if (act == null) {
                _shop.value = ShopState.Failed(
                    when {
                        indexRes.network -> "Network error. Check your connection and try again."
                        indexRes.retcode == -100 -> "Your HoYoLAB session expired. Sign in again."
                        indexRes.retcode == 0 -> "There is no active Machine Workshop event right now."
                        else -> "Couldn't load the event [${indexRes.retcode}] ${indexRes.message}"
                    }
                )
                return@launch
            }
            val (listRes, awards) = WorkshopApi.exchangeList(cr, act)
            _shop.value = if (listRes.retcode == 0) {
                ShopState.Ready(act.points, awards)
            } else {
                val why = if (listRes.network) {
                    "Network error. Check your connection and try again."
                } else {
                    "Couldn't load the exchange [${listRes.retcode}] ${listRes.message}"
                }
                ShopState.Failed(why)
            }
        }
    }

    /** Cancels whatever is running right now (manual or scheduled). */
    fun stop() = RunBus.stop()

    fun updateSettings(transform: (Settings) -> Settings) {
        val old = store.settings.value
        store.updateSettings(transform)
        val n = store.settings.value
        if (old.autoRun != n.autoRun) Scheduler.schedule(getApplication(), n, replace = true)
        if (!old.checkUpdates && n.checkUpdates) checkUpdates(manual = false)
    }

    fun saveHoyolab(ltoken: String, ltuid: String) = store.saveHoyolab(ltoken, ltuid)
    fun saveCookieToken(token: String) = store.saveCookieToken(token)
    fun acceptDisclaimer() = store.acceptDisclaimer()

    fun logout() {
        store.clearCredentials()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
