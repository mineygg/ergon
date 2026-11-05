package com.mineygg.ergon.core

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** One message to Discord: the codes it carries and its JSON body. */
class Batch(val codes: List<ScrapedCode>, val body: String)

/** What came of sending some batches: the codes that got through, and why the rest didn't (null = all sent). */
class Delivery(val delivered: List<ScrapedCode>, val failure: String?, val invalid: Boolean = false)

/**
 * Posts new gift codes to a Discord webhook. The webhook URL is a secret (whoever has it can post in the channel), so
 * it is never logged and only Discord's own addresses are accepted.
 *
 * A message is plain Discord markdown (a header per code, the rewards in small text, a bold link to claim it), so
 * it is limited to Discord's 2000 characters of content: codes that don't fit go into further messages.
 *
 * Discord allows about 5 requests per 2 seconds per webhook. Messages are sent one after the other, with a pause
 * between them, a longer one when Discord says the limit is used up, and a wait of the length Discord asks for when
 * it answers 429.
 */
object Discord {
    private val URL_PATTERN =
        Regex("""^https://(?:(?:ptb|canary)\.)?(?:discord|discordapp)\.com/api(?:/v\d+)?/webhooks/\d{17,20}/[A-Za-z0-9._-]{20,}$""")

    /** Discord's limit for the text of a message is 2000 characters. */
    private const val MAX_CONTENT = 2000

    /** Left free in every message for the title, the part number and the footer. */
    private const val FRAME = 200
    private const val MAX_REWARDS = 160
    private const val SUPPRESS_EMBEDS = 4 // no link preview cards under the message

    /** The pause between two messages. Much more than Discord's 5 per 2 seconds needs. */
    private const val MIN_GAP_MS = 1_000L
    private const val MAX_TRIES = 3

    /** A single wait longer than this is not worth holding the run for: the codes are tried again later. */
    private const val MAX_WAIT_MS = 30_000L

    /** All the waiting for rate limits and retries in one sending. */
    private const val WAIT_BUDGET_MS = 60_000L
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val USER_AGENT = "Ergon (https://github.com/mineygg/ergon)"

    /** The text as typed, without spaces around it or a trailing slash. */
    fun clean(input: String): String = input.trim().trimEnd('/')

    fun isWebhookUrl(url: String): Boolean = URL_PATTERN.matches(clean(url))

    /** The page where a code can be redeemed, with the code filled in. */
    fun redeemLink(game: Game, code: String): String {
        val page = when (game) {
            Game.GENSHIN -> "https://genshin.hoyoverse.com/en/gift"
            Game.HSR -> "https://hsr.hoyoverse.com/gift"
            Game.ZZZ -> "https://zenless.hoyoverse.com/redemption"
            else -> return ""
        }
        return "$page?code=" + URLEncoder.encode(code, "UTF-8")
    }

    /** Text from a web page, made safe for Discord's markdown. */
    private fun plain(s: String): String = s.replace(Regex("\\s+"), " ").trim().replace(Regex("([\\\\*_~`|>\\[\\]])"), "\\\\$1")

    /** One code as it appears in the message: the code as a header, its rewards in small text, a link to claim it. */
    private fun block(game: Game, c: ScrapedCode): String =
        block(c.code, c.rewards.joinToString(", "), "Click to claim", redeemLink(game, c.code))

    private fun block(code: String, rewardsText: String, linkLabel: String, link: String): String {
        val rewards = plain(rewardsText).take(MAX_REWARDS)
        return buildString {
            append("## `").append(code.replace("`", "")).append("`")
            if (rewards.isNotEmpty()) append("\n-# ").append(rewards)
            if (link.isNotEmpty()) append("\n- **[").append(linkLabel).append("](").append(link).append(")**")
        }
    }

    private fun message(content: String): String = JSONObject()
        .put("content", content)
        .put("flags", SUPPRESS_EMBEDS)
        .put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        .toString()

    /** The codes split into as many messages as Discord's length limit needs, in the order they were given. */
    fun batches(game: Game, codes: List<ScrapedCode>): List<Batch> {
        val groups = ArrayList<List<ScrapedCode>>()
        var current = ArrayList<ScrapedCode>()
        var length = 0
        for (c in codes) {
            val size = block(game, c).length + 2 // and the blank line after it
            if (current.isNotEmpty() && length + size > MAX_CONTENT - FRAME) {
                groups.add(current)
                current = ArrayList()
                length = 0
            }
            current.add(c)
            length += size
        }
        if (current.isNotEmpty()) groups.add(current)

        return groups.mapIndexed { i, group ->
            val part = if (groups.size > 1) " (${i + 1}/${groups.size})" else ""
            val noun = if (codes.size == 1) "gift code" else "gift codes"
            val text = "# New ${game.title} $noun$part\n\n" +
                group.joinToString("\n\n") { block(game, it) } +
                "\n\n-# Ergon"
            Batch(group, message(text.take(MAX_CONTENT)))
        }
    }

