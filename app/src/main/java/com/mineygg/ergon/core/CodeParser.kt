package com.mineygg.ergon.core

import java.time.LocalDate
import java.time.ZoneOffset
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class ScrapedCode(val code: String, val rewards: List<String>, val section: String)

/**
 * Heading-agnostic Game8 code table parser (port of the tested cheerio version).
 *
 * Walks the page in document order, tracks the h2/h3/h4 path plus intro paragraphs, and extracts
 * every table that has copy-to-clipboard code inputs. Expired codes are skipped by four checks:
 * heading text, a passed expiry date in the intro text, row-level expired markers, and the fact
 * that Game8's expired list has no copy inputs at all.
 */
object CodeParser {
    private val EXPIRED_RE = Regex("""\b(expired|inactive|no longer (valid|work))""", RegexOption.IGNORE_CASE)
    private val EXPIRY_RE = Regex(
        """(?:expire[sd]?|cannot be redeemed|valid (?:until|through)|until)[^.]{0,80}?\b([A-Z][a-z]+)\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})"""
    )
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )
    private val WS = Regex("""\s+""")
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun insideTable(el: Element): Boolean = el.parents().any { it.tagName() == "table" }

    private fun parseExpiry(text: String): Long? {
        val m = EXPIRY_RE.find(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1].lowercase())
        if (month == -1) return null
        return runCatching {
            LocalDate.of(m.groupValues[3].toInt(), month + 1, m.groupValues[2].toInt())
                .atTime(23, 59, 59)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()
    }

    fun parse(html: String, nowMs: Long = System.currentTimeMillis()): List<ScrapedCode> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<ScrapedCode>()
        val seen = HashSet<String>()

        var h2 = ""
        var h3 = ""
        var h4 = ""
        val intro = StringBuilder()

        for (el in doc.select("h1, h2, h3, h4, p, table")) {
            when (el.tagName()) {
                "h1" -> {}
                "h2" -> {
                    h2 = el.text().trim()
                    h3 = ""
                    h4 = ""
                    intro.setLength(0)
                }
                "h3" -> {
                    h3 = el.text().trim()
                    h4 = ""
                    intro.setLength(0)
                }
                // Keep intro on h4: the expiry note usually sits above it.
                "h4" -> {
                    h4 = el.text().trim()
                }
                "p" -> if (!insideTable(el)) intro.append(' ').append(el.text())
                "table" -> {
                    if (insideTable(el)) continue
                    if (el.select("input.a-clipboard__textInput").isEmpty()) continue

                    val section = listOf(h2, h3, h4).filter { it.isNotEmpty() }.joinToString(" › ")
                    if (EXPIRED_RE.containsMatchIn(section)) continue

                    val expiry = parseExpiry(intro.toString().replace(WS, " "))
                    if (expiry != null && expiry + DAY_MS < nowMs) continue

                    for (row in el.select("tr")) {
                        val code = row.selectFirst("input.a-clipboard__textInput")?.attr("value")?.trim().orEmpty()
                        if (code.isEmpty()) continue

                        val flag = row.className() + " " + row.select("s, del, strike, .a-red").text()
                        if (EXPIRED_RE.containsMatchIn(flag)) continue
                        if (!seen.add(code.lowercase())) continue

                        val cell = row.select("td").lastOrNull()
                        val rewards = ArrayList<String>()
                        cell?.select(".align")?.forEach { div ->
                            val t = div.text().replace(WS, " ").trim()
                            if (t.isNotEmpty()) rewards.add(t)
                        }
                        if (rewards.isEmpty() && cell != null) {
                            // Plain-text reward cells: "・Primogem x60<br>・Mora x10,000"
                            cell.html().split(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)).forEach { part ->
                                val t = Jsoup.parse(part).text().replace(Regex("^[・\\s]+"), "").replace(WS, " ").trim()
                                if (t.isNotEmpty()) rewards.add(t)
                            }
                        }
                        out.add(ScrapedCode(code, rewards, section))
                    }
                }
            }
        }
        return out
    }
}
