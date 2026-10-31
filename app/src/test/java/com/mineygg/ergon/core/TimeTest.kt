package com.mineygg.ergon.core

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTest {
    private fun cst(s: String) = ZonedDateTime.parse("$s+08:00[Asia/Shanghai]")

    @Test
    fun `game tasks reset at 04 00, community tasks at midnight`() {
        val now = cst("2026-10-31T03:00:00")
        assertEquals("2026-10-30", Time.workshopPeriodKey(1, gameTask = true, now = now))
        assertEquals("2026-10-31", Time.workshopPeriodKey(1, gameTask = false, now = now))
    }

    @Test
    fun `weekly tasks key on the Monday of the week`() {
        // 2026-10-31 is a Saturday.
        assertEquals("2026-10-26", Time.workshopPeriodKey(2, gameTask = false, now = cst("2026-10-31T12:00:00")))
    }

    @Test
    fun `one-off tasks never change period`() {
        assertEquals("once", Time.workshopPeriodKey(0, gameTask = false, now = cst("2026-10-31T12:00:00")))
    }

    @Test
    fun `next check-in is 00 30 today before it, tomorrow after it`() {
        assertEquals(cst("2026-10-31T00:30:00"), Time.nextCheckinAt(cst("2026-10-31T00:10:00")))
        assertEquals(cst("2026-11-01T00:30:00"), Time.nextCheckinAt(cst("2026-10-31T00:40:00")))
    }
}
