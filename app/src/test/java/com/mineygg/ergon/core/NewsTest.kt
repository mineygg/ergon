package com.mineygg.ergon.core

import android.app.Application
import com.mineygg.ergon.data.NewsItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which events count as important, and that the games' event feeds are read and kept as they should. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class NewsTest {
    private fun important(game: Game, title: String, text: String = "", type: Int = 2) =
        NewsWords.isImportant(game, type, title, text)

    @Test
    fun `a premium currency or pass makes an event important for its game`() {
        assertTrue(important(Game.GENSHIN, "Preview Discussion Room! Intertwined Fate ×500 Giveaway!"))
        assertTrue(important(Game.GENSHIN, "Genshin Impact 6th Anniversary Forum Commenting Event!", "Win a Blessing of the Welkin Moon!"))
        assertTrue(important(Game.HSR, "[Stellar Jade Reward] Take Part in the Discussions"))
        assertTrue(important(Game.ZZZ, "Participate for a chance to obtain Master Tape ×10!"))
        assertTrue(important(Game.ZZZ, "Version 3.3 Twitch Drops Event Now Live", "Watch streams, win Polychrome!"))
        assertTrue(important(Game.TOT, "New Year's Commenting Event", "a server-wide redemption code for 100 S-Chips"))
    }

    @Test
    fun `a currency only counts in its own game`() {
        assertFalse(important(Game.HSR, "Win 1600 Genesis Crystals"))
        assertFalse(important(Game.GENSHIN, "Win 1600 Stellar Jade"))
    }

    @Test
    fun `web events, raffles, figures and hardware are important in every game`() {
        assertTrue(important(Game.GENSHIN, "The \"Anniversary Memories Album\" web event is now live"))
        assertTrue(important(Game.HI3, "Elysia raffle", "3 winners"))
        assertTrue(important(Game.HI3, "Participate to win an Elysia 1/8 Scale Figure"))
        assertTrue(important(Game.ZZZ, "Win a new Phone", ""))
        assertTrue(important(Game.HSR, "Win a MacBook or a laptop"))
        assertTrue(important(Game.GENSHIN, "Giveaway: a PlayStation 5"))
    }

    @Test
    fun `codes, stellin and gift cards alone are not important`() {
        assertFalse(important(Game.HI3, "Time to redeem the 100 Crystals code"))
        assertFalse(important(Game.TOT, "Check in for Stellin"))
        assertFalse(important(Game.HI3, "Leave a comment for a chance to win a \$100 Gift Card, a merch bundle"))
    }

    @Test
    fun `an excluded word wins over a reward word`() {
        assertFalse(important(Game.GENSHIN, "Version 7.1 Share HoYoverse Top-Up Center Event: Win Primogems, Phase 2"))
        assertFalse(important(Game.GENSHIN, "Primogems for the creator contest", "The First Creator Contest Begins"))
        assertFalse(important(Game.GENSHIN, "Genshin Impact Miliastra Wonderland \"Starlit Gala\" Web Event Now Live"))
        assertFalse(important(Game.GENSHIN, "Machine Workshop: win Primogems"))
        assertFalse(important(Game.HSR, "Take Part in the Traveling Mimo Event to Win Stellar Jades"))
        assertFalse(important(Game.HSR, "Take Part in the Travel Mimo Event to Win Stellar Jades"))
        assertFalse(important(Game.HI3, "Today's Time Album Spotlight", "Leave a comment to win a figure"))
    }

    @Test
    fun `top up is the same word however it is written`() {
        assertFalse(important(Game.GENSHIN, "Top up and win Primogems"))
        assertFalse(important(Game.GENSHIN, "Topup and win Primogems"))
        assertFalse(important(Game.GENSHIN, "Top‑Up bonus: Primogems"))
    }

    @Test
    fun `share, screenshot, submission and birthday only count in the title`() {
        assertFalse(important(Game.HSR, "Share Screenshots to Win Stellar Jades"))
        assertFalse(important(Game.GENSHIN, "The Genius Column Submission Event: win Primogems"))
        assertFalse(important(Game.TOT, "Vyn's Birthday Event", "A code for S-Chips"))
        // The same words in the text don't drop a giveaway.
        assertTrue(important(Game.GENSHIN, "Preview Discussion Room! Intertwined Fate ×500 Giveaway!", "share the screenshot that left you speechless"))
    }

    @Test
    fun `the feed's fan-art and Travel Mimo posts are never important`() {
        assertFalse(important(Game.TOT, "Anniversary Fan Art Event win S-Chips", type = 1))
        assertFalse(important(Game.ZZZ, "Win Polychromes", type = 5))
    }

    @Test
    fun `only whole words count`() {
        assertFalse(important(Game.GENSHIN, "The primogeniture and the welkinish"))
        assertTrue(important(Game.GENSHIN, "5 Primogems"))
        assertFalse(important(Game.HI3, "Headphonesque and the figurehead"))
    }

    private val feed = """
        {"list":[
          {"id":"47163124","name":"Dear Traveler, more content is now available in the \"Starlit Gala\" Web Event!",
           "start":"1792652400","end":"1793649600","status":3,"desc":">> Click to Take Part in Event <<","game_id":2,"type":2,
           "app_path":"hoyolab://articles/47163124","web_path":"/article/47163124","create_at":"1792652406","tags":{"is_exclusive":false}},
          {"id":"2345","name":"[Prize Giveaway] Take Part in the Travel Mimo Event to Win Polychromes","start":"1792555200","end":"1795967999",
           "desc":"New Profile Page Background Image is now available too!","game_id":8,"type":5,
           "app_path":"hoyolab://openURL?url=https%3A%2F%2Fact.hoyolab.com","web_path":"https://act.hoyolab.com/bbs/event/bbs-event-20230908mimo/index.html",
           "create_at":"1792562403"},
          {"id":"448","name":"5th Anniversary Fan Art Event","end":null,"desc":null,"type":1,"app_path":"hoyolab://events/448",
           "web_path":"/contribution/448","create_at":"1784181604"},
          {"id":"","name":"No id","desc":"x"}
        ],"next_offset":15,"is_last":false}
    """.trimIndent()

    @Test
    fun `an event feed is read, with links and null fields handled`() {
        val list = NewsApi.parse(JSONObject(feed))
        assertEquals(listOf("47163124", "2345", "448"), list.map { it.id })
        assertEquals("https://www.hoyolab.com/article/47163124", list[0].url)
        assertEquals(1793649600L, list[0].end)
        assertEquals(1792652406L, list[0].time)
        assertEquals(5, list[1].type)
        assertEquals("https://act.hoyolab.com/bbs/event/bbs-event-20230908mimo/index.html", list[1].url)
        assertEquals("", list[2].text)
        assertEquals(0L, list[2].end)
        assertEquals("https://www.hoyolab.com/contribution/448", list[2].url)
    }

    @Test
    fun `an event is kept under its game's feed and only when important`() {
        val list = NewsApi.parse(JSONObject(feed))
        val item = News.toItem(Game.GENSHIN, list[0])
        assertEquals("2:47163124", item?.id)
        assertEquals(Game.GENSHIN.id, item?.game)
        assertEquals(null, News.toItem(Game.ZZZ, list[1]))
        assertEquals("8:2345", News.keyOf(Game.ZZZ, "2345"))
    }

    @Test
    fun `at most 15 ids are remembered per game`() {
        val ids = (1..20).map { "2:$it" } + (1..3).map { "6:$it" }
        val kept = News.trimSeen(ids + listOf("2:1"))
        assertEquals(15, kept.count { it.startsWith("2:") })
        assertEquals(3, kept.count { it.startsWith("6:") })
        assertEquals("2:1", kept.first())
    }

    private val now = 1_800_000_000L
    private fun event(id: String, endInDays: Int, postedDaysAgo: Int = 5) =
        NewsItem(id, Game.GENSHIN.id, "t", "x", now - postedDaysAgo * 86_400L, now + endInDays * 86_400L, "")

    @Test
    fun `a running event stays, and an ended one stays 30 days`() {
        val list = listOf(
            event("running", endInDays = 3),
            event("ended 29 days ago", endInDays = -29),
            event("ended 31 days ago", endInDays = -31)
        )
        assertEquals(listOf("running", "ended 29 days ago"), NewsItem.kept(list, now).map { it.id })
    }

    @Test
    fun `a running event stays however long it has run`() {
        assertEquals(1, NewsItem.kept(listOf(event("long", endInDays = 200, postedDaysAgo = 90)), now).size)
    }

    @Test
    fun `an event posted long ago still shows when it ended recently`() {
        assertEquals(1, NewsItem.kept(listOf(event("welkin", endInDays = -21, postedDaysAgo = 34)), now).size)
    }

    @Test
    fun `an event without an end counts from when it was posted`() {
        val noEnd = NewsItem("a", Game.HSR.id, "t", "x", now - 10 * 86_400L, 0L, "")
        val old = noEnd.copy(id = "b", time = now - 40 * 86_400L)
        assertEquals(listOf("a"), NewsItem.kept(listOf(noEnd, old), now).map { it.id })
    }

    @Test
    fun `running events come first, newest first, then the ended ones`() {
        val list = listOf(
            event("ended new", endInDays = -1, postedDaysAgo = 2),
            event("running old", endInDays = 5, postedDaysAgo = 20),
            event("running new", endInDays = 5, postedDaysAgo = 1),
            event("ended old", endInDays = -10, postedDaysAgo = 30)
        )
        assertEquals(listOf("running new", "running old", "ended new", "ended old"), NewsItem.kept(list, now).map { it.id })
    }

    @Test
    fun `all games together keep at most 100, and an id counts once`() {
        val many = (1..130).map { event("e$it", endInDays = 3, postedDaysAgo = 1 + it % 20) }
        assertEquals(100, NewsItem.kept(many, now).size)
        assertEquals(1, NewsItem.kept(listOf(event("a", 3), event("a", 3)), now).size)
    }

    @Test
    fun `a saved event survives being stored`() {
        val item = NewsItem("8:1", Game.ZZZ.id, "Title", "Text", 99L, 150L, "https://www.hoyolab.com/article/1")
        assertEquals(listOf(item), NewsItem.listFromJson(NewsItem.listToJson(listOf(item))))
    }
}
