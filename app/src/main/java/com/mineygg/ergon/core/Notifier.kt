package com.mineygg.ergon.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mineygg.ergon.MainActivity
import com.mineygg.ergon.R
import com.mineygg.ergon.data.RedeemEntry

object Notifier {
    private const val CH_RESULT = "result"
    private const val CH_ALERT = "alert"
    private const val CH_WORK = "work"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "Claims", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Session alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Background claims", NotificationManager.IMPORTANCE_LOW))
    }

    fun checkinClaimed(ctx: Context, games: List<String>) =
        post(ctx, 1, CH_RESULT, "Daily check-in claimed", games.joinToString(", "))

    fun codesRedeemed(ctx: Context, entries: List<RedeemEntry>) {
        val lines = entries.joinToString("\n") { e ->
            val g = Game.entries.firstOrNull { it.id == e.game }?.title ?: e.game
            "$g: ${e.code}"
        }
        val n = entries.size
        post(ctx, 3, CH_RESULT, "Redeemed $n new code${if (n == 1) "" else "s"}", lines)
    }

    // Each game gets its own notification ids so one doesn't replace the other.
    fun workshopPoints(ctx: Context, wg: WorkshopGame, gained: Int, total: Int) =
        post(ctx, 10 + wg.ordinal * 2, CH_RESULT, "${wg.eventName}: +$gained points", "${wg.game.title}: you now have $total points.")

    fun exchangeClaimed(ctx: Context, wg: WorkshopGame, names: List<String>, codes: List<String>) {
        val n = names.size
        val text = (listOf(wg.game.title) + names + codes).joinToString("\n")
        post(ctx, 11 + wg.ordinal * 2, CH_RESULT, "Exchanged $n reward${if (n == 1) "" else "s"}", text)
    }

    fun prizeDraw(ctx: Context, wg: WorkshopGame, prizes: List<String>, codes: List<String>) {
        val n = prizes.size
        val text = (listOf(wg.game.title) + prizes + codes).joinToString("\n")
        post(ctx, 20 + wg.ordinal, CH_RESULT, "Prize draw: $n draw${if (n == 1) "" else "s"}", text)
    }

    /** Only shown on Android 11 and older, which needs one while an alarm-started claim runs. */
    fun working(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Claiming a reward")
            .setOngoing(true)
            .build()

    fun alert(ctx: Context, title: String, text: String) = post(ctx, 2, CH_ALERT, title, text)

    private fun post(ctx: Context, id: Int, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
