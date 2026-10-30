package com.mineygg.ergon.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimisation can delay or skip scheduled background work while the app is closed.
 * "Unrestricted" (exempt from battery optimisation) lets the scheduled runs happen on time.
 */
object Battery {
    fun isUnrestricted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Opens the system prompt to allow it, or the app's settings page if the device has no such prompt. */
    fun request(ctx: Context) {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            runCatching { ctx.startActivity(fallback) }
        } catch (_: SecurityException) {
            runCatching { ctx.startActivity(fallback) }
        }
    }
}
