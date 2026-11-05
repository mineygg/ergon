package com.mineygg.ergon.data

import com.mineygg.ergon.core.Account
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.regionName
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionsTest {
    private val usa = Account("1001", "os_usa", "Trailblazer")
    private val euro = Account("2002", "os_euro", "Traveler")
    private val asia = Account("3003", "os_asia", "Wanderer")
    private val known = listOf(usa, euro, asia)

    @Test
    fun `every account is used until a region is switched off`() {
        assertEquals(known, Settings().accountsToUse(Game.GENSHIN, known))
        assertEquals(setOf("os_usa", "os_euro", "os_asia"), Settings().pickedRegions(Game.GENSHIN, known))
    }

    @Test
    fun `switching a region off leaves the others`() {
        val s = Settings().withRegion(Game.GENSHIN, known, "os_euro", on = false)
        assertEquals(listOf(usa, asia), s.accountsToUse(Game.GENSHIN, known))
    }

    @Test
    fun `the last region cannot be switched off`() {
        var s = Settings().withRegion(Game.GENSHIN, known, "os_usa", on = false)
        s = s.withRegion(Game.GENSHIN, known, "os_euro", on = false)
        s = s.withRegion(Game.GENSHIN, known, "os_asia", on = false)
        assertEquals(listOf(asia), s.accountsToUse(Game.GENSHIN, known))
    }

    @Test
    fun `a region can be switched back on`() {
        var s = Settings().withRegion(Game.GENSHIN, known, "os_euro", on = false)
        s = s.withRegion(Game.GENSHIN, known, "os_euro", on = true)
        assertEquals(known, s.accountsToUse(Game.GENSHIN, known))
    }

    @Test
    fun `a region without an account is ignored`() {
        val s = Settings().withRegion(Game.GENSHIN, known, "os_cht", on = true)
        assertEquals(Settings(), s)
    }

    @Test
    fun `picked regions that are gone fall back to every account`() {
        val s = Settings(regions = mapOf(Game.GENSHIN.id to setOf("os_cht")))
        assertEquals(known, s.accountsToUse(Game.GENSHIN, known))
    }

    @Test
    fun `games do not share their regions`() {
        val s = Settings().withRegion(Game.GENSHIN, known, "os_usa", on = false)
        assertEquals(known, s.accountsToUse(Game.HSR, known))
    }

    @Test
    fun `region codes get a readable name`() {
        assertEquals("Europe", regionName("os_euro"))
        assertEquals("America", regionName("prod_official_usa"))
        assertEquals("TW, HK, MO", regionName("prod_gf_sg"))
        assertEquals("some_new_region", regionName("some_new_region"))
    }
}
