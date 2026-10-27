package com.mineygg.ergon.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Time {
    /** HoYoLAB's daily reset happens at 00:00 UTC+8 for every game. */
    val CST: ZoneId = ZoneId.of("Asia/Shanghai")

    fun today(): String = LocalDate.now(CST).toString()

    fun isToday(epochMs: Long): Boolean =
        java.time.Instant.ofEpochMilli(epochMs).atZone(CST).toLocalDate().toString() == today()

    /** Key that changes when a task resets: 0 = once, 1 = daily, 2 = weekly (Monday-based, UTC+8). */
    fun periodKey(timeType: Int): String = when (timeType) {
        1 -> today()
        2 -> LocalDate.now(CST).with(java.time.DayOfWeek.MONDAY).toString()
        else -> "once"
    }

    /** Genshin's own daily/weekly reset is 04:00 server time (UTC+8 on the Asia server). */
    const val GAME_RESET_HOUR = 4

    /**
     * Key for a Machine Workshop task's current period. Per the event rules, community missions refresh
     * at 00:00 UTC+8 (weekly: Monday) and game missions (type 6) at 04:00 server time (weekly: Monday).
     */
    fun workshopPeriodKey(timeType: Int, taskType: Int, now: ZonedDateTime = ZonedDateTime.now(CST)): String {
        val d = (if (taskType == 6) now.minusHours(GAME_RESET_HOUR.toLong()) else now).toLocalDate()
        return when (timeType) {
            1 -> d.toString()
            2 -> d.with(java.time.DayOfWeek.MONDAY).toString()
            else -> "once"
        }
    }

    fun nextAt(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now(CST)): ZonedDateTime {
        var next = now.toLocalDate().atTime(hour, minute).atZone(CST)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    /** Check-in is fixed to 30 minutes after HoYoLAB's reset (00:30 UTC+8). */
    fun nextCheckinAt(now: ZonedDateTime = ZonedDateTime.now(CST)): ZonedDateTime = nextAt(0, 30, now)

    private fun localLabel(t: ZonedDateTime): String {
        val z = ZoneId.systemDefault()
        return t.withZoneSameInstant(z).format(DateTimeFormatter.ofPattern("HH:mm")) + " " +
            z.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    /** e.g. "22:00 IST" in the device's own time zone. */
    fun checkinLocalLabel(): String = localLabel(nextCheckinAt())

    /** Local label for a UTC+8 clock time, e.g. localLabelAt(23, 45) -> "21:15 IST". */
    fun localLabelAt(hour: Int, minute: Int): String = localLabel(nextAt(hour, minute))
}
