package com.mineygg.ergon.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mineygg.ergon.ClaimApp
import com.mineygg.ergon.core.Notifier
import com.mineygg.ergon.core.Runner
import com.mineygg.ergon.core.WorkshopGame
import com.mineygg.ergon.data.Store
import kotlinx.coroutines.delay

/**
 * One alarm per game, set a few seconds before a timed exchange reward refreshes. Its stock is first come, first
 * served, so it can't wait for the next scheduled run. The alarm is only set while auto exchange is waiting for one.
 */
object RefreshAlarm {
    const val ACTION = "com.mineygg.ergon.REFRESH_EXCHANGE"
    const val EXTRA_GAME = "game"

    private fun pending(ctx: Context, wg: WorkshopGame, create: Boolean): PendingIntent? {
        val intent = Intent(ctx, RefreshReceiver::class.java).setAction(ACTION).putExtra(EXTRA_GAME, wg.id)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, wg.ordinal, intent, flags)
    }

    fun schedule(ctx: Context, store: Store, wg: WorkshopGame, atMs: Long) {
        store.setRefreshAt(wg, atMs)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, wg, create = true) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    fun cancel(ctx: Context, store: Store, wg: WorkshopGame) {
        if (store.refreshAt(wg) != 0L) store.setRefreshAt(wg, 0L)
        val pi = pending(ctx, wg, create = false) ?: return
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    /** A reboot clears all alarms: set them again from the saved times (a time already past fires at once). */
    fun restore(ctx: Context, store: Store) {
        for (wg in WorkshopGame.entries) {
            val at = store.refreshAt(wg)
            if (at > 0L) schedule(ctx, store, wg, maxOf(at, System.currentTimeMillis() + 1_000))
        }
    }
}

class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ClaimApp
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                RefreshAlarm.restore(context, app.store)
                // Work that was waiting for the internet before the restart.
                Scheduler.catchUp(context)
            }
            RefreshAlarm.ACTION -> intent.getStringExtra(RefreshAlarm.EXTRA_GAME)?.let { RefreshWorker.enqueue(context, it) }
        }
    }
}

/** Runs when a timed reward refreshes: reads the exchange and claims what is available. Started as expedited work. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ClaimApp
        val wg = WorkshopGame.entries.firstOrNull { it.id == inputData.getString(KEY_GAME) } ?: return Result.success()
        val runner = Runner(app, app.store)
        // Another run may be holding the lock for a few seconds. This one must not be dropped.
        repeat(MAX_TRIES) {
            if (runner.runTimedExchange(wg) != null) return Result.success()
            // No connection: the run is saved as pending and the catch-up worker claims what is there once online.
            if (runner.outcome == Runner.Outcome.DEFERRED) return Result.success()
            delay(3_000)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(NOTIFICATION_ID, Notifier.working(applicationContext))

    companion object {
        const val KEY_GAME = "game"
        private const val MAX_TRIES = 6
        private const val NOTIFICATION_ID = 99

        fun enqueue(ctx: Context, gameId: String) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(workDataOf(KEY_GAME to gameId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("refresh_$gameId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
