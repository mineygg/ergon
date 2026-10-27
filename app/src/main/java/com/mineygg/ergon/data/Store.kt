package com.mineygg.ergon.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mineygg.ergon.core.CheckinStatus
import com.mineygg.ergon.core.Time
import com.mineygg.ergon.core.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All persistence. Tokens live in EncryptedSharedPreferences (Android Keystore backed);
 * settings, claimed-code history and reports live in regular preferences.
 */
class Store(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("claim_prefs", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = createSecure(ctx)

    private val _settings = MutableStateFlow(loadSettings())
    private val _creds = MutableStateFlow(loadCreds())
    private val _checkinReport = MutableStateFlow(RunReport.fromJson(sp.getString("report_checkin", null)))
    private val _redeemReport = MutableStateFlow(RunReport.fromJson(sp.getString("report_redeem", null)))
    private val _lastCodeCheck = MutableStateFlow(sp.getLong("last_code_check", 0L))
    private val _history = MutableStateFlow(RunRecord.listFromJson(sp.getString("history", null)))
    private val _disclaimerAccepted = MutableStateFlow(sp.getBoolean("disclaimer", false))
    private val _workshopInfo = MutableStateFlow(WorkshopInfo.fromJson(sp.getString("workshop_info", null)))

    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val creds: StateFlow<Credentials> = _creds.asStateFlow()
    val checkinReport: StateFlow<RunReport?> = _checkinReport.asStateFlow()
    val redeemReport: StateFlow<RunReport?> = _redeemReport.asStateFlow()
    val lastCodeCheck: StateFlow<Long> = _lastCodeCheck.asStateFlow()
    val history: StateFlow<List<RunRecord>> = _history.asStateFlow()
    val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted.asStateFlow()
    val workshopInfo: StateFlow<WorkshopInfo?> = _workshopInfo.asStateFlow()

    val savedLog: List<String>
        get() = sp.getString("log", "").orEmpty().split('\n').filter { it.isNotBlank() }

    // ── Settings ────────────────────────────────────────────────────────────
    private fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            theme = runCatching { ThemeMode.valueOf(sp.getString("theme", d.theme.name).orEmpty()) }
                .getOrDefault(d.theme),
            autoRun = sp.getBoolean("autoRun", d.autoRun),
            enabledGames = sp.getStringSet("games", null)?.toSet() ?: d.enabledGames,
            checkin = sp.getBoolean("checkin", d.checkin),
            autoRedeem = sp.getBoolean("autoRedeem", d.autoRedeem),
            workshop = sp.getBoolean("workshop", d.workshop),
            notify = sp.getBoolean("notify", d.notify),
            checkUpdates = sp.getBoolean("checkUpdates", d.checkUpdates)
        )
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val n = transform(_settings.value)
        _settings.value = n
        sp.edit()
            .putString("theme", n.theme.name)
            .putBoolean("autoRun", n.autoRun)
            .putStringSet("games", HashSet(n.enabledGames))
            .putBoolean("checkin", n.checkin)
            .putBoolean("autoRedeem", n.autoRedeem)
            .putBoolean("workshop", n.workshop)
            .putBoolean("notify", n.notify)
            .putBoolean("checkUpdates", n.checkUpdates)
            .apply()
    }

    // ── Credentials ─────────────────────────────────────────────────────────
    private fun loadCreds() = Credentials(
        ltoken = secure.getString("ltoken", "").orEmpty(),
        ltuid = secure.getString("ltuid", "").orEmpty(),
        cookieToken = secure.getString("cookie_token", "").orEmpty(),
        hoyolabExpired = secure.getBoolean("exp_h", false),
        loginExpired = secure.getBoolean("exp_l", false)
    )

    fun saveHoyolab(ltoken: String, ltuid: String) {
        secure.edit().putString("ltoken", ltoken).putString("ltuid", ltuid).putBoolean("exp_h", false).apply()
        _creds.value = _creds.value.copy(ltoken = ltoken, ltuid = ltuid, hoyolabExpired = false)
    }

    fun saveCookieToken(token: String) {
        secure.edit().putString("cookie_token", token).putBoolean("exp_l", false).apply()
        _creds.value = _creds.value.copy(cookieToken = token, loginExpired = false)
    }

    /** Only touches the flags that are non-null. */
    fun setHealth(hoyolabExpired: Boolean? = null, loginExpired: Boolean? = null) {
        val e = secure.edit()
        hoyolabExpired?.let { e.putBoolean("exp_h", it) }
        loginExpired?.let { e.putBoolean("exp_l", it) }
        e.apply()
        _creds.value = _creds.value.copy(
            hoyolabExpired = hoyolabExpired ?: _creds.value.hoyolabExpired,
            loginExpired = loginExpired ?: _creds.value.loginExpired
        )
    }

    fun clearCredentials() {
        secure.edit().clear().apply()
        _creds.value = Credentials()
        // Show the disclaimer again on the next sign-in.
        sp.edit().putBoolean("disclaimer", false).apply()
        _disclaimerAccepted.value = false
    }

    fun acceptDisclaimer() {
        sp.edit().putBoolean("disclaimer", true).apply()
        _disclaimerAccepted.value = true
    }

    // ── Check-in state (per game, per HoYoLAB day = UTC+8) ──────────────────
    fun checkinToday(gameId: String): CheckinStatus? {
        val parts = sp.getString("checkin:$gameId", null)?.split("|") ?: return null
        if (parts.size != 2 || parts[0] != Time.today()) return null
        return runCatching { CheckinStatus.valueOf(parts[1]) }.getOrNull()
    }

    fun setCheckinToday(gameId: String, status: CheckinStatus) {
        sp.edit().putString("checkin:$gameId", "${Time.today()}|${status.name}").apply()
    }

    // ── Claimed-code history ────────────────────────────────────────────────
    fun isClaimed(key: String, code: String): Boolean =
        sp.getStringSet("claimed:$key", null)?.contains(code) == true

    fun addClaimed(key: String, code: String) {
        val cur = HashSet(sp.getStringSet("claimed:$key", null) ?: emptySet())
        cur.add(code)
        sp.edit().putStringSet("claimed:$key", cur).apply()
    }

    // ── Code page change detection ──────────────────────────────────────────
    fun validators(key: String): Pair<String?, String?> =
        sp.getString("etag:$key", null) to sp.getString("lm:$key", null)

    fun setValidators(key: String, etag: String?, lastModified: String?) {
        sp.edit().putString("etag:$key", etag).putString("lm:$key", lastModified).apply()
    }

    fun codesSig(key: String): String? = sp.getString("sig:$key", null)
    fun setCodesSig(key: String, sig: String) = sp.edit().putString("sig:$key", sig).apply()

    /** Time of the last redeem request that reached the server (real code or keep-alive ping). */
    val lastRedeemCall: Long get() = sp.getLong("last_redeem_call", 0L)
    fun setLastRedeemCall(t: Long) = sp.edit().putLong("last_redeem_call", t).apply()

    val lastFullRedeem: Long get() = sp.getLong("last_full_redeem", 0L)
    fun setLastFullRedeem(t: Long) = sp.edit().putLong("last_full_redeem", t).apply()

    fun touchCodeCheck() {
        val t = System.currentTimeMillis()
        _lastCodeCheck.value = t
        sp.edit().putLong("last_code_check", t).apply()
    }

    // ── App updates ─────────────────────────────────────────────────────────
    val lastUpdateCheck: Long get() = sp.getLong("update_checked", 0L)
    fun setLastUpdateCheck(t: Long) = sp.edit().putLong("update_checked", t).apply()

    /** The newer release found by the last check, if any. */
    val updateInfo: UpdateInfo?
        get() {
            val v = sp.getString("update_ver", null) ?: return null
            val u = sp.getString("update_url", null) ?: return null
            return UpdateInfo(v, u, sp.getLong("update_size", 0L))
        }

    fun setUpdateInfo(i: UpdateInfo?) {
        sp.edit().apply {
            if (i == null) {
                remove("update_ver").remove("update_url").remove("update_size")
            } else {
                putString("update_ver", i.version).putString("update_url", i.url).putLong("update_size", i.size)
            }
        }.apply()
    }

    // ── Reports & log ───────────────────────────────────────────────────────
    fun saveCheckinReport(r: RunReport) {
        _checkinReport.value = r
        sp.edit().putString("report_checkin", r.toJson()).apply()
    }

    fun saveRedeemReport(r: RunReport) {
        _redeemReport.value = r
        sp.edit().putString("report_redeem", r.toJson()).apply()
    }

    // ── Machine Workshop ────────────────────────────────────────────────────
    fun saveWorkshopInfo(i: WorkshopInfo) {
        _workshopInfo.value = i
        sp.edit().putString("workshop_info", i.toJson()).apply()
    }

    fun workshopDone(key: String): Boolean = sp.getStringSet("ws_done", null)?.contains(key) == true

    /** Replaces any older entry for the same task (older period or event version), so the sets stay small. */
    private fun swapEntry(setKey: String, prefix: String, entry: String) {
        val ver = prefix.substringBefore(':') + ":"
        val cur = HashSet(sp.getStringSet(setKey, null) ?: emptySet())
        cur.removeAll { !it.startsWith(ver) || it.startsWith(prefix) }
        cur.add(entry)
        sp.edit().putStringSet(setKey, cur).apply()
    }

    /** Task collected for this period. [prefix] is "version:task:timeType:". */
    fun setWorkshopDone(prefix: String, key: String) = swapEntry("ws_done", prefix, key)

    /** True if finish_task was already tried this period and the server said the player has to do it. */
    fun workshopNeedsYou(key: String): Boolean =
        sp.getStringSet("ws_need", null)?.contains(key) == true

    fun setWorkshopNeedsYou(prefix: String, key: String) = swapEntry("ws_need", prefix, key)

    fun addRecord(r: RunRecord) {
        val next = (listOf(r) + _history.value).take(40)
        _history.value = next
        sp.edit().putString("history", RunRecord.listToJson(next)).apply()
    }

    fun saveLog(lines: List<String>) {
        sp.edit().putString("log", lines.takeLast(400).joinToString("\n")).apply()
    }

    private fun createSecure(ctx: Context): SharedPreferences {
        fun build(): SharedPreferences {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                ctx,
                "secure_creds",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        return try {
            build()
        } catch (e: Exception) {
            // Keystore key lost or file corrupted: start clean rather than crash.
            ctx.deleteSharedPreferences("secure_creds")
            build()
        }
    }
}
