package com.mineygg.ergon.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mineygg.ergon.core.CheckinStatus
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.Task
import com.mineygg.ergon.core.Time
import com.mineygg.ergon.data.Store
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the catch-up job decides to run, and that the "waiting for internet" state is really saved. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class CatchUpTest {
    private lateinit var ctx: Context

    // The Android Keystore doesn't exist here, so the "secure" preferences are plain ones.
    private fun newStore() = Store(ctx, ctx.getSharedPreferences("test_secure", Context.MODE_PRIVATE))

    private fun cst(hour: Int, minute: Int): ZonedDateTime = ZonedDateTime.now(Time.CST).withHour(hour).withMinute(minute)

    private fun signedIn() = newStore().also { it.saveHoyolab("token", "1") }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `nothing to do on a fresh install`() {
        assertTrue(CatchUp.tasks(newStore(), cst(12, 0)).isEmpty())
    }

    @Test
    fun `pending tasks survive a restart and run in order`() {
        val first = newStore()
        first.addPending("REDEEM")
        first.addPending("CHECKIN")
        val second = newStore()
        assertEquals(setOf("REDEEM", "CHECKIN"), second.pending.value)
        assertEquals(listOf(Task.CHECKIN, Task.REDEEM), CatchUp.tasks(second, cst(12, 0)))
    }

    @Test
    fun `a finished task is no longer pending`() {
        val store = newStore()
        store.addPending("WORKSHOP")
        store.clearPending("WORKSHOP")
        assertTrue(newStore().pending.value.isEmpty())
    }

    @Test
    fun `a missing check-in is picked up after 00 30`() {
        assertEquals(listOf(Task.CHECKIN), CatchUp.tasks(signedIn(), cst(12, 0)))
    }

    @Test
    fun `no check-in catch-up before 00 30`() {
        assertTrue(CatchUp.tasks(signedIn(), cst(0, 10)).isEmpty())
    }

    @Test
    fun `no check-in catch-up when automatic runs are off`() {
        val store = signedIn()
        store.updateSettings { it.copy(autoRun = false) }
        assertTrue(CatchUp.tasks(store, cst(12, 0)).isEmpty())
    }

    @Test
    fun `no check-in catch-up when the session expired`() {
        val store = signedIn()
        store.setHealth(hoyolabExpired = true)
        assertTrue(CatchUp.tasks(store, cst(12, 0)).isEmpty())
    }

    @Test
    fun `no check-in catch-up once every game is done today`() {
        val store = signedIn()
        Game.entries.forEach { store.setCheckinToday(it.id, CheckinStatus.SUCCESS) }
        assertTrue(CatchUp.tasks(store, cst(12, 0)).isEmpty())
    }

    @Test
    fun `a failed check-in is retried only after two hours`() {
        val store = signedIn()
        store.noteCheckinTry()
        val now = System.currentTimeMillis()
        assertTrue(CatchUp.tasks(store, cst(12, 0), now + 60 * 60 * 1000L).isEmpty())
        assertEquals(listOf(Task.CHECKIN), CatchUp.tasks(store, cst(12, 0), now + 3 * 60 * 60 * 1000L))
    }

    @Test
    fun `a missing check-in is given up on after four tries a day`() {
        val store = signedIn()
        repeat(4) { store.noteCheckinTry() }
        assertTrue(CatchUp.tasks(store, cst(12, 0), System.currentTimeMillis() + 10 * 60 * 60 * 1000L).isEmpty())
    }
}