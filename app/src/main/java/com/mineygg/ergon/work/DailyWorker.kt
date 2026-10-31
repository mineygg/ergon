package com.mineygg.ergon.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mineygg.ergon.ClaimApp
import com.mineygg.ergon.core.CheckinStatus
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.Runner
import com.mineygg.ergon.core.Task
import com.mineygg.ergon.core.Time
import com.mineygg.ergon.data.Settings
import com.mineygg.ergon.data.Store
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * What a worker does after its run:
 *  - done, or cut short by the connection (already saved as pending and handed to the catch-up worker): finished
 *  - another run held the lock, or an unexpected error: try again a few times, then hand it to the catch-up worker
 */
private fun resultFor(app: ClaimApp, runner: Runner, task: Task, attempt: Int, maxTries: Int): ListenableWorker.Result =
    when (runner.outcome) {
        Runner.Outcome.DONE, Runner.Outcome.DEFERRED -> ListenableWorker.Result.success()
        Runner.Outcome.BUSY, Runner.Outcome.ERROR ->
            if (attempt < maxTries) {
                ListenableWorker.Result.retry()
            } else {
                app.store.addPending(task.name)
                Scheduler.catchUp(app)
                ListenableWorker.Result.success()
            }
    }

/** Runs once a day, 30 minutes after HoYoLAB's reset. Skips games already claimed today. */
class CheckinWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClaimApp
        val runner = Runner(app, app.store)
        val report = runner.runCheckin("scheduled")
        if (runner.outcome == Runner.Outcome.DONE) {
            // The server answered with a real failure (not the connection): a few more tries, as before.
            val failed = report?.checkins?.values?.any { it == CheckinStatus.FAILED.name } == true
            return if (failed && runAttemptCount < 3) Result.retry() else Result.success()
        }
        // The workshop run starts at the same minute and may hold the lock: wait and try again, never give up silently.
        return resultFor(app, runner, Task.CHECKIN, runAttemptCount, 8)
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
        val runner = Runner(app, app.store)
        runner.runWorkshop("scheduled", force = false, finalCall = final)
        return resultFor(app, runner, Task.WORKSHOP, runAttemptCount, 4)
    }

    companion object {
        const val KEY_FINAL = "final"
    }
}

/** Runs hourly. Does nothing expensive unless a code page changed (full pass every 6 hours). */
class RedeemWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClaimApp
        val runner = Runner(app, app.store)
        runner.runRedeem("scheduled", force = false)
        // The hourly run doubles as a safety net: anything pending or missed (a check-in whose run was lost) is picked up here.
        Scheduler.catchUp(app)
        return resultFor(app, runner, Task.REDEEM, runAttemptCount, 2)
    }
}

/**
 * Finishes work that could not run or was cut short, as soon as there is internet (the network constraint holds it
 * back until then, it does not fail). It does what is saved as pending, plus a check-in that never got done today.
 * Everything it runs is state-tracked, so nothing is claimed twice.
 */
class CatchUpWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClaimApp
        val store = app.store
        var again = false
        for (task in CatchUp.tasks(store)) {
            val runner = Runner(app, store)
            when (task) {
                Task.CHECKIN -> runner.runCheckin("scheduled")
                Task.WORKSHOP -> runner.runWorkshop("scheduled", force = false)
                // Forced: a page that was already read would otherwise count as "unchanged" and its codes be skipped.
                Task.REDEEM -> runner.runRedeem("scheduled", force = true)
            }
            if (runner.outcome != Runner.Outcome.DONE) again = true
        }
        return if (again && runAttemptCount < 6) Result.retry() else Result.success()
    }
}

/** Decides what the catch-up worker has to do. */
object CatchUp {
    /** A check-in that is missing today is retried at most this often, and at most [MAX_DUE_TRIES] times a day. */
    private const val CHECKIN_RETRY_MS = 2L * 60 * 60 * 1000
    private const val MAX_DUE_TRIES = 4

    /** In the order they should run. */
    fun tasks(
        store: Store,
        now: ZonedDateTime = ZonedDateTime.now(Time.CST),
        nowMs: Long = System.currentTimeMillis()
    ): List<Task> {
        val pending = store.pending.value
        val out = ArrayList<Task>()
        if (Task.CHECKIN.name in pending || checkinDue(store, now, nowMs)) out.add(Task.CHECKIN)
        if (Task.WORKSHOP.name in pending) out.add(Task.WORKSHOP)
        if (Task.REDEEM.name in pending) out.add(Task.REDEEM)
        return out
    }

    /** Today's check-in is missing for a game that can be checked in, and enough time has passed since the last try. */
    private fun checkinDue(store: Store, now: ZonedDateTime, nowMs: Long): Boolean {
        val s = store.settings.value
        val cr = store.creds.value
        if (!s.autoRun || !cr.hasHoyolab || cr.hoyolabExpired) return false
        if (now.toLocalTime().isBefore(LocalTime.of(0, 30))) return false
        val missing = Game.entries.any { g ->
            g.id in s.checkinGames &&
                !(g.checkin.useCookieToken && (!cr.hasLogin || cr.loginExpired)) &&
                store.checkinToday(g.id) == null
        }
        if (!missing) return false
        if (store.checkinTriesToday >= MAX_DUE_TRIES) return false
        return nowMs - store.lastCheckinTry >= CHECKIN_RETRY_MS
    }
}

object Scheduler {
    private const val CATCH_UP = "catch_up"
    private const val CHECKIN = "checkin_daily"
    private const val REDEEM = "redeem_watch"
    private const val WORKSHOP_WATCH = "workshop_watch"
    private const val LEGACY = "daily_run"
    private const val LEGACY_WORKSHOP = "workshop_daily"

    /**
     * Makes sure unfinished work gets done as soon as there is internet. Does nothing if there is none, or if
     * automatic runs are off. Safe to call often: only one catch-up job exists at a time.
     */
    fun catchUp(ctx: Context) {
        val app = ctx.applicationContext as? ClaimApp ?: return
        if (!app.store.settings.value.autoRun) return
        if (CatchUp.tasks(app.store).isEmpty()) return
        val request = OneTimeWorkRequestBuilder<CatchUpWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, request)
    }

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
            wm.cancelUniqueWork(CATCH_UP)
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
