package com.mineygg.ergon

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mineygg.ergon.core.Account
import com.mineygg.ergon.core.Connectivity
import com.mineygg.ergon.core.Discord
import com.mineygg.ergon.core.ExchangeAward
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.HoyoApi
import com.mineygg.ergon.core.News
import com.mineygg.ergon.core.NewsApi
import com.mineygg.ergon.core.RunBus
import com.mineygg.ergon.core.Runner
import com.mineygg.ergon.core.Updater
import com.mineygg.ergon.core.WorkshopApi
import com.mineygg.ergon.core.WorkshopGame
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

    /** The game accounts of the HoYoLAB profile, by game id, as of the last time it was read. */
    val accounts = store.accounts

    /** The games that have a Discord webhook saved. */
    val webhooks = store.webhooks

    /**
     * Saves the webhooks typed in (game id -> URL; empty = leave it as it is). Each new one gets a short test message,
     * and is only saved when Discord takes it. [done] gets what went wrong per game id, empty when all went well.
     */
    fun saveWebhooks(entries: Map<String, String>, done: (Map<String, String>) -> Unit) {
        viewModelScope.launch {
            val errors = LinkedHashMap<String, String>()
            for ((gameId, raw) in entries) {
                val url = Discord.clean(raw)
                val game = Game.entries.firstOrNull { it.id == gameId }
                if (url.isEmpty() || game == null) continue
                if (!Discord.isWebhookUrl(url)) {
                    errors[gameId] = "That isn't a Discord webhook URL."
                } else if (store.webhook(gameId) != url) {
                    val problem = Discord.sayHello(url, game)
                    if (problem == null) store.setWebhook(gameId, url) else errors[gameId] = problem
                }
            }
            done(errors)
        }
    }

    fun removeWebhook(gameId: String) = store.removeWebhook(gameId)

    /** Important HoYoLAB posts for the games that have News turned on. */
    val news = store.news
    val disclaimerAccepted = store.disclaimerAccepted
    val running = RunBus.running
    val active = RunBus.active
    val status = RunBus.status
    val live = RunBus.live
    val log = RunBus.log
    val updateState = Updater.state

    /** Live internet state, and the tasks waiting for it (CHECKIN, REDEEM, WORKSHOP). */
    val online = Connectivity.online
    val pending = store.pending

    private val _newsBusy = MutableStateFlow(false)
    val newsBusy: StateFlow<Boolean> = _newsBusy.asStateFlow()
    private val _newsStatus = MutableStateFlow("")
    val newsStatus: StateFlow<String> = _newsStatus.asStateFlow()

    private val _shop = MutableStateFlow<ShopState>(ShopState.Loading)
    val shop: StateFlow<ShopState> = _shop.asStateFlow()

    init {
        Connectivity.start(app)
        // Whenever the internet comes back (and on start), finish what was waiting for it or got missed.
        viewModelScope.launch {
            Connectivity.online.collect { if (it) Scheduler.catchUp(app) }
        }
        // The profile is read on sign-in and on every check-in. After an update from a version that didn't keep it, read it once.
        if (store.creds.value.hasHoyolab && !store.creds.value.hoyolabExpired && store.accounts.value.isEmpty()) {
            viewModelScope.launch { refreshProfile() }
        }
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
    fun runWorkshop(game: WorkshopGame) {
        if (RunBus.running.value) return
        val app = getApplication<Application>()
        viewModelScope.launch { Runner(app, store).runWorkshop("manual", force = true, only = game) }
    }

    /** Reads the exchange shop (read-only, so it doesn't need the run lock) for the priority menu. */
    fun loadShop(game: WorkshopGame) {
        val cr = store.creds.value
        if (!cr.hasHoyolab || cr.hoyolabExpired) {
            _shop.value = ShopState.Failed("Sign in with HoYoLAB first.")
            return
        }
        _shop.value = ShopState.Loading
        viewModelScope.launch {
            val (indexRes, act) = WorkshopApi.index(cr, game)
            if (act == null) {
                _shop.value = ShopState.Failed(
                    when {
                        indexRes.network -> "Network error. Check your connection and try again."
                        indexRes.retcode == -100 -> "Your HoYoLAB session expired. Sign in again."
                        indexRes.retcode == 0 -> "There is no active ${game.eventName} event right now."
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

    /** Looks for new important posts now. Found ones go to the News tab without a notification. */
    fun checkNews() {
        if (_newsBusy.value) return
        _newsBusy.value = true
        viewModelScope.launch {
            _newsStatus.value = News.check(getApplication(), store, notify = false).message
            _newsBusy.value = false
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
        // A game just got News turned off: forget what was seen of it, so turning it on again starts from scratch.
        for (g in Game.entries) {
            if (g.id in old.newsGames && g.id !in n.newsGames) store.forgetNewsSeen(NewsApi.FEEDS.getValue(g))
        }
        // A game just got News turned on: read its feed now.
        if (n.newsGames.any { it !in old.newsGames }) checkNews()
    }

    /** Reads the HoYoLAB profile of one game and keeps it. Null when it couldn't be read. */
    suspend fun profile(game: Game): List<Account>? =
        HoyoApi.discoverOrNull(game.biz, store.creds.value)?.also { store.saveAccounts(game, it) }

    /** Reads the HoYoLAB profile of every game. */
    suspend fun refreshProfile() {
        for (g in Game.entries) profile(g)
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
