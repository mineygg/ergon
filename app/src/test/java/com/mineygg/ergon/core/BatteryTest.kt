package com.mineygg.ergon.core

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The battery setting that lets the scheduled runs happen on time. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real ClaimApp opens the Android Keystore in onCreate, which Robolectric doesn't have.
@Config(sdk = [34], application = Application::class)
class BatteryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the app is restricted until the user allows it`() {
        assertFalse(Battery.isUnrestricted(app))
    }

    @Test
    fun `the app is unrestricted once it is exempt from battery optimisation`() {
        val pm = app.getSystemService(PowerManager::class.java)
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, true)
        assertTrue(Battery.isUnrestricted(app))
    }

    @Test
    fun `asking opens the system prompt for this app`() {
        Battery.request(app)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
