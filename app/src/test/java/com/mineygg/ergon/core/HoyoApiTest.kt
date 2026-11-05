package com.mineygg.ergon.core

import android.app.Application
import com.mineygg.ergon.data.Credentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the HoYoLAB answers mean: a check-in result, a redeem result, the account list and the plain API answer. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class HoyoApiTest {
    private fun answer(retcode: Int, message: String = "") = """{"retcode":$retcode,"message":"$message","data":null}"""

    // ── Check-in ────────────────────────────────────────────────────────────
    @Test
    fun `retcode 0 is a claimed check-in`() {
        assertEquals(CheckinStatus.SUCCESS, HoyoApi.parseCheckin(answer(0, "OK")).status)
    }

    @Test
    fun `retcode -5003 means it was already claimed today`() {
        assertEquals(CheckinStatus.ALREADY, HoyoApi.parseCheckin(answer(-5003, "Traveler, you've already checked in today")).status)
    }

    @Test
    fun `retcode -100 means the session expired`() {
        assertEquals(CheckinStatus.TOKEN_EXPIRED, HoyoApi.parseCheckin(answer(-100, "Please login")).status)
    }

    @Test
    fun `a risk check asks for a captcha`() {
        val json = """{"retcode":-9999,"message":"risk","data":{"gt_result":{"is_risk":true}}}"""
        assertEquals(CheckinStatus.CAPTCHA, HoyoApi.parseCheckin(json).status)
    }

    @Test
    fun `a risk flag that is false is not a captcha`() {
        val json = """{"retcode":-9999,"message":"x","data":{"gt_result":{"is_risk":false}}}"""
        assertEquals(CheckinStatus.FAILED, HoyoApi.parseCheckin(json).status)
    }

    @Test
    fun `success wins over a risk flag`() {
        val json = """{"retcode":0,"message":"OK","data":{"gt_result":{"is_risk":true}}}"""
        assertEquals(CheckinStatus.SUCCESS, HoyoApi.parseCheckin(json).status)
    }

    @Test
    fun `any other retcode is a failure with its code and message`() {
        val r = HoyoApi.parseCheckin(answer(-1, "oops"))
        assertEquals(CheckinStatus.FAILED, r.status)
        assertEquals("[-1] oops", r.message)
        assertFalse(r.network)
    }

    @Test
    fun `an answer without a retcode is a failure`() {
        assertEquals(CheckinStatus.FAILED, HoyoApi.parseCheckin("{}").status)
    }

    @Test
    fun `an answer that is not JSON is a failure that is not a network error`() {
        val r = HoyoApi.parseCheckin("<html>blocked</html>")
        assertEquals(CheckinStatus.FAILED, r.status)
        assertEquals("Invalid response", r.message)
        assertFalse(r.network)
    }

    // ── Plain API answer ────────────────────────────────────────────────────
    @Test
    fun `an API answer keeps its retcode, message, data and status code`() {
        val r = HoyoApi.parseApi(200, """{"retcode":0,"message":"OK","data":{"points":25}}""")
        assertEquals(0, r.retcode)
        assertEquals("OK", r.message)
        assertEquals(25, r.data!!.getInt("points"))
        assertEquals(200, r.http)
        assertFalse(r.network)
    }

    @Test
    fun `an API answer without data has none`() {
        assertNull(HoyoApi.parseApi(200, answer(-1, "no")).data)
        assertNull(HoyoApi.parseApi(200, """{"retcode":0,"message":"OK"}""").data)
    }

    @Test
    fun `an API answer that is not JSON is marked unreadable with the status code`() {
        val r = HoyoApi.parseApi(502, "Bad Gateway")
        assertEquals(Int.MIN_VALUE, r.retcode)
        assertEquals("HTTP 502: unreadable response", r.message)
        assertNull(r.data)
        assertEquals(502, r.http)
    }

    @Test
    fun `an API answer without a retcode has the lowest number`() {
        assertEquals(Int.MIN_VALUE, HoyoApi.parseApi(200, "{}").retcode)
    }

    // ── Redeem ──────────────────────────────────────────────────────────────
    @Test
    fun `retcode 0 is a redeemed code`() {
        val r = HoyoApi.parseRedeem(200, answer(0, "OK"))
        assertTrue(r.success)
        assertEquals("Redeemed!", r.message)
    }

    @Test
    fun `every used, expired or invalid code retcode counts as already claimed`() {
        for (code in listOf(-2017, -2018, -2003, -2001, -2011, -2006)) {
            val r = HoyoApi.parseRedeem(200, answer(code, "nope"))
            assertFalse("$code", r.success)
            assertTrue("$code", r.alreadyClaimed)
            assertFalse("$code", r.tokenExpired)
            assertFalse("$code", r.rateLimited)
        }
    }

    @Test
    fun `a failed redeem shows its retcode and message`() {
        assertEquals("[-2018] Redemption code has already been used", HoyoApi.parseRedeem(200, answer(-2018, "Redemption code has already been used")).message)
    }

    @Test
    fun `retcode -1071 means the cookie token expired`() {
        val r = HoyoApi.parseRedeem(200, answer(-1071, "Please login"))
        assertTrue(r.tokenExpired)
        assertFalse(r.alreadyClaimed)
        assertFalse(r.success)
    }

    @Test
    fun `retcodes -1003 and -1016 mean too many requests`() {
        for (code in listOf(-1003, -1016)) {
            val r = HoyoApi.parseRedeem(200, answer(code, "slow down"))
            assertTrue("$code", r.rateLimited)
            assertFalse("$code", r.alreadyClaimed)
        }
    }

    @Test
    fun `an unknown retcode is a plain failure`() {
        val r = HoyoApi.parseRedeem(200, answer(-12345, "who knows"))
        assertFalse(r.success)
        assertFalse(r.alreadyClaimed)
        assertFalse(r.tokenExpired)
        assertFalse(r.rateLimited)
        assertFalse(r.networkError)
    }

    @Test
    fun `an answer without a message says unknown error`() {
        assertEquals("[-777] Unknown error", HoyoApi.parseRedeem(200, """{"retcode":-777}""").message)
    }

    @Test
    fun `an HTTP 429 is a rate limit however the body looks`() {
        val r = HoyoApi.parseRedeem(429, answer(0, "OK"))
        assertTrue(r.rateLimited)
        assertFalse(r.success)
        assertEquals("Rate limited (HTTP 429)", r.message)
    }

    @Test
    fun `a body that says too many requests is a rate limit`() {
        assertTrue(HoyoApi.parseRedeem(200, "Too Many Requests").rateLimited)
        assertTrue(HoyoApi.parseRedeem(200, "error: too many requests, try later").rateLimited)
    }

    @Test
    fun `an HTML page is a rate limit or a CDN block`() {
        val r = HoyoApi.parseRedeem(200, "<html><body>Access denied</body></html>")
        assertTrue(r.rateLimited)
        assertEquals("Server returned HTML (rate limit or CDN block)", r.message)
    }

    @Test
    fun `an answer that is not JSON is a failure with the status code`() {
        val r = HoyoApi.parseRedeem(503, "service unavailable")
        assertFalse(r.success)
        assertFalse(r.rateLimited)
        assertEquals("Non-JSON response (HTTP 503)", r.message)
    }

    @Test
    fun `a game without gift codes is not asked`() {
        val r = runBlocking { HoyoApi.redeem(Game.HI3, "CODE", Account("1", "os_usa", "x"), Credentials(ltuid = "1", cookieToken = "c")) }
        assertFalse(r.success)
        assertEquals("Redeem not supported for Honkai Impact 3rd", r.message)
    }

    @Test
    fun `nothing is sent without the cookie token`() {
        val r = runBlocking { HoyoApi.redeem(Game.GENSHIN, "CODE", Account("1", "os_usa", "x"), Credentials(ltoken = "t", ltuid = "1")) }
        assertFalse(r.success)
        assertEquals("cookie_token_v2 not set", r.message)
        assertFalse(r.networkError)
    }

    // ── Account list ────────────────────────────────────────────────────────
    @Test
    fun `the accounts of a profile are read`() {
        val json = """{"retcode":0,"message":"OK","data":{"list":[
            {"game_uid":"800111","region":"os_usa","nickname":"Traveler"},
            {"game_uid":"700222","region":"os_euro","nickname":"Wanderer"}]}}"""
        val list = HoyoApi.parseAccounts(json)!!
        assertEquals(listOf(Account("800111", "os_usa", "Traveler"), Account("700222", "os_euro", "Wanderer")), list)
    }

    @Test
    fun `an account without a nickname has an empty one`() {
        val json = """{"retcode":0,"data":{"list":[{"game_uid":"1","region":"os_asia"}]}}"""
        assertEquals("", HoyoApi.parseAccounts(json)!!.single().nickname)
    }

    @Test
    fun `a profile without accounts is an empty list, not a failure`() {
        val list = HoyoApi.parseAccounts("""{"retcode":0,"data":{"list":[]}}""")
        assertNotNull(list)
        assertTrue(list!!.isEmpty())
    }

    @Test
    fun `an error answer is not an account list`() {
        assertNull(HoyoApi.parseAccounts(answer(-100, "Please login")))
    }

    @Test
    fun `an answer that cannot be read is not an account list`() {
        assertNull(HoyoApi.parseAccounts(""))
        assertNull(HoyoApi.parseAccounts("<html>"))
        assertNull(HoyoApi.parseAccounts("""{"retcode":0,"data":{}}"""))
        assertNull(HoyoApi.parseAccounts("""{"retcode":0,"data":{"list":[{"region":"os_usa"}]}}"""))
    }
}
