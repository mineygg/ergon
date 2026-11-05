package com.mineygg.ergon.core

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which webhook URLs are taken, how codes are laid out and split into messages, and how Discord's rate limits are followed. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class DiscordTest {
    private val token = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDEFG"
    private val url = "https://discord.com/api/webhooks/123456789012345678/$token"

    private fun codes(n: Int, rewards: List<String> = listOf("Primogem ×60", "Mora ×10000")) =
        (1..n).map { ScrapedCode("CODE$it", rewards, "Active") }

    private fun content(batch: Batch): String = JSONObject(batch.body).getString("content")

    @Test
    fun `only Discord's own webhook addresses are taken`() {
        assertTrue(Discord.isWebhookUrl(url))
        assertTrue(Discord.isWebhookUrl("  $url/ "))
        assertTrue(Discord.isWebhookUrl("https://ptb.discord.com/api/v10/webhooks/123456789012345678/$token"))
        assertTrue(Discord.isWebhookUrl("https://discordapp.com/api/webhooks/123456789012345678/$token"))
        assertFalse(Discord.isWebhookUrl("http://discord.com/api/webhooks/123456789012345678/$token"))
        assertFalse(Discord.isWebhookUrl("https://discord.com.evil.example/api/webhooks/123456789012345678/$token"))
        assertFalse(Discord.isWebhookUrl("https://example.com/api/webhooks/123456789012345678/$token"))
        assertFalse(Discord.isWebhookUrl("https://discord.com/api/webhooks/123456789012345678"))
        assertFalse(Discord.isWebhookUrl("https://discord.com/api/webhooks/123/$token"))
        assertFalse(Discord.isWebhookUrl("https://discord.com/api/webhooks/123456789012345678/short"))
        assertFalse(Discord.isWebhookUrl(""))
    }

    @Test
    fun `a code is a header, its rewards in small text and a bold link`() {
        val text = content(Discord.batches(Game.GENSHIN, codes(1)).single())
        assertTrue(
            text.contains(
                "## `CODE1`\n-# Primogem ×60, Mora ×10000\n- **[Click to claim](https://genshin.hoyoverse.com/en/gift?code=CODE1)**"
            )
        )
        assertTrue(text.startsWith("# New Genshin Impact gift code\n"))
        assertTrue(text.trimEnd().endsWith("-# Ergon"))
    }

    @Test
    fun `a code without rewards has no small text line`() {
        val text = content(Discord.batches(Game.HSR, codes(1, emptyList())).single())
        assertTrue(text.contains("## `CODE1`\n- **[Click to claim]"))
        assertFalse(text.contains("-# \n"))
    }

    @Test
    fun `a few codes fit in one message`() {
        val batches = Discord.batches(Game.GENSHIN, codes(3))
        assertEquals(1, batches.size)
        assertEquals(3, batches.single().codes.size)
        assertEquals(3, Regex("## `CODE").findAll(content(batches.single())).count())
    }

    @Test
    fun `many codes are split into several messages that each fit Discord's limit`() {
        val all = codes(60)
        val batches = Discord.batches(Game.GENSHIN, all)
        assertTrue(batches.size > 1)
        for (b in batches) assertTrue(content(b).length <= 2000)
        // Every code is sent once, in the order given.
        assertEquals(all.map { it.code }, batches.flatMap { b -> b.codes.map { it.code } })
        assertTrue(content(batches.first()).contains("(1/${batches.size})"))
        assertTrue(content(batches.last()).contains("(${batches.size}/${batches.size})"))
    }

    @Test
    fun `no message is longer than Discord allows, however long the rewards are`() {
        val long = listOf("x".repeat(900))
        for (b in Discord.batches(Game.HSR, codes(40, long))) {
            assertTrue(content(b).length <= 2000)
        }
    }

    @Test
    fun `a single batch has no part number, and one code is singular`() {
        assertTrue(content(Discord.batches(Game.GENSHIN, codes(3)).single()).startsWith("# New Genshin Impact gift codes\n"))
        assertTrue(content(Discord.batches(Game.GENSHIN, codes(1)).single()).startsWith("# New Genshin Impact gift code\n"))
    }

    @Test
    fun `a message never pings anyone and shows no link preview`() {
        val body = JSONObject(Discord.batches(Game.GENSHIN, codes(1)).single().body)
        assertEquals(0, body.getJSONObject("allowed_mentions").getJSONArray("parse").length())
        assertEquals(4, body.getInt("flags"))
    }

    @Test
    fun `text from the page can't format or mention`() {
        val text = content(Discord.batches(Game.ZZZ, codes(1, listOf("**Bold** @everyone [x](https://evil.example)"))).single())
        assertTrue(text.contains("\\*\\*Bold\\*\\*"))
        assertTrue(text.contains("\\[x\\]"))
    }

    @Test
    fun `each game links to its own redeem page`() {
        assertEquals("https://hsr.hoyoverse.com/gift?code=ABC", Discord.redeemLink(Game.HSR, "ABC"))
        assertEquals("https://zenless.hoyoverse.com/redemption?code=ABC", Discord.redeemLink(Game.ZZZ, "ABC"))
    }

    @Test
    fun `the test message looks like a code message but carries no real code`() {
        val hello = Discord.hello(Game.ZZZ)
        assertTrue(hello.codes.isEmpty())
        val text = content(hello)
        assertTrue(text.contains("connected"))
        assertTrue(text.contains("## `TEST`\n-# Test rewards\n- **[KeepAndroidOpen](https://keepandroidopen.org)**"))
        assertFalse(text.contains("Click to claim"))
        assertTrue(text.length <= 2000)
    }

    @Test
    fun `after a message Ergon pauses a second, or until the limit resets`() {
        assertEquals(1_000L, Discord.waitAfterSuccessMs(4, 1_500L))
        assertEquals(1_000L, Discord.waitAfterSuccessMs(null, null))
        assertEquals(1_600L, Discord.waitAfterSuccessMs(0, 1_500L))
        assertEquals(1_000L, Discord.waitAfterSuccessMs(0, 200L))
    }

    @Test
    fun `a 429 is waited out for as long as Discord says`() {
        assertEquals(1_750L, Discord.retryAfterMs("""{"message":"You are being rate limited.","retry_after":1.5,"global":false}""", null))
        assertEquals(2_250L, Discord.retryAfterMs("", "2"))
        assertEquals(5_250L, Discord.retryAfterMs("not json", null))
    }
}
