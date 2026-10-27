package com.mineygg.ergon.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mineygg.ergon.AppInfo
import com.mineygg.ergon.data.Store
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(val version: String, val url: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Checks the project's latest GitHub release and installs its APK. */
object Updater {
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000
    private val API_URL =
        "https://api.github.com/repos/" + AppInfo.REPO_URL.removePrefix("https://github.com/") + "/releases/latest"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull().orEmpty().ifEmpty { "0" }

    /** Shows a previously found update right away (and forgets it once it has been installed). */
    fun restore(ctx: Context, store: Store) {
        val info = store.updateInfo ?: return
        if (isNewer(info.version, currentVersion(ctx))) {
            if (_state.value == UpdateState.Idle) _state.value = UpdateState.Available(info)
        } else {
            store.setUpdateInfo(null)
        }
    }

    suspend fun check(ctx: Context, store: Store, manual: Boolean) {
        when (_state.value) {
            is UpdateState.Checking, is UpdateState.Downloading -> return
            else -> {}
        }
        if (!manual && System.currentTimeMillis() - store.lastUpdateCheck < CHECK_EVERY_MS) return

        val before = _state.value
        _state.value = UpdateState.Checking
        val (info, error) = fetchLatest()
        if (error != null) {
            _state.value = if (manual) UpdateState.Error(error) else before
            return
        }
        store.setLastUpdateCheck(System.currentTimeMillis())
        if (info != null && isNewer(info.version, currentVersion(ctx))) {
            store.setUpdateInfo(info)
            _state.value = UpdateState.Available(info)
        } else {
            store.setUpdateInfo(null)
            _state.value = UpdateState.UpToDate
        }
    }

    /** Downloads the update (if not already downloaded) and opens the system installer. */
    suspend fun downloadAndInstall(ctx: Context) {
        val info = when (val st = _state.value) {
            is UpdateState.Available -> st.info
            is UpdateState.NeedsPermission -> st.info
            else -> return
        }
        val file = File(File(ctx.cacheDir, "updates"), "ergon-${info.version}.apk")
        if (!file.exists() || (info.size > 0 && file.length() != info.size)) {
            _state.value = UpdateState.Downloading(info, 0)
            val err = try {
                download(info, file)
            } catch (e: CancellationException) {
                file.delete()
                _state.value = UpdateState.Available(info)
                throw e
            }
            if (err != null) {
                file.delete()
                _state.value = UpdateState.Error(err)
                return
            }
        }
        install(ctx, info, file)
    }

    private fun install(ctx: Context, info: UpdateInfo, file: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        _state.value = UpdateState.Available(info)
    }

    /** Returns an error message, or null on success. */
    private suspend fun download(info: UpdateInfo, file: File): String? = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            file.parentFile?.listFiles()?.forEach { it.delete() }
            val req = Request.Builder().url(info.url).header("User-Agent", "Ergon").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use "Download failed (HTTP ${resp.code})"
                val body = resp.body ?: return@use "Empty download"
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                file.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            ensureActive()
                            if (total > 0) {
                                val p = (read * 100 / total).toInt().coerceIn(0, 100)
                                if (p != last) {
                                    last = p
                                    _state.value = UpdateState.Downloading(info, p)
                                }
                            }
                        }
                    }
                }
                null
            }
        } catch (e: IOException) {
            e.message ?: "Network error"
        }
    }

    /** Latest release as (info, error). No release yet counts as "nothing newer", not an error. */
    private suspend fun fetchLatest(): Pair<UpdateInfo?, String?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Ergon")
                .build()
            Http.client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@use null to null
                if (!resp.isSuccessful) return@use null to "Update check failed (HTTP ${resp.code})"
                val json = JSONObject(resp.body?.string().orEmpty())
                val version = json.optString("tag_name").removePrefix("v").trim()
                val assets = json.optJSONArray("assets")
                var url: String? = null
                var size = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size")
                            break
                        }
                    }
                }
                if (version.isEmpty() || url.isNullOrEmpty()) return@use null to null
                if (!isTrustedHost(url)) return@use null to "Unexpected download address"
                UpdateInfo(version, url, size) to null
            }
        } catch (e: IOException) {
            null to (e.message ?: "Network error")
        } catch (e: Exception) {
            null to "Couldn't read the update info"
        }
    }

    private fun isTrustedHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url) }.getOrNull()?.takeIf { it.scheme == "https" }?.host ?: return false
        return host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
    }

    /** True if [a] is a higher version than [b] (numeric, dot separated; a leading "v" is ignored). */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
