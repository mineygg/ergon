package com.mineygg.ergon.data

import android.app.Application
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.WorkshopGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The saved shapes of the app: tokens, settings, run reports, the activity list and the workshop state. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class ModelsTest {
    // ── Credentials ─────────────────────────────────────────────────────────
    @Test
    fun `a HoYoLAB session needs both tokens`() {
        assertFalse(Credentials().hasHoyolab)
        assertFalse(Credentials(ltoken = "t").hasHoyolab)
        assertFalse(Credentials(ltuid = "1").hasHoyolab)
        assertTrue(Credentials(ltoken = "t", ltuid = "1").hasHoyolab)
    }

    @Test
    fun `a login for gift codes needs the user id and the cookie token`() {
        assertFalse(Credentials().hasLogin)
        assertFalse(Credentials(ltuid = "1").hasLogin)
        assertFalse(Credentials(cookieToken = "c").hasLogin)
        assertTrue(Credentials(ltuid = "1", cookieToken = "c").hasLogin)
    }

    @Test
    fun `the cookies are built from the tokens`() {
        val c = Credentials(ltoken = "LT", ltuid = "42", cookieToken = "CK")
        assertEquals("ltoken_v2=LT; ltuid_v2=42;", c.hoyolabCookie())
        assertEquals("account_id_v2=42; cookie_token_v2=CK;", c.loginCookie())
    }

    @Test
    fun `a fresh login has not expired`() {
        val c = Credentials()
        assertFalse(c.hoyolabExpired)
        assertFalse(c.loginExpired)
    }

    // ── Settings ────────────────────────────────────────────────────────────
    @Test
    fun `the default settings turn everything a game supports on`() {
        val s = Settings()
        assertEquals(ThemeMode.DARK, s.theme)
        assertTrue(s.autoRun)
        assertTrue(s.notify)
        assertTrue(s.checkUpdates)
        assertEquals(Game.entries.map { it.id }.toSet(), s.checkinGames)
        assertEquals(setOf("genshin", "hsr", "zzz"), s.redeemGames)
        assertEquals(WorkshopGame.entries.map { it.id }.toSet(), s.workshopGames)
    }

    @Test
    fun `news is off by default`() {
        assertTrue(Settings().newsGames.isEmpty())
    }

    @Test
    fun `a game without exchange choices has the defaults`() {
        val p = Settings().exchangePrefs(WorkshopGame.HSR)
        assertFalse(p.auto)
        assertEquals(ExchangeMode.WAIT, p.mode)
        assertTrue(p.priority.isEmpty())
        assertFalse(p.drawWhenIdle)
    }

    @Test
    fun `changing the exchange choices of one game leaves the others`() {
        val s = Settings().withExchange(WorkshopGame.GENSHIN) { it.copy(auto = true, priority = listOf(3, 1)) }
        assertTrue(s.exchangePrefs(WorkshopGame.GENSHIN).auto)
        assertEquals(listOf(3, 1), s.exchangePrefs(WorkshopGame.GENSHIN).priority)
        assertEquals(ExchangePrefs(), s.exchangePrefs(WorkshopGame.HSR))
        assertEquals(ExchangePrefs(), s.exchangePrefs(WorkshopGame.ZZZ))
    }

    @Test
    fun `a change builds on what the game already had`() {
        val s = Settings()
            .withExchange(WorkshopGame.ZZZ) { it.copy(auto = true) }
            .withExchange(WorkshopGame.ZZZ) { it.copy(mode = ExchangeMode.DRAW) }
        assertTrue(s.exchangePrefs(WorkshopGame.ZZZ).auto)
        assertEquals(ExchangeMode.DRAW, s.exchangePrefs(WorkshopGame.ZZZ).mode)
    }

    @Test
    fun `the saved names of the modes do not change`() {
        // The modes are saved by name, so renaming one would lose what the user chose.
        assertEquals(listOf("WAIT", "AFFORDABLE", "DRAW"), ExchangeMode.entries.map { it.name })
        assertEquals(listOf("SYSTEM", "DARK", "LIGHT"), ThemeMode.entries.map { it.name })
    }

    // ── Run report ──────────────────────────────────────────────────────────
    private fun report() = RunReport(
        timeMillis = 1_700_000_000_000L,
        trigger = "scheduled",
        checkins = mapOf("genshin" to "SUCCESS", "hsr" to "ALREADY"),
        claimed = 2,
        skipped = 1,
        failed = 0,
        entries = listOf(
            RedeemEntry("genshin", "CODE1", "Primogem x60", "CLAIMED", "Redeemed!"),
            RedeemEntry("zzz", "CODE2", "", "SKIPPED", "[-2018] already used")
        )
    )

    @Test
    fun `a report survives being saved and read`() {
        assertEquals(report(), RunReport.fromJson(report().toJson()))
    }

    @Test
    fun `no report is read from nothing or from rubbish`() {
        assertNull(RunReport.fromJson(null))
        assertNull(RunReport.fromJson(""))
        assertNull(RunReport.fromJson("   "))
        assertNull(RunReport.fromJson("not json"))
        assertNull(RunReport.fromJson("{}"))
    }

    @Test
    fun `a report without a trigger counts as manual`() {
        val json = """{"time":1,"claimed":2,"skipped":3,"failed":4,"checkins":{},"entries":[]}"""
        val r = RunReport.fromJson(json)!!
        assertEquals("manual", r.trigger)
        assertEquals(2, r.claimed)
        assertEquals(3, r.skipped)
        assertEquals(4, r.failed)
    }

    @Test
    fun `a report entry without rewards or a message is read with empty text`() {
        val json = """{"time":1,"claimed":0,"skipped":0,"failed":0,"checkins":{},
            "entries":[{"game":"hsr","code":"X","outcome":"FAILED"}]}"""
        val e = RunReport.fromJson(json)!!.entries.single()
        assertEquals("", e.rewards)
        assertEquals("", e.message)
    }

    @Test
    fun `a report with a broken entry is dropped as a whole`() {
        val json = """{"time":1,"claimed":0,"skipped":0,"failed":0,"checkins":{},"entries":[{"game":"hsr"}]}"""
        assertNull(RunReport.fromJson(json))
    }

    // ── Activity list ───────────────────────────────────────────────────────
    private fun record() = RunRecord(
        timeMillis = 5L,
        task = "REDEEM",
        trigger = "manual",
        summary = "2 redeemed",
        ok = false,
        items = listOf(
            RecordItem("genshin", "CODE1", "Primogem x60", ItemState.OK, mono = true),
            RecordItem(null, "Network", "No connection", ItemState.ERROR)
        )
    )

    @Test
    fun `a run record survives being saved and read`() {
        assertEquals(record(), RunRecord.fromJson(record().toJson()))
    }

    @Test
    fun `an item without a game is read back without one`() {
        val back = RunRecord.fromJson(record().toJson()).items[1]
        assertNull(back.gameId)
        assertFalse(back.mono)
    }

    @Test
    fun `a record with only the basics gets sensible defaults`() {
        val r = RunRecord.fromJson(org.json.JSONObject("""{"time":9,"task":"CHECKIN","items":[]}"""))
        assertEquals("manual", r.trigger)
        assertEquals("", r.summary)
        assertTrue(r.ok)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun `an item state that is not known counts as info`() {
        val json = """{"time":9,"task":"CHECKIN","items":[{"title":"x","state":"WEIRD"}]}"""
        val item = RunRecord.fromJson(org.json.JSONObject(json)).items.single()
        assertEquals(ItemState.INFO, item.state)
        assertEquals("", item.detail)
    }

    @Test
    fun `a list of records keeps its order`() {
        val list = listOf(record(), record().copy(timeMillis = 4L, summary = "older"))
        assertEquals(list, RunRecord.listFromJson(RunRecord.listToJson(list)))
    }

    @Test
    fun `no records are read from nothing or from rubbish`() {
        assertTrue(RunRecord.listFromJson(null).isEmpty())
        assertTrue(RunRecord.listFromJson("").isEmpty())
        assertTrue(RunRecord.listFromJson("nope").isEmpty())
        assertTrue(RunRecord.listFromJson("""[{"time":1}]""").isEmpty())
    }

    // ── Workshop state ──────────────────────────────────────────────────────
    @Test
    fun `workshop info survives being saved and read`() {
        val info = WorkshopInfo(versionId = 12, points = 340, endTime = 1_800_000_000L, lottery = true, exchange = false, updatedAt = 1_700_000_000_000L)
        assertEquals(info, WorkshopInfo.fromJson(info.toJson()))
    }

    @Test
    fun `no workshop info is read from nothing or from rubbish`() {
        assertNull(WorkshopInfo.fromJson(null))
        assertNull(WorkshopInfo.fromJson(" "))
        assertNull(WorkshopInfo.fromJson("{"))
        assertNull(WorkshopInfo.fromJson("""{"v":1}"""))
    }

    // ── News item ───────────────────────────────────────────────────────────
    private fun news(end: Long) = NewsItem("2:1", "genshin", "Giveaway", "Win something", time = 10L, end = end, url = "")

    @Test
    fun `an event is live until its end time`() {
        assertTrue(news(end = 101L).isLive(100L))
        assertFalse(news(end = 100L).isLive(100L))
        assertFalse(news(end = 99L).isLive(100L))
    }

    @Test
    fun `an event with no end time counts as ended`() {
        assertFalse(news(end = 0L).isLive(100L))
    }
}
