package com.mineygg.ergon.core

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The hoyo-codes answer is read correctly, and the two code sources merge without doubles. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class SeriaCodesTest {
    private val sample = """
        {"codes":[
          {"id":821,"code":"NEEIJB764CA3","status":"OK","game":"genshin","rewards":"Primogem*60"},
          {"id":819,"code":"NEEI8A8341OW","status":"OK","game":"genshin","rewards":""},
          {"id":837,"code":"MIJIQ23V2437","status":"OK","game":"genshin","rewards":"Mora*10000;Adventurer's Experience*10;Fine Enhancement Ore*5"},
          {"id":836,"code":"KEEPUPWITHMITYA","status":"OK","game":"genshin","rewards":"Guide to Charity*3;\"Energy-Replenishing Meal\"*3;Kristallfold*3"}
        ],"game":"genshin"}
    """.trimIndent()

    private fun code(code: String, vararg rewards: String) = ScrapedCode(code, rewards.toList(), "x")

    @Test
    fun `the sample answer is read, with the rewards written out`() {
        val list = SeriaCodes.parse(sample)!!
        assertEquals(listOf("NEEIJB764CA3", "NEEI8A8341OW", "MIJIQ23V2437", "KEEPUPWITHMITYA"), list.map { it.code })
        assertEquals(listOf("Primogem x60"), list[0].rewards)
        assertEquals(emptyList<String>(), list[1].rewards)
        assertEquals(
            listOf("Mora x10000", "Adventurer's Experience x10", "Fine Enhancement Ore x5"),
            list[2].rewards
        )
        assertEquals(listOf("Guide to Charity x3", "Energy-Replenishing Meal x3", "Kristallfold x3"), list[3].rewards)
        assertEquals("hoyo-codes", list[0].section)
    }

    @Test
    fun `a code that is not OK is dropped`() {
        val json = """{"codes":[{"code":"AAAAAA111","status":"NOT_OK","rewards":""},{"code":"BBBBBB222","status":"OK","rewards":""}]}"""
        assertEquals(listOf("BBBBBB222"), SeriaCodes.parse(json)!!.map { it.code })
    }

    @Test
    fun `an entry without a status counts as OK`() {
        assertEquals(listOf("CCCCCC333"), SeriaCodes.parse("""{"codes":[{"code":"CCCCCC333"}]}""")!!.map { it.code })
    }

    @Test
    fun `codes that can't be real, and doubles, are dropped`() {
        val json = """{"codes":[{"code":"SHORT"},{"code":"has space1"},{"code":"DDDDDD444"},{"code":"dddddd444"}]}"""
        assertEquals(listOf("DDDDDD444"), SeriaCodes.parse(json)!!.map { it.code })
    }

    @Test
    fun `an answer that isn't a list of codes is a failure, an empty list is not`() {
        assertNull(SeriaCodes.parse("not json"))
        assertNull(SeriaCodes.parse(""))
        assertNull(SeriaCodes.parse("""{"game":"genshin"}"""))
        assertNull(SeriaCodes.parse("""{"error":"unknown game"}"""))
        assertNotNull(SeriaCodes.parse("""{"codes":[]}"""))
        assertEquals(0, SeriaCodes.parse("""{"codes":[]}""")!!.size)
    }

    @Test
    fun `merging leaves no doubles, and the first list wins`() {
        val site = listOf(code("ABCDEF1", "from the page"), code("PAGEONLY1"))
        val api = listOf(code("abcdef1", "from the api"), code("APIONLY22"))
        val merged = SeriaCodes.merge(site, api)
        assertEquals(listOf("ABCDEF1", "PAGEONLY1", "APIONLY22"), merged.map { it.code })
        assertEquals(listOf("from the page"), merged[0].rewards)
    }

    @Test
    fun `merging with an empty list changes nothing`() {
        val list = listOf(code("ABCDEF1"), code("GHIJKL2"))
        assertEquals(list, SeriaCodes.merge(list, emptyList()))
        assertEquals(list, SeriaCodes.merge(emptyList(), list))
    }
}
