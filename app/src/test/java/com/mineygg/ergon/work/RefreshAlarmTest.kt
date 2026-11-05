package com.mineygg.ergon.work

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mineygg.ergon.core.WorkshopGame
import com.mineygg.ergon.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The alarm that wakes Ergon when a timed reward refreshes, and that it comes back after a reboot. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class RefreshAlarmTest {
    private lateinit var ctx: Context
    private lateinit var store: Store
    private lateinit var am: AlarmManager

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // The Android Keystore doesn't exist here, so the "secure" preferences are plain ones.
        store = Store(ctx, ctx.getSharedPreferences("test_secure", Context.MODE_PRIVATE))
        am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private fun alarms() = shadowOf(am).scheduledAlarms

    private fun pendingFor(wg: WorkshopGame): PendingIntent? {
        val intent = Intent(ctx, RefreshReceiver::class.java).setAction(RefreshAlarm.ACTION).putExtra(RefreshAlarm.EXTRA_GAME, wg.id)
        return PendingIntent.getBroadcast(ctx, wg.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
    }

    private val farAway: Long get() = System.currentTimeMillis() + 3_600_000L

    @Test
    fun `an alarm is set for the time given and the time is saved`() {
        val at = farAway
        RefreshAlarm.schedule(ctx, store, WorkshopGame.HSR, at)
        assertEquals(at, store.refreshAt(WorkshopGame.HSR))
        assertEquals(1, alarms().size)
        assertEquals(at, alarms().single().getTriggerAtMs())
    }

    @Test
    fun `the alarm tells which game refreshed`() {
        RefreshAlarm.schedule(ctx, store, WorkshopGame.ZZZ, farAway)
        // Look up the pending intent the alarm was given: it only exists for a game that has an alarm.
        val operation = pendingFor(WorkshopGame.ZZZ)
        assertNotNull(operation)
        assertNull(pendingFor(WorkshopGame.HSR))
        val intent = shadowOf(operation).savedIntent
        assertEquals(RefreshAlarm.ACTION, intent.action)
        assertEquals("zzz", intent.getStringExtra(RefreshAlarm.EXTRA_GAME))
    }

    @Test
    fun `each game has its own alarm`() {
        RefreshAlarm.schedule(ctx, store, WorkshopGame.GENSHIN, farAway)
        RefreshAlarm.schedule(ctx, store, WorkshopGame.HSR, farAway + 1_000L)
        assertEquals(2, alarms().size)
    }

    @Test
    fun `cancelling removes the alarm and the saved time`() {
        RefreshAlarm.schedule(ctx, store, WorkshopGame.HSR, farAway)
        RefreshAlarm.cancel(ctx, store, WorkshopGame.HSR)
        assertEquals(0L, store.refreshAt(WorkshopGame.HSR))
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun `cancelling a game that has no alarm does nothing`() {
        RefreshAlarm.cancel(ctx, store, WorkshopGame.GENSHIN)
        assertEquals(0L, store.refreshAt(WorkshopGame.GENSHIN))
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun `cancelling one game leaves the alarm of another`() {
        RefreshAlarm.schedule(ctx, store, WorkshopGame.GENSHIN, farAway)
        RefreshAlarm.schedule(ctx, store, WorkshopGame.HSR, farAway)
        RefreshAlarm.cancel(ctx, store, WorkshopGame.GENSHIN)
        assertEquals(1, alarms().size)
        assertEquals(0L, store.refreshAt(WorkshopGame.GENSHIN))
        assertTrue(store.refreshAt(WorkshopGame.HSR) > 0L)
    }

    @Test
    fun `after a reboot the saved alarms are set again`() {
        val at = farAway
        store.setRefreshAt(WorkshopGame.GENSHIN, at)
        store.setRefreshAt(WorkshopGame.ZZZ, at + 5_000L)
        RefreshAlarm.restore(ctx, store)
        assertEquals(2, alarms().size)
        assertEquals(setOf(at, at + 5_000L), alarms().map { it.getTriggerAtMs() }.toSet())
    }

    @Test
    fun `a time that already passed fires almost at once`() {
        val before = System.currentTimeMillis()
        store.setRefreshAt(WorkshopGame.HSR, 1L)
        RefreshAlarm.restore(ctx, store)
        val at = alarms().single().getTriggerAtMs()
        assertTrue(at >= before + 1_000L)
        assertTrue(at <= System.currentTimeMillis() + 5_000L)
        assertEquals(at, store.refreshAt(WorkshopGame.HSR))
    }

    @Test
    fun `nothing is set again when nothing was saved`() {
        RefreshAlarm.restore(ctx, store)
        assertTrue(alarms().isEmpty())
    }
}
