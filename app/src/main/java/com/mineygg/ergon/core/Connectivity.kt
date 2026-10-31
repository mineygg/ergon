package com.mineygg.ergon.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thrown inside a run when the connection is gone and did not come back in time. The run is resumed later. */
class OfflineException : Exception("No internet connection")

/**
 * Internet state for the whole app.
 *
 * [online] is a live flow (for the UI and for "back online" triggers). [isOnline] asks Android directly, so it is
 * right even if the callback has not fired yet. "Online" means the active network has internet capability; the
 * system's own "validated" probe is deliberately not required, because it fails on some networks that work fine.
 * Real request failures are still handled by the runner, so a captive portal or dead link is not missed either.
 */
object Connectivity {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var started = false

    /** Safe to call as often as you like; registers the network callback once per process. */
    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        started = true
        _online.value = query(cm)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    _online.value = query(cm)
                }

                override fun onLost(network: android.net.Network) {
                    _online.value = query(cm)
                }

                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                    _online.value = query(cm)
                }
            })
        }
    }

    private fun query(cm: ConnectivityManager): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching { query(cm) }.getOrDefault(true)
    }

    /**
     * Waits until the device is online, at most [timeoutMs] (0 = just check). Returns whether it is online.
     * After a reconnect it waits a moment so DNS and the radio settle before the first request.
     */
    suspend fun awaitOnline(ctx: Context, timeoutMs: Long): Boolean {
        if (isOnline(ctx)) return true
        if (timeoutMs <= 0L) return false
        val end = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < end) {
            delay(1_000)
            if (isOnline(ctx)) {
                delay(2_000)
                return isOnline(ctx)
            }
        }
        return false
    }
}