    /**
     * The message that tells the webhook works: a made-up code in the same layout as the real ones, so you can see how
     * they will look. Its link goes to keepandroidopen.org instead of a redeem page.
     */
    fun hello(game: Game): Batch = Batch(
        emptyList(),
        message(
            "# Ergon is connected\n\n" +
                block("TEST", "Test rewards", "KeepAndroidOpen", "https://keepandroidopen.org") +
                "\n\n-# New ${game.title} gift codes will be posted here like this."
        )
    )

    /** How long to wait after a message that went through: a short pause, or until the limit resets when it is used up. */
    fun waitAfterSuccessMs(remaining: Int?, resetAfterMs: Long?): Long =
        if (remaining == 0 && resetAfterMs != null) maxOf(MIN_GAP_MS, resetAfterMs + 100) else MIN_GAP_MS

    /** How long Discord asked to wait after a 429: its `retry_after` (seconds), else the Retry-After header, else 5 s. */
    fun retryAfterMs(body: String, header: String?): Long {
        val fromBody = runCatching { JSONObject(body).optDouble("retry_after", Double.NaN) }.getOrDefault(Double.NaN)
        val seconds = if (!fromBody.isNaN()) fromBody else header?.toDoubleOrNull() ?: 5.0
        return (seconds * 1000).toLong() + 250
    }

    private class Reply(val code: Int, val remaining: Int?, val resetAfterMs: Long?, val retryAfterMs: Long)

    private suspend fun post(url: String, body: String): Reply = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).post(body.toRequestBody(JSON)).build()
            Http.client.newCall(req).execute().use { r ->
                val text = if (r.code == 429) r.body?.string().orEmpty() else ""
                Reply(
                    r.code,
                    r.header("X-RateLimit-Remaining")?.toIntOrNull(),
                    r.header("X-RateLimit-Reset-After")?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                    if (r.code == 429) retryAfterMs(text, r.header("Retry-After")) else 0L
                )
            }
        } catch (e: IOException) {
            Reply(0, null, null, 0L)
        }
    }

    /**
     * Sends the batches one after the other. Stops at the first one that can't be delivered (the rest wait for the
     * next time) and says why. A webhook that Discord doesn't know any more is reported as invalid.
     */
    suspend fun send(url: String, batches: List<Batch>, log: (String) -> Unit = {}): Delivery {
        val delivered = ArrayList<ScrapedCode>()
        var budget = WAIT_BUDGET_MS
        var pause = 0L
        for (batch in batches) {
            if (pause > 0) delay(pause)
            var tries = 0
            while (true) {
                val r = post(url, batch.body)
                when {
                    r.code in 200..299 -> {
                        delivered.addAll(batch.codes)
                        pause = waitAfterSuccessMs(r.remaining, r.resetAfterMs)
                        break
                    }
                    r.code == 429 -> {
                        tries++
                        if (tries > MAX_TRIES || r.retryAfterMs > MAX_WAIT_MS || r.retryAfterMs > budget) {
                            return Delivery(delivered, "Discord is asking Ergon to slow down. The rest is sent next time.")
                        }
                        log("  Discord asked to wait ${r.retryAfterMs / 1000} s")
                        delay(r.retryAfterMs)
                        budget -= r.retryAfterMs
                    }
                    r.code == 401 || r.code == 403 || r.code == 404 ->
                        return Delivery(delivered, "Discord doesn't accept this webhook any more (${r.code}).", invalid = true)
                    r.code == 0 || r.code >= 500 -> {
                        tries++
                        val wait = 2_000L * tries
                        if (tries > MAX_TRIES || wait > budget) return Delivery(delivered, "Couldn't reach Discord. The rest is sent next time.")
                        delay(wait)
                        budget -= wait
                    }
                    else -> return Delivery(delivered, "Discord refused the message (${r.code}).")
                }
            }
        }
        return Delivery(delivered, null)
    }

    /** Sends the short "connected" message. Returns what went wrong, or null when it arrived. */
    suspend fun sayHello(url: String, game: Game): String? = send(url, listOf(hello(game))).failure
}
