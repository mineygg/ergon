package com.mineygg.ergon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list of games: what Ergon knows about each one has to stay consistent, other code relies on it. */
class GamesTest {
    @Test
    fun `the five games keep their ids and order`() {
        assertEquals(listOf("genshin", "hsr", "zzz", "hi3", "tot"), Game.entries.map { it.id })
    }

    @Test
    fun `ids, titles and profile names are all different`() {
        assertEquals(Game.entries.size, Game.entries.map { it.id }.toSet().size)
        assertEquals(Game.entries.size, Game.entries.map { it.title }.toSet().size)
        assertEquals(Game.entries.size, Game.entries.map { it.biz }.toSet().size)
    }

    @Test
    fun `every game has a readable title and an opaque color`() {
        for (g in Game.entries) {
            assertTrue(g.title.isNotBlank())
            assertEquals("${g.id} color has an alpha of 255", 0xFFL, g.color ushr 24)
        }
    }

    @Test
    fun `every check-in is sent over https and names its event`() {
        for (g in Game.entries) {
            assertTrue("${g.id} url", g.checkin.url.startsWith("https://"))
            assertTrue("${g.id} event", g.checkin.actId.isNotBlank())
        }
        assertEquals(Game.entries.size, Game.entries.map { it.checkin.actId }.toSet().size)
    }

    @Test
    fun `Zenless checks in with the login cookie, the others with the HoYoLAB one`() {
        assertTrue(Game.ZZZ.checkin.useCookieToken)
        for (g in Game.entries - Game.ZZZ) assertFalse(g.id, g.checkin.useCookieToken)
    }

    @Test
    fun `the sign game header is only sent for Zenless and Tears of Themis`() {
        assertEquals("zzz", Game.ZZZ.checkin.signGame)
        assertEquals("nxx", Game.TOT.checkin.signGame)
        assertNull(Game.GENSHIN.checkin.signGame)
        assertNull(Game.HSR.checkin.signGame)
        assertNull(Game.HI3.checkin.signGame)
    }

    @Test
    fun `only Genshin, Star Rail and Zenless have gift codes`() {
        assertEquals(setOf(Game.GENSHIN, Game.HSR, Game.ZZZ), Game.entries.filter { it.redeem != null }.toSet())
        assertNull(Game.HI3.redeem)
        assertNull(Game.TOT.redeem)
    }

    @Test
    fun `a redeem setup reads its code page and posts to its own server`() {
        for (g in Game.entries.filter { it.redeem != null }) {
            val r = g.redeem!!
            assertTrue("${g.id} page", r.scrapeUrl.startsWith("https://game8.co/games/"))
            assertTrue("${g.id} endpoint", r.redeemUrl.startsWith("https://public-operation-"))
            assertTrue("${g.id} endpoint path", r.redeemUrl.endsWith("/common/apicdkey/api/webExchangeCdkey"))
            assertEquals("${g.id} game_biz is the profile's", g.biz, r.gameBiz)
        }
    }

    @Test
    fun `the API names of the games match the hoyo-codes API`() {
        assertEquals("genshin", Game.GENSHIN.redeem!!.seriaGame)
        assertEquals("hkrpg", Game.HSR.redeem!!.seriaGame)
        assertEquals("nap", Game.ZZZ.redeem!!.seriaGame)
    }

    @Test
    fun `each game has its own keep-alive code, in capitals`() {
        val codes = Game.entries.mapNotNull { it.redeem?.keepAliveCode }
        assertEquals(3, codes.toSet().size)
        for (c in codes) assertEquals(c.uppercase(), c)
    }

    @Test
    fun `a game is found by its id`() {
        assertNotNull(Game.entries.firstOrNull { it.id == "zzz" })
        assertEquals(Game.HI3, Game.entries.first { it.id == "hi3" })
    }

    @Test
    fun `every region code the profile can return has a name`() {
        val codes = listOf(
            "os_usa", "os_euro", "os_asia", "os_cht",
            "prod_official_usa", "prod_official_eur", "prod_official_asia", "prod_official_cht",
            "prod_gf_us", "prod_gf_eu", "prod_gf_jp", "prod_gf_sg"
        )
        val names = setOf("America", "Europe", "Asia", "TW, HK, MO")
        for (c in codes) assertTrue(c, regionName(c) in names)
    }

    @Test
    fun `the same server has the same name in every code family`() {
        assertEquals(regionName("os_usa"), regionName("prod_gf_us"))
        assertEquals(regionName("os_euro"), regionName("prod_official_eur"))
        assertEquals(regionName("os_asia"), regionName("prod_gf_jp"))
        assertEquals(regionName("os_cht"), regionName("prod_official_cht"))
    }

    @Test
    fun `an empty or new region code is shown as it is`() {
        assertEquals("", regionName(""))
        assertEquals("os_mars", regionName("os_mars"))
    }

    @Test
    fun `an account keeps what it was given`() {
        val a = Account("1001", "os_usa", "Traveler")
        assertEquals("1001", a.uid)
        assertEquals("os_usa", a.region)
        assertEquals("Traveler", a.nickname)
        assertEquals(a, Account("1001", "os_usa", "Traveler"))
    }
}
