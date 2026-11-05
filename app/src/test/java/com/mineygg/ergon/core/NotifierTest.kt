package com.mineygg.ergon.core

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mineygg.ergon.data.NewsItem
import com.mineygg.ergon.data.RedeemEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the notifications say, which channel they use, and that none is shown without the permission. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class NotifierTest {
    private lateinit var app: Application
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        nm = app.getSystemService(NotificationManager::class.java)
        Notifier.createChannels(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(id: Int): Notification = shadowOf(nm).getNotification(id)!!

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun Notification.bigText() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    private fun entry(game: String, code: String) = RedeemEntry(game, code, "", "CLAIMED", "Redeemed!")

    // ── Channels ────────────────────────────────────────────────────────────
    @Test
    fun `the four channels are created with their names`() {
        assertEquals("Claims", nm.getNotificationChannel("result")!!.name.toString())
        assertEquals("Session alerts", nm.getNotificationChannel("alert")!!.name.toString())
        assertEquals("Background claims", nm.getNotificationChannel("work")!!.name.toString())
        assertEquals("HoYoLAB news", nm.getNotificationChannel("news")!!.name.toString())
    }

    @Test
    fun `alerts are loud and background work is quiet`() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel("alert")!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel("work")!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel("result")!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel("news")!!.importance)
    }

    @Test
    fun `creating the channels twice is harmless`() {
        Notifier.createChannels(app)
        assertEquals(4, nm.notificationChannels.count { it.id in setOf("result", "alert", "work", "news") })
    }

    // ── Check-in and codes ──────────────────────────────────────────────────
    @Test
    fun `a claimed check-in names the games`() {
        Notifier.checkinClaimed(app, listOf("Genshin Impact", "Honkai: Star Rail"))
        val n = posted(1)
        assertEquals("Daily check-in claimed", n.title())
        assertEquals("Genshin Impact, Honkai: Star Rail", n.text())
        assertEquals("result", n.channelId)
    }

    @Test
    fun `one redeemed code is singular`() {
        Notifier.codesRedeemed(app, listOf(entry("genshin", "ABC123")))
        val n = posted(3)
        assertEquals("Redeemed 1 new code", n.title())
        assertEquals("Genshin Impact: ABC123", n.text())
    }

    @Test
    fun `several redeemed codes are plural and listed with their game`() {
        Notifier.codesRedeemed(app, listOf(entry("genshin", "ABC123"), entry("zzz", "XYZ789")))
        val n = posted(3)
        assertEquals("Redeemed 2 new codes", n.title())
        assertEquals("Genshin Impact: ABC123\nZenless Zone Zero: XYZ789", n.bigText())
    }

    @Test
    fun `the short text shows the first line only`() {
        Notifier.codesRedeemed(app, listOf(entry("genshin", "ABC123"), entry("zzz", "XYZ789")))
        assertEquals("Genshin Impact: ABC123", posted(3).text())
    }

    @Test
    fun `a game that is not known is listed by its id`() {
        Notifier.codesRedeemed(app, listOf(entry("newgame", "CODE1")))
        assertEquals("newgame: CODE1", posted(3).text())
    }

    // ── Workshop ────────────────────────────────────────────────────────────
    @Test
    fun `points gained are shown with the event and the total`() {
        Notifier.workshopPoints(app, WorkshopGame.GENSHIN, gained = 25, total = 100)
        val n = posted(10)
        assertEquals("Machine Workshop: +25 points", n.title())
        assertEquals("Genshin Impact: you now have 100 points.", n.text())
    }

    @Test
    fun `each game has its own notification so one does not replace another`() {
        Notifier.workshopPoints(app, WorkshopGame.GENSHIN, 1, 1)
        Notifier.workshopPoints(app, WorkshopGame.HSR, 1, 1)
        Notifier.workshopPoints(app, WorkshopGame.ZZZ, 1, 1)
        assertEquals(setOf(10, 12, 14), listOf(10, 12, 14).filter { shadowOf(nm).getNotification(it) != null }.toSet())
        assertEquals(3, shadowOf(nm).size())
    }

    @Test
    fun `exchanged rewards are counted and listed with their codes`() {
        Notifier.exchangeClaimed(app, WorkshopGame.HSR, names = listOf("Stellar Jade x40"), codes = listOf("ABCD1234"))
        val n = posted(13)
        assertEquals("Exchanged 1 reward", n.title())
        assertEquals("Honkai: Star Rail\nStellar Jade x40\nABCD1234", n.bigText())
    }

    @Test
    fun `several exchanged rewards are plural`() {
        Notifier.exchangeClaimed(app, WorkshopGame.GENSHIN, names = listOf("A", "B"), codes = emptyList())
        assertEquals("Exchanged 2 rewards", posted(11).title())
    }

    @Test
    fun `prize draws are counted per game`() {
        Notifier.prizeDraw(app, WorkshopGame.ZZZ, prizes = listOf("Polychrome x20", "Denny x5000", "Battery x2"), codes = emptyList())
        val n = posted(22)
        assertEquals("Prize draw: 3 draws", n.title())
        assertEquals("Zenless Zone Zero\nPolychrome x20\nDenny x5000\nBattery x2", n.bigText())
    }

    @Test
    fun `one prize draw is singular`() {
        Notifier.prizeDraw(app, WorkshopGame.HSR, prizes = listOf("Credit x10000"), codes = emptyList())
        assertEquals("Prize draw: 1 draw", posted(21).title())
    }

    // ── Alerts and the work notification ────────────────────────────────────
    @Test
    fun `an alert uses the loud channel`() {
        Notifier.alert(app, "Session expired", "Sign in again to keep claiming.")
        val n = posted(2)
        assertEquals("Session expired", n.title())
        assertEquals("Sign in again to keep claiming.", n.text())
        assertEquals("alert", n.channelId)
    }

    @Test
    fun `the work notification is ongoing and quiet`() {
        val n = Notifier.working(app)
        assertEquals("Claiming a reward", n.title())
        assertEquals("work", n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    // ── News ────────────────────────────────────────────────────────────────
    private fun newsId(item: NewsItem) = 1000 + (item.id.hashCode() and 0x7FFF)

    @Test
    fun `a news notification names the game and opens the article`() {
        val item = NewsItem("2:47163124", "genshin", "Giveaway time", "Win 500 Fates", 100L, 200L, "https://www.hoyolab.com/article/47163124")
        Notifier.news(app, item)
        val n = posted(newsId(item))
        assertEquals("Genshin Impact: Giveaway time", n.title())
        assertEquals("Win 500 Fates", n.text())
        assertEquals("news", n.channelId)
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertEquals("https://www.hoyolab.com/article/47163124", open.dataString)
    }

    @Test
    fun `a news notification without a link opens the app`() {
        val item = NewsItem("2:1", "hsr", "Web event", "Details", 100L, 200L, "")
        Notifier.news(app, item)
        val open = shadowOf(posted(newsId(item)).contentIntent).savedIntent
        assertEquals("com.mineygg.ergon.MainActivity", open.component!!.className)
    }

    @Test
    fun `a news notification without text shows its title instead`() {
        val item = NewsItem("2:2", "zzz", "Raffle", "", 100L, 200L, "")
        Notifier.news(app, item)
        assertEquals("Raffle", posted(newsId(item)).text())
    }

    @Test
    fun `two events get two notifications`() {
        val a = NewsItem("2:100", "genshin", "A", "", 1L, 2L, "")
        val b = NewsItem("2:101", "genshin", "B", "", 1L, 2L, "")
        Notifier.news(app, a)
        Notifier.news(app, b)
        assertNotNull(shadowOf(nm).getNotification(newsId(a)))
        assertNotNull(shadowOf(nm).getNotification(newsId(b)))
        assertEquals(2, shadowOf(nm).size())
    }

    // ── Permission ──────────────────────────────────────────────────────────
    @Test
    fun `nothing is shown without the notification permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier.checkinClaimed(app, listOf("Genshin Impact"))
        Notifier.alert(app, "t", "x")
        Notifier.news(app, NewsItem("2:3", "genshin", "t", "", 1L, 2L, ""))
        assertEquals(0, shadowOf(nm).size())
    }
}
