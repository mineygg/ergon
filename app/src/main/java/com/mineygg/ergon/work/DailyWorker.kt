package com.mineygg.ergon.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mineygg.ergon.ClaimApp
import com.mineygg.ergon.core.CheckinStatus
import com.mineygg.ergon.core.Runner
import com.mineygg.ergon.core.Time
import com.mineygg.ergon.data.Settings
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Runs once a day, 30 minutes after HoYoLAB's reset. Skips games already claimed today. */
class CheckinWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClaimApp
        val report = Runner(app, app.store).runCheckin("scheduled")
        val failed = report?.checkins?.values?.any { it == CheckinStatus.FAILED.name } == true
        return if (failed && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

/**
 * Machine Workshop and Traveling Mimo runs (every enabled game, one after the other). Community missions reset at 00:00 UTC+8 and game missions at 04:00 server time:
 *  - after a reset (00:30 / 04:30): try every new task with the API
 *  - 15 min before a reset (23:45 / 03:45): mandatory claim of everything that is complete
 *  - every 6 hours: re-read the list and claim anything that became complete
 */
class WorkshopWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClaimApp
        val final = inputData.getBoolean(KEY_FINAL, false)
        Runner(app, app.store).runWorkshop("scheduled", force = false, finalCall = final)
        return Result.success()
    }

    companion object {
        const val KEY_FINAL = "final"
    }
}

/** Runs hourly. Does nothing expensive unless a code page changed (full pass every 6 hours). */
class RedeemWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClaimApp
        Runner(app, app.store).runRedeem("scheduled", force = false)
        return Result.success()
    }
}

object Scheduler {
    private const val CHECKIN = "checkin_daily"
    private const val REDEEM = "redeem_watch"
    private const val WORKSHOP_WATCH = "workshop_watch"
    private const val LEGACY = "daily_run"
    private const val LEGACY_WORKSHOP = "workshop_daily"

    /** Machine Workshop slots in UTC+8: name, hour, minute, final (mandatory pre-reset claim). */
    private data class Slot(val name: String, val hour: Int, val minute: Int, val final: Boolean)

    private val WORKSHOP_SLOTS = listOf(
        Slot("workshop_2345", 23, 45, true), // 15 min before the community reset (00:00)
        Slot("workshop_0030", 0, 30, false), // 30 min after the community reset
        Slot("workshop_0345", 3, 45, true), //  15 min before the game reset (04:00)
        Slot("workshop_0430", 4, 30, false) // 30 min after the game reset
    )

    /**
     * Fixed schedule (not user adjustable):
     *  - check-in: every 24h, first run at the next 00:30 UTC+8
     *  - Machine Workshop: 23:45, 00:30, 03:45 and 04:30 UTC+8, plus a re-check every 6 hours
     *  - codes: every hour
     * [replace] re-times existing jobs; false keeps them (use on app start).
     */
    fun schedule(ctx: Context, s: Settings, replace: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(LEGACY)
        wm.cancelUniqueWork(LEGACY_WORKSHOP)
        if (!s.autoRun) {
            wm.cancelUniqueWork(CHECKIN)
            wm.cancelUniqueWork(REDEEM)
            wm.cancelUniqueWork(WORKSHOP_WATCH)
            WORKSHOP_SLOTS.forEach { wm.cancelUniqueWork(it.name) }
            return
        }
        val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun untilCst(next: ZonedDateTime) = Duration.between(ZonedDateTime.now(Time.CST), next).toMillis()

        wm.enqueueUniquePeriodicWork(
            CHECKIN,
            policy,
            PeriodicWorkRequestBuilder<CheckinWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(untilCst(Time.nextCheckinAt()), TimeUnit.MILLISECONDS)
                .setConstraints(online)
                .build()
        )

        for (slot in WORKSHOP_SLOTS) {
            wm.enqueueUniquePeriodicWork(
                slot.name,
                policy,
                PeriodicWorkRequestBuilder<WorkshopWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(untilCst(Time.nextAt(slot.hour, slot.minute)), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(WorkshopWorker.KEY_FINAL to slot.final))
                    .setConstraints(online)
                    .build()
            )
        }
        wm.enqueueUniquePeriodicWork(
            WORKSHOP_WATCH,
            policy,
            PeriodicWorkRequestBuilder<WorkshopWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .setConstraints(online)
                .build()
        )

        wm.enqueueUniquePeriodicWork(
            REDEEM,
            policy,
            PeriodicWorkRequestBuilder<RedeemWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
        )
    }
}
