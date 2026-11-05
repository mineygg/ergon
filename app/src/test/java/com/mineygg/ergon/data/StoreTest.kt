package com.mineygg.ergon.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.mineygg.ergon.core.Account
import com.mineygg.ergon.core.CheckinStatus
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.Time
import com.mineygg.ergon.core.UpdateInfo
import com.mineygg.ergon.core.WorkshopGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Everything the app keeps on the device: that it is saved, read back after a restart, and kept where it belongs. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class StoreTest {
    private lateinit var ctx: Context

    // The Android Keystore doesn't exist here, so the "secure" preferences are plain ones.
    private fun newStore() = Store(ctx, secure())

    private fun secure(): SharedPreferences = ctx.getSharedPreferences("test_secure", Context.MODE_PRIVATE)

    private fun plain(): SharedPreferences = ctx.getSharedPreferences("claim_prefs", Context.MODE_PRIVATE)

    private val hook = "https://discord.com/api/webhooks/123/abc"
    private val hook2 = "https://discord.com/api/webhooks/456/def"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    // ── Settings ────────────────────────────────────────────────────────────
    @Test
    fun `a fresh store has the default settings`() {
        val s = newStore().settings.value
        assertEquals(ThemeMode.DARK, s.theme)
        assertTrue(s.autoRun)
        assertTrue(s.notify)
        assertTrue(s.checkUpdates)
        assertEquals(Game.entries.map { it.id }.toSet(), s.checkinGames)
        assertEquals(setOf("genshin", "hsr", "zzz"), s.redeemGames)
        assertEquals(WorkshopGame.entries.map { it.id }.toSet(), s.workshopGames)
        assertTrue(s.newsGames.isEmpty())
        assertTrue(s.regions.isEmpty())
    }

    @Test
    fun `changed settings are still there after a restart`() {
        newStore().updateSettings {
            it.copy(
                theme = ThemeMode.LIGHT,
                autoRun = false,
                notify = false,
                checkUpdates = false,
                checkinGames = setOf("genshin"),
                redeemGames = setOf("zzz"),
                workshopGames = emptySet(),
                newsGames = setOf("hsr"),
                regions = mapOf("genshin" to setOf("os_usa", "os_euro"))
            )
        }
        val s = newStore().settings.value
        assertEquals(ThemeMode.LIGHT, s.theme)
        assertFalse(s.autoRun)
        assertFalse(s.notify)
        assertFalse(s.checkUpdates)
        assertEquals(setOf("genshin"), s.checkinGames)
        assertEquals(setOf("zzz"), s.redeemGames)
        assertTrue(s.workshopGames.isEmpty())
        assertEquals(setOf("hsr"), s.newsGames)
        assertEquals(mapOf("genshin" to setOf("os_usa", "os_euro")), s.regions)
    }

    @Test
    fun `the settings flow shows a change at once`() {
        val store = newStore()
        store.updateSettings { it.copy(autoRun = false) }
        assertFalse(store.settings.value.autoRun)
    }

    @Test
    fun `exchange choices are kept per game, with the priority in order`() {
        newStore().updateSettings {
            it.withExchange(WorkshopGame.HSR) { ExchangePrefs(auto = true, mode = ExchangeMode.AFFORDABLE, priority = listOf(5, 3, 9), drawWhenIdle = true) }
        }
        val s = newStore().settings.value
        assertEquals(ExchangePrefs(true, ExchangeMode.AFFORDABLE, listOf(5, 3, 9), true), s.exchangePrefs(WorkshopGame.HSR))
        assertEquals(ExchangePrefs(), s.exchangePrefs(WorkshopGame.GENSHIN))
        assertEquals(ExchangePrefs(), s.exchangePrefs(WorkshopGame.ZZZ))
    }

    @Test
    fun `the picked regions can be cleared again`() {
        val store = newStore()
        store.updateSettings { it.copy(regions = mapOf("genshin" to setOf("os_usa"))) }
        store.updateSettings { it.copy(regions = emptyMap()) }
        assertTrue(newStore().settings.value.regions.isEmpty())
    }

    @Test
    fun `an unknown saved theme falls back to the default`() {
        plain().edit().putString("theme", "NEON").commit()
        assertEquals(ThemeMode.DARK, newStore().settings.value.theme)
    }

    @Test
    fun `settings from before the per-game screens keep what was on`() {
        plain().edit()
            .putStringSet("games", setOf("genshin", "hsr"))
            .putBoolean("checkin", true)
            .putBoolean("autoRedeem", false)
            .putBoolean("workshop", true)
            .commit()
        val s = newStore().settings.value
        assertEquals(setOf("genshin", "hsr"), s.checkinGames)
        assertTrue(s.redeemGames.isEmpty())
        assertEquals(setOf("genshin", "hsr"), s.workshopGames)
    }

    @Test
    fun `an old switch that was off turns the task off for every game`() {
        plain().edit().putBoolean("checkin", false).putBoolean("workshop", false).commit()
        val s = newStore().settings.value
        assertTrue(s.checkinGames.isEmpty())
        assertTrue(s.workshopGames.isEmpty())
        assertEquals(setOf("genshin", "hsr", "zzz"), s.redeemGames)
    }

    @Test
    fun `the old Genshin exchange keys are still read, and the list is cleaned`() {
        plain().edit()
            .putBoolean("autoExchange", true)
            .putString("exchangePriority", "4, 2,x,2")
            .putBoolean("autoExchange_hsr", true)
            .putString("exchangeMode_hsr", "DRAW")
            .commit()
        val s = newStore().settings.value
        assertTrue(s.exchangePrefs(WorkshopGame.GENSHIN).auto)
        assertEquals(listOf(4, 2), s.exchangePrefs(WorkshopGame.GENSHIN).priority)
        assertTrue(s.exchangePrefs(WorkshopGame.HSR).auto)
        assertEquals(ExchangeMode.DRAW, s.exchangePrefs(WorkshopGame.HSR).mode)
        assertFalse(s.exchangePrefs(WorkshopGame.ZZZ).auto)
    }

    // ── Credentials ─────────────────────────────────────────────────────────
    @Test
    fun `a fresh store has no session`() {
        val c = newStore().creds.value
        assertFalse(c.hasHoyolab)
        assertFalse(c.hasLogin)
    }

    @Test
    fun `saved tokens are there after a restart`() {
        val a = newStore()
        a.saveHoyolab("tok", "42")
        a.saveCookieToken("ck")
        assertEquals(Credentials(ltoken = "tok", ltuid = "42", cookieToken = "ck"), newStore().creds.value)
    }

    @Test
    fun `tokens are kept in the secure preferences, never in the plain ones`() {
        val store = newStore()
        store.saveHoyolab("tok", "42")
        store.saveCookieToken("ck")
        assertTrue(secure().contains("ltoken"))
        assertTrue(secure().contains("cookie_token"))
        for (key in listOf("ltoken", "ltuid", "cookie_token")) assertFalse(key, plain().contains(key))
    }

    @Test
    fun `a session flagged as expired stays flagged until a new one is saved`() {
        val store = newStore()
        store.saveHoyolab("tok", "42")
        store.saveCookieToken("ck")
        store.setHealth(hoyolabExpired = true, loginExpired = true)
        assertTrue(newStore().creds.value.hoyolabExpired)
        assertTrue(newStore().creds.value.loginExpired)
        store.saveHoyolab("new", "42")
        assertFalse(store.creds.value.hoyolabExpired)
        assertTrue(store.creds.value.loginExpired)
        store.saveCookieToken("new")
        assertFalse(store.creds.value.loginExpired)
    }

    @Test
    fun `setting one health flag leaves the other`() {
        val store = newStore()
        store.setHealth(hoyolabExpired = true)
        store.setHealth(loginExpired = true)
        assertTrue(store.creds.value.hoyolabExpired)
        assertTrue(store.creds.value.loginExpired)
        store.setHealth(hoyolabExpired = false)
        assertFalse(store.creds.value.hoyolabExpired)
        assertTrue(store.creds.value.loginExpired)
    }

    @Test
    fun `signing out removes the session, the profile and the picked regions`() {
        val store = newStore()
        store.saveHoyolab("tok", "42")
        store.saveCookieToken("ck")
        store.saveAccounts(Game.GENSHIN, listOf(Account("1", "os_usa", "Nick")))
        store.updateSettings { it.copy(regions = mapOf("genshin" to setOf("os_usa"))) }
        store.acceptDisclaimer()

        store.clearCredentials()

        val again = newStore()
        assertEquals(Credentials(), again.creds.value)
        assertTrue(again.accounts.value.isEmpty())
        assertTrue(again.settings.value.regions.isEmpty())
        assertFalse(again.disclaimerAccepted.value)
    }

    @Test
    fun `signing out keeps the webhooks, the news and the other settings`() {
        val store = newStore()
        val now = System.currentTimeMillis() / 1000
        store.setWebhook("genshin", hook)
        store.addNews(listOf(NewsItem("2:1", "genshin", "Giveaway", "", now, now + 86_400L, "")), now)
        store.updateSettings { it.copy(theme = ThemeMode.LIGHT) }

        store.clearCredentials()

        val again = newStore()
        assertEquals(hook, again.webhook("genshin"))
        assertEquals(1, again.news.value.size)
        assertEquals(ThemeMode.LIGHT, again.settings.value.theme)
    }

    @Test
    fun `the disclaimer stays accepted after a restart`() {
        val store = newStore()
        assertFalse(store.disclaimerAccepted.value)
        store.acceptDisclaimer()
        assertTrue(store.disclaimerAccepted.value)
        assertTrue(newStore().disclaimerAccepted.value)
    }

    // ── Profile ─────────────────────────────────────────────────────────────
    @Test
    fun `the accounts of the profile are kept per game`() {
        val store = newStore()
        val genshin = listOf(Account("800", "os_usa", "A"), Account("700", "os_euro", "B"))
        val hsr = listOf(Account("600", "prod_official_asia", "C"))
        store.saveAccounts(Game.GENSHIN, genshin)
        store.saveAccounts(Game.HSR, hsr)
        assertEquals(mapOf("genshin" to genshin, "hsr" to hsr), newStore().accounts.value)
    }

    @Test
    fun `a game with no account is remembered as having none`() {
        val store = newStore()
        store.saveAccounts(Game.ZZZ, emptyList())
        assertEquals(mapOf("zzz" to emptyList<Account>()), newStore().accounts.value)
    }

    @Test
    fun `a profile that is saved again with the same accounts changes nothing`() {
        val store = newStore()
        val list = listOf(Account("1", "os_usa", "Nick"))
        store.saveAccounts(Game.GENSHIN, list)
        store.saveAccounts(Game.GENSHIN, list)
        assertEquals(mapOf("genshin" to list), store.accounts.value)
    }

    @Test
    fun `a saved profile that is damaged is read as empty`() {
        plain().edit().putString("accounts", "{broken").commit()
        assertTrue(newStore().accounts.value.isEmpty())
    }

    // ── Check-in state ──────────────────────────────────────────────────────
    @Test
    fun `a check-in is remembered for today and for its game`() {
        val store = newStore()
        assertNull(store.checkinToday("genshin"))
        store.setCheckinToday("genshin", CheckinStatus.ALREADY)
        assertEquals(CheckinStatus.ALREADY, store.checkinToday("genshin"))
        assertNull(store.checkinToday("hsr"))
        assertEquals(CheckinStatus.ALREADY, newStore().checkinToday("genshin"))
    }

    @Test
    fun `a check-in from an earlier day does not count`() {
        plain().edit().putString("checkin:genshin", "2001-01-01|SUCCESS").commit()
        assertNull(newStore().checkinToday("genshin"))
    }

    @Test
    fun `a damaged check-in entry does not count`() {
        plain().edit()
            .putString("checkin:genshin", "garbage")
            .putString("checkin:hsr", "${Time.today()}|NOPE")
            .commit()
        val store = newStore()
        assertNull(store.checkinToday("genshin"))
        assertNull(store.checkinToday("hsr"))
    }

    @Test
    fun `check-in tries are counted for today`() {
        val store = newStore()
        assertEquals(0, store.checkinTriesToday)
        assertEquals(0L, store.lastCheckinTry)
        store.noteCheckinTry()
        store.noteCheckinTry()
        assertEquals(2, store.checkinTriesToday)
        assertTrue(store.lastCheckinTry > 0L)
        assertEquals(2, newStore().checkinTriesToday)
    }

    @Test
    fun `the tries of an earlier day start again from zero`() {
        plain().edit().putString("checkin_tries", "2001-01-01|3").commit()
        val store = newStore()
        assertEquals(0, store.checkinTriesToday)
        store.noteCheckinTry()
        assertEquals(1, store.checkinTriesToday)
    }

    // ── Claimed codes ───────────────────────────────────────────────────────
    @Test
    fun `a claimed code is remembered for its account only`() {
        val store = newStore()
        assertFalse(store.isClaimed("genshin:800", "ABC"))
        store.addClaimed("genshin:800", "ABC")
        assertTrue(store.isClaimed("genshin:800", "ABC"))
        assertFalse(store.isClaimed("genshin:800", "XYZ"))
        assertFalse(store.isClaimed("genshin:700", "ABC"))
        assertTrue(newStore().isClaimed("genshin:800", "ABC"))
    }

    @Test
    fun `claiming more codes keeps the earlier ones`() {
        val store = newStore()
        store.addClaimed("hsr:1", "ONE")
        store.addClaimed("hsr:1", "TWO")
        assertTrue(store.isClaimed("hsr:1", "ONE"))
        assertTrue(store.isClaimed("hsr:1", "TWO"))
    }

    // ── Discord webhooks ────────────────────────────────────────────────────
    @Test
    fun `a fresh store has no webhooks`() {
        val store = newStore()
        assertNull(store.webhook("genshin"))
        assertTrue(store.webhooks.value.isEmpty())
        assertFalse(store.webhookReady("genshin"))
        assertTrue(store.announced("genshin").isEmpty())
        assertNull(store.seen("genshin"))
    }

    @Test
    fun `a saved webhook is there after a restart`() {
        val store = newStore()
        store.setWebhook("genshin", hook)
        assertEquals(hook, store.webhook("genshin"))
        assertEquals(setOf("genshin"), store.webhooks.value)
        val again = newStore()
        assertEquals(hook, again.webhook("genshin"))
        assertEquals(setOf("genshin"), again.webhooks.value)
    }

    @Test
    fun `webhook addresses are secret and kept in the secure preferences`() {
        newStore().setWebhook("genshin", hook)
        assertTrue(secure().contains("webhook:genshin"))
        assertTrue(plain().all.keys.none { it.startsWith("webhook:") })
        assertTrue(plain().all.values.none { it == hook })
    }

    @Test
    fun `a new address starts from what was seen, so what was posted before is forgotten`() {
        val store = newStore()
        store.setWebhook("genshin", hook)
        store.setWebhookReady("genshin")
        store.setAnnounced("genshin", setOf("a"))
        store.setSeen("genshin", setOf("a", "b"))

        store.setWebhook("genshin", hook2)

        assertFalse(store.webhookReady("genshin"))
        assertTrue(store.announced("genshin").isEmpty())
        assertEquals(setOf("a", "b"), store.seen("genshin"))
    }

    @Test
    fun `saving the same address again changes nothing`() {
        val store = newStore()
        store.setWebhook("genshin", hook)
        store.setWebhookReady("genshin")
        store.setAnnounced("genshin", setOf("a"))
        store.setWebhook("genshin", hook)
        assertTrue(store.webhookReady("genshin"))
        assertEquals(setOf("a"), store.announced("genshin"))
    }

    @Test
    fun `removing a webhook forgets it and what was posted, but not what was seen`() {
        val store = newStore()
        store.setWebhook("zzz", hook)
        store.setWebhookReady("zzz")
        store.setAnnounced("zzz", setOf("a"))
        store.setSeen("zzz", setOf("a"))

        store.removeWebhook("zzz")

        assertNull(store.webhook("zzz"))
        assertFalse(store.webhookReady("zzz"))
        assertTrue(store.announced("zzz").isEmpty())
        assertEquals(setOf("a"), store.seen("zzz"))
        assertTrue(store.webhooks.value.isEmpty())
        assertTrue(newStore().webhooks.value.isEmpty())
    }

    @Test
    fun `each game has its own webhook`() {
        val store = newStore()
        store.setWebhook("genshin", hook)
        store.setWebhook("hsr", hook2)
        store.removeWebhook("genshin")
        assertEquals(hook2, store.webhook("hsr"))
        assertEquals(setOf("hsr"), store.webhooks.value)
    }

    @Test
    fun `seen and announced codes are kept separately per game`() {
        val store = newStore()
        store.setSeen("genshin", setOf("one"))
        store.setSeen("hsr", setOf("two"))
        store.setAnnounced("genshin", setOf("three"))
        val again = newStore()
        assertEquals(setOf("one"), again.seen("genshin"))
        assertEquals(setOf("two"), again.seen("hsr"))
        assertEquals(setOf("three"), again.announced("genshin"))
        assertTrue(again.announced("hsr").isEmpty())
    }

    // ── Code page ───────────────────────────────────────────────────────────
    @Test
    fun `the page validators are kept per page`() {
        val store = newStore()
        assertEquals(null to null, store.validators("genshin"))
        store.setValidators("genshin", "\"etag1\"", "Mon, 02 Nov 2026 10:00:00 GMT")
        assertEquals("\"etag1\"" to "Mon, 02 Nov 2026 10:00:00 GMT", newStore().validators("genshin"))
        assertEquals(null to null, store.validators("hsr"))
    }

    @Test
    fun `the code signature and the time of the last read are kept per game`() {
        val store = newStore()
        assertNull(store.codesSig("genshin"))
        assertEquals(0L, store.siteChecked("genshin"))
        store.setCodesSig("genshin", "abc123")
        store.setSiteChecked("genshin", 1234L)
        val again = newStore()
        assertEquals("abc123", again.codesSig("genshin"))
        assertEquals(1234L, again.siteChecked("genshin"))
        assertNull(again.codesSig("hsr"))
        assertEquals(0L, again.siteChecked("hsr"))
    }

    @Test
    fun `the times of the last redeem calls are kept`() {
        val store = newStore()
        assertEquals(0L, store.lastRedeemCall)
        assertEquals(0L, store.lastFullRedeem)
        store.setLastRedeemCall(11L)
        store.setLastFullRedeem(22L)
        assertEquals(11L, newStore().lastRedeemCall)
        assertEquals(22L, newStore().lastFullRedeem)
    }

    @Test
    fun `a code check stamps the time`() {
        val store = newStore()
        assertEquals(0L, store.lastCodeCheck.value)
        store.touchCodeCheck()
        assertTrue(store.lastCodeCheck.value > 0L)
        assertEquals(store.lastCodeCheck.value, newStore().lastCodeCheck.value)
    }

    // ── App updates ─────────────────────────────────────────────────────────
    @Test
    fun `a found update is kept until it is cleared`() {
        val store = newStore()
        assertNull(store.updateInfo)
        val info = UpdateInfo("1.4.0", "https://github.com/mineygg/ergon/releases/download/v1.4.0/ergon.apk", 9_999L)
        store.setUpdateInfo(info)
        assertEquals(info, newStore().updateInfo)
        store.setUpdateInfo(null)
        assertNull(newStore().updateInfo)
    }

    @Test
    fun `an update with a missing part is ignored`() {
        plain().edit().putString("update_ver", "1.4.0").commit()
        assertNull(newStore().updateInfo)
    }

    @Test
    fun `the time of the last update check is kept`() {
        val store = newStore()
        assertEquals(0L, store.lastUpdateCheck)
        store.setLastUpdateCheck(777L)
        assertEquals(777L, newStore().lastUpdateCheck)
    }

    // ── Reports and activity ────────────────────────────────────────────────
    private fun report(claimed: Int) = RunReport(1_700_000_000_000L, "manual", mapOf("genshin" to "SUCCESS"), claimed, 0, 0, emptyList())

    @Test
    fun `the last reports are there after a restart`() {
        val store = newStore()
        assertNull(store.checkinReport.value)
        assertNull(store.redeemReport.value)
        store.saveCheckinReport(report(1))
        store.saveRedeemReport(report(2))
        val again = newStore()
        assertEquals(report(1), again.checkinReport.value)
        assertEquals(report(2), again.redeemReport.value)
    }

    private fun record(i: Int) = RunRecord(i.toLong(), "CHECKIN", "manual", "run $i", true, emptyList())

    @Test
    fun `the activity list shows the newest run first`() {
        val store = newStore()
        store.addRecord(record(1))
        store.addRecord(record(2))
        assertEquals(listOf(2L, 1L), store.history.value.map { it.timeMillis })
    }

    @Test
    fun `the activity list keeps the latest 40 runs`() {
        val store = newStore()
        for (i in 1..45) store.addRecord(record(i))
        assertEquals(40, store.history.value.size)
        assertEquals(45L, store.history.value.first().timeMillis)
        assertEquals(6L, store.history.value.last().timeMillis)
        assertEquals(store.history.value, newStore().history.value)
    }

    @Test
    fun `the log keeps the latest 400 lines and drops blank ones`() {
        val store = newStore()
        assertTrue(store.savedLog.isEmpty())
        store.saveLog(List(450) { "line $it" })
        assertEquals(400, store.savedLog.size)
        assertEquals("line 50", store.savedLog.first())
        assertEquals("line 449", store.savedLog.last())
        store.saveLog(listOf("a", "", "b"))
        assertEquals(listOf("a", "b"), newStore().savedLog)
    }

    // ── Workshop ────────────────────────────────────────────────────────────
    @Test
    fun `the workshop state is kept per game`() {
        val store = newStore()
        val genshin = WorkshopInfo(5, 100, 1_800_000_000L, lottery = false, exchange = true, updatedAt = 1L)
        val hsr = WorkshopInfo(7, 20, 1_800_000_000L, lottery = true, exchange = true, updatedAt = 2L)
        store.saveWorkshopInfo(WorkshopGame.GENSHIN, genshin)
        store.saveWorkshopInfo(WorkshopGame.HSR, hsr)
        assertEquals(mapOf("genshin" to genshin, "hsr" to hsr), newStore().workshopInfo.value)
    }

    @Test
    fun `the refresh alarm time is kept per game`() {
        val store = newStore()
        assertEquals(0L, store.refreshAt(WorkshopGame.HSR))
        store.setRefreshAt(WorkshopGame.HSR, 5_000L)
        store.setRefreshAt(WorkshopGame.ZZZ, 9_000L)
        assertEquals(5_000L, newStore().refreshAt(WorkshopGame.HSR))
        assertEquals(9_000L, newStore().refreshAt(WorkshopGame.ZZZ))
        assertEquals(0L, newStore().refreshAt(WorkshopGame.GENSHIN))
    }

    @Test
    fun `a task done this period is remembered, and a new period replaces the old one`() {
        val store = newStore()
        val prefix = "genshin:5:12:1:"
        store.setWorkshopDone(prefix, "genshin:5:12:1:2026-11-04")
        assertTrue(store.workshopDone("genshin:5:12:1:2026-11-04"))
        store.setWorkshopDone(prefix, "genshin:5:12:1:2026-11-05")
        assertFalse(store.workshopDone("genshin:5:12:1:2026-11-04"))
        assertTrue(store.workshopDone("genshin:5:12:1:2026-11-05"))
    }

    @Test
    fun `another task of the same event is kept`() {
        val store = newStore()
        store.setWorkshopDone("genshin:5:12:1:", "genshin:5:12:1:2026-11-04")
        store.setWorkshopDone("genshin:5:13:1:", "genshin:5:13:1:2026-11-04")
        store.setWorkshopDone("genshin:5:12:1:", "genshin:5:12:1:2026-11-05")
        assertTrue(store.workshopDone("genshin:5:13:1:2026-11-04"))
    }

    @Test
    fun `a new event version clears the old one of the same game only`() {
        val store = newStore()
        store.setWorkshopDone("genshin:5:12:1:", "genshin:5:12:1:2026-11-04")
        store.setWorkshopDone("hsr:3:12:1:", "hsr:3:12:1:2026-11-04")
        store.setWorkshopDone("genshin:6:12:1:", "genshin:6:12:1:2026-11-05")
        assertFalse(store.workshopDone("genshin:5:12:1:2026-11-04"))
        assertTrue(store.workshopDone("genshin:6:12:1:2026-11-05"))
        assertTrue(store.workshopDone("hsr:3:12:1:2026-11-04"))
    }

    @Test
    fun `entries written by version 1 1 without a game are dropped`() {
        plain().edit().putStringSet("ws_done", setOf("123:7:2026-10-01")).commit()
        val store = newStore()
        store.setWorkshopDone("genshin:5:12:1:", "genshin:5:12:1:2026-11-05")
        assertFalse(store.workshopDone("123:7:2026-10-01"))
        assertTrue(store.workshopDone("genshin:5:12:1:2026-11-05"))
    }

    @Test
    fun `tasks that need the player are remembered apart from done ones`() {
        val store = newStore()
        store.setWorkshopNeedsYou("zzz:8:4:1:", "zzz:8:4:1:2026-11-05")
        assertTrue(store.workshopNeedsYou("zzz:8:4:1:2026-11-05"))
        assertFalse(store.workshopDone("zzz:8:4:1:2026-11-05"))
        store.setWorkshopNeedsYou("zzz:8:4:1:", "zzz:8:4:1:2026-11-06")
        assertFalse(store.workshopNeedsYou("zzz:8:4:1:2026-11-05"))
        assertTrue(newStore().workshopNeedsYou("zzz:8:4:1:2026-11-06"))
    }

    // ── News ────────────────────────────────────────────────────────────────
    @Test
    fun `the events already looked at are kept, and a bad list is empty`() {
        val store = newStore()
        assertTrue(store.newsSeen().isEmpty())
        store.setNewsSeen(listOf("2:1", "2:2"))
        assertEquals(listOf("2:1", "2:2"), newStore().newsSeen())
        plain().edit().putString("news_seen", "nope").commit()
        assertTrue(newStore().newsSeen().isEmpty())
    }

    @Test
    fun `forgetting one game's events leaves the others`() {
        val store = newStore()
        store.setNewsSeen(listOf("2:1", "2:2", "6:1", "20:1"))
        store.forgetNewsSeen(2)
        assertEquals(listOf("6:1", "20:1"), store.newsSeen())
    }

    @Test
    fun `news that came in is shown newest first and survives a restart`() {
        // The store drops ended events when it loads, so this one uses the real time.
        val now = System.currentTimeMillis() / 1000
        val store = newStore()
        val older = NewsItem("2:1", "genshin", "Older", "", now - 100, now + 1_000, "")
        val newer = NewsItem("2:2", "genshin", "Newer", "", now - 10, now + 1_000, "")
        store.addNews(listOf(older, newer), now)
        assertEquals(listOf("2:2", "2:1"), store.news.value.map { it.id })
        assertEquals(listOf("2:2", "2:1"), newStore().news.value.map { it.id })
    }

    @Test
    fun `news that ended long ago is dropped when it is added or pruned`() {
        val now = 1_800_000_000L
        val day = 86_400L
        val store = newStore()
        store.addNews(listOf(NewsItem("2:1", "genshin", "Old", "", now - 60 * day, now - 50 * day, "")), now)
        assertTrue(store.news.value.isEmpty())
        store.addNews(listOf(NewsItem("2:2", "genshin", "Recent", "", now, now + day, "")), now)
        assertEquals(1, store.news.value.size)
        store.pruneNews(now + 40 * day)
        assertTrue(store.news.value.isEmpty())
    }

    @Test
    fun `an event that is added twice is shown once`() {
        val now = 1_800_000_000L
        val store = newStore()
        val item = NewsItem("2:1", "genshin", "Giveaway", "", now, now + 1_000, "")
        store.addNews(listOf(item), now)
        store.addNews(listOf(item), now)
        assertEquals(1, store.news.value.size)
    }

    @Test
    fun `news saved by an older version is dropped once`() {
        val now = System.currentTimeMillis() / 1000
        plain().edit()
            .putInt("news_rev", 1)
            .putString("news_seen", """["old:1"]""")
            .putString("news_items", NewsItem.listToJson(listOf(NewsItem("old:1", "genshin", "t", "", now, now + 1_000, ""))))
            .commit()
        val migrated = newStore()
        assertTrue(migrated.newsSeen().isEmpty())
        assertTrue(migrated.news.value.isEmpty())

        migrated.setNewsSeen(listOf("2:1"))
        assertEquals(listOf("2:1"), newStore().newsSeen())
        assertEquals(3, plain().getInt("news_rev", 0))
    }

    // ── Pending tasks ───────────────────────────────────────────────────────
    @Test
    fun `a task waiting for the internet is added once and removed once`() {
        val store = newStore()
        store.addPending("CHECKIN")
        store.addPending("CHECKIN")
        store.addPending("REDEEM")
        assertEquals(setOf("CHECKIN", "REDEEM"), newStore().pending.value)
        store.clearPending("CHECKIN")
        store.clearPending("CHECKIN")
        assertEquals(setOf("REDEEM"), newStore().pending.value)
    }
}
