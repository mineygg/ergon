package com.mineygg.ergon.core

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeParserTest {
    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val livestream = """
        <h2>HSR 4.7 Livestream Codes</h2><h3>All 4.7 Livestream Redeem Codes</h3>
        <ul class="a-list">
          <li><a href="https://hsr.hoyoverse.com/gift?code=NEVERGONNAGIVE" target="_blank">NEVERGONNAGIVE</a>
            (<a href="/x">Stellar Jades</a> x100, <a href="/y">Credits</a> x50,000)</li>
          <li><a href="https://hsr.hoyoverse.com/gift?code=AHABLESSYOU" target="_blank">AHABLESSYOU</a>
            (<a href="/x">Stellar Jades</a> x100, <a href="/z">Refined Aether</a> x4)</li>
        </ul>
        <p>The 4.7 Livestream redeem codes expire on <b>October 31, 2026</b> at <b>10:59 AM (UTC-5)</b>.</p>
    """.trimIndent()

    @Test
    fun `livestream list is read with its rewards`() {
        val codes = CodeParser.parse(livestream, at(2026, 10, 30))
        assertEquals(listOf("NEVERGONNAGIVE", "AHABLESSYOU"), codes.map { it.code })
        assertEquals(listOf("Stellar Jades x100", "Credits x50,000"), codes[0].rewards)
        assertEquals(listOf("Stellar Jades x100", "Refined Aether x4"), codes[1].rewards)
    }

    @Test
    fun `livestream list is dropped once the note below it has expired`() {
        assertTrue(CodeParser.parse(livestream, at(2026, 11, 5)).isEmpty())
    }

    @Test
    fun `list under an expired heading is ignored`() {
        val html = """
            <h2>All Expired Star Rail Redeem Codes</h2><h3>HSR 4.6 Livestream Codes</h3>
            <ul><li><a href="https://hsr.hoyoverse.com/gift?code=KA5SV3FJM7WX">KA5SV3FJM7WX</a> (Credits x50,000)</li></ul>
        """.trimIndent()
        assertTrue(CodeParser.parse(html, at(2026, 10, 30)).isEmpty())
    }

    @Test
    fun `clipboard table is still read`() {
        val html = """
            <h2>Active Redeem Codes for October 2026</h2>
            <table>
              <tr><th>Redeem Codes</th><th>Reward</th></tr>
              <tr>
                <td><input class="a-clipboard__textInput" value="DSLSPNBREASP" readonly></td>
                <td><div class="align">Stellar Jade x50</div><div class="align">Credit x10,000</div></td>
              </tr>
            </table>
        """.trimIndent()
        val codes = CodeParser.parse(html, at(2026, 10, 30))
        assertEquals(listOf("DSLSPNBREASP"), codes.map { it.code })
        assertEquals(listOf("Stellar Jade x50", "Credit x10,000"), codes[0].rewards)
    }

    @Test
    fun `a code shown in a list and a table counts once`() {
        val html = livestream + """
            <h2>Active Redeem Codes</h2>
            <table><tr><td><input class="a-clipboard__textInput" value="NEVERGONNAGIVE"></td><td></td></tr></table>
        """.trimIndent()
        assertEquals(1, CodeParser.parse(html, at(2026, 10, 30)).count { it.code == "NEVERGONNAGIVE" })
    }
}
