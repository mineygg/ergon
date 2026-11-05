package com.mineygg.ergon.core

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The live state of a run: what the Home screen shows and what the Stop button cancels. */
class RunBusTest {
    @Before
    fun reset() {
        RunBus.end()
    }

    @Test
    fun `nothing runs when no task has begun`() {
        assertNull(RunBus.active.value)
        assertFalse(RunBus.running.value)
        assertEquals("", RunBus.status.value)
        assertEquals(Live(), RunBus.live.value)
    }

    @Test
    fun `beginning a task shows it as running`() {
        RunBus.begin(Task.CHECKIN, "manual")
        assertEquals(Task.CHECKIN, RunBus.active.value)
        assertTrue(RunBus.running.value)
        assertEquals("Starting…", RunBus.status.value)
    }

    @Test
    fun `beginning a task writes a heading into the log`() {
        RunBus.begin(Task.REDEEM, "scheduled")
        val heading = RunBus.log.value.last()
        assertTrue(heading, heading.startsWith("──── Gift codes · scheduled · "))
        assertTrue(heading.endsWith(" ────"))
    }

    @Test
    fun `beginning a task clears what the last one showed`() {
        RunBus.liveCounts(3, 2, 1)
        RunBus.liveDetail("old")
        RunBus.begin(Task.WORKSHOP, "manual")
        assertEquals(Live(), RunBus.live.value)
    }

    @Test
    fun `ending a task clears everything that is live`() {
        RunBus.begin(Task.CHECKIN, "manual")
        RunBus.liveGame("genshin")
        RunBus.step("working")
        RunBus.end()
        assertNull(RunBus.active.value)
        assertFalse(RunBus.running.value)
        assertEquals("", RunBus.status.value)
        assertEquals(Live(), RunBus.live.value)
    }

    @Test
    fun `a log line starts with the time`() {
        RunBus.log("hello")
        assertTrue(RunBus.log.value.last(), Regex("""^\d{2}:\d{2}:\d{2}  hello$""").matches(RunBus.log.value.last()))
    }

    @Test
    fun `a step sets the status and is logged too`() {
        RunBus.step("Checking in…")
        assertEquals("Checking in…", RunBus.status.value)
        assertTrue(RunBus.log.value.last().endsWith("  Checking in…"))
    }

    @Test
    fun `the log keeps the newest 600 lines`() {
        repeat(650) { RunBus.log("line $it") }
        assertEquals(600, RunBus.log.value.size)
        assertTrue(RunBus.log.value.last().endsWith("line 649"))
        assertTrue(RunBus.log.value.first().endsWith("line 50"))
    }

    @Test
    fun `the live game, statuses, counts and detail are kept separately`() {
        RunBus.liveGame("hsr")
        RunBus.liveStatus("genshin", "SUCCESS")
        RunBus.liveStatus("hsr", "ALREADY")
        RunBus.liveCounts(claimed = 4, skipped = 2, failed = 1)
        RunBus.liveDetail("Trying CODE")
        val live = RunBus.live.value
        assertEquals("hsr", live.game)
        assertEquals(mapOf("genshin" to "SUCCESS", "hsr" to "ALREADY"), live.statuses)
        assertEquals(4, live.claimed)
        assertEquals(2, live.skipped)
        assertEquals(1, live.failed)
        assertEquals("Trying CODE", live.detail)
    }

    @Test
    fun `a status of a game is replaced by the next one`() {
        RunBus.liveStatus("genshin", "FAILED")
        RunBus.liveStatus("genshin", "SUCCESS")
        assertEquals(mapOf("genshin" to "SUCCESS"), RunBus.live.value.statuses)
    }

    @Test
    fun `the live game can be cleared`() {
        RunBus.liveGame("zzz")
        RunBus.liveGame(null)
        assertNull(RunBus.live.value.game)
    }

    @Test
    fun `stopping cancels the attached job`() {
        val job = Job()
        RunBus.attach(job)
        RunBus.stop()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `stopping with nothing attached does nothing`() {
        RunBus.stop()
        assertFalse(RunBus.running.value)
    }

    @Test
    fun `a finished task can no longer be stopped`() {
        val job = Job()
        RunBus.attach(job)
        RunBus.end()
        RunBus.stop()
        assertFalse(job.isCancelled)
    }

    @Test
    fun `the tasks have their own labels`() {
        assertEquals("Daily check-in", Task.CHECKIN.label)
        assertEquals("Gift codes", Task.REDEEM.label)
        assertEquals("Machine Workshop", Task.WORKSHOP.label)
    }

    @Test
    fun `a new Live has nothing in it`() {
        val l = Live()
        assertNull(l.game)
        assertTrue(l.statuses.isEmpty())
        assertEquals(0, l.claimed + l.skipped + l.failed)
        assertEquals("", l.detail)
    }
}
