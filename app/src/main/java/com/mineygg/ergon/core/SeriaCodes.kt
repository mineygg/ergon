package com.mineygg.ergon.core

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * The hoyo-codes API (hoyo-codes.seria.moe): a second source of gift codes next to the Game8 page. It only lists codes
 * it considers valid. Each source succeeds or fails on its own, see [fetch].
 */
object SeriaCodes {
    private const val BASE = "https://hoyo-codes.seria.moe/codes"
    private val CODE = Regex("[A-Za-z0-9]{6,24}")

    /** The codes of [game] (the API's name for it), or null when the request failed or the answer wasn't understood. */
    suspend fun fetch(game: String): List<ScrapedCode>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$BASE?game=$game").header("Accept", "application/json").build()
            Http.client.newCall(request).execute().use { r ->
                if (r.isSuccessful) parse(r.body?.string().orEmpty()) else null
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Null for anything that isn't an answer with a "codes" list. An empty list is a real "no codes". */
    fun parse(json: String): List<ScrapedCode>? {
        val list = runCatching { JSONObject(json).getJSONArray("codes") }.getOrNull() ?: return null
        val out = ArrayList<ScrapedCode>()
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (!item.optString("status", "OK").equals("OK", ignoreCase = true)) continue
            val code = item.optString("code").trim()
            if (!CODE.matches(code) || !seen.add(code.lowercase())) continue
            out.add(ScrapedCode(code, rewards(item.optString("rewards")), "hoyo-codes"))
        }
        return out
    }

    /** "Mora*10000;"Energy-Replenishing Meal"*3" becomes ["Mora x10000", "Energy-Replenishing Meal x3"]. */
    private fun rewards(raw: String): List<String> =
        raw.split(';')
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }
            .map { r ->
                val star = r.lastIndexOf('*')
                if (star > 0) "${r.substring(0, star).trim()} x${r.substring(star + 1).trim()}" else r
            }

    /** Both lists as one, without doubles (case-insensitive). The first list wins when a code is in both. */
    fun merge(first: List<ScrapedCode>, second: List<ScrapedCode>): List<ScrapedCode> {
        val seen = HashSet<String>()
        return (first + second).filter { seen.add(it.code.lowercase()) }
    }
}
