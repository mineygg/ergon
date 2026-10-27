package com.mineygg.ergon.core

import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mineygg.ergon.data.Store
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONTokener

data class Fetched(val html: String?, val notModified: Boolean = false)

/**
 * Loads a Game8 page like a browser would:
 *  1. plain request with this device's real headers and the shared cookie jar (so a stored
 *     cf_clearance is reused), with If-None-Match / If-Modified-Since when [conditional];
 *  2. if Cloudflare blocks it, a hidden WebView solves the challenge. Its cookies land in the same
 *     jar, so the next plain request usually goes straight through.
 *
 * Note: OkHttp's TLS fingerprint isn't Chrome's, which is why the WebView fallback stays.
 */
object Game8Fetcher {

    private fun usable(html: String?): Boolean =
        html != null && html.length > 20_000 && !html.contains("Just a moment") && !html.contains("cf-challenge")

    suspend fun fetch(
        ctx: Context,
        store: Store,
        key: String,
        url: String,
        conditional: Boolean,
        log: (String) -> Unit
    ): Fetched {
        val plain = plain(store, key, url, conditional)
        if (plain.notModified || usable(plain.html)) return plain

        log("Direct request blocked — trying in-app browser…")
        val viaWeb = webView(ctx.applicationContext, url)
        if (usable(viaWeb)) return Fetched(viaWeb)
        return Fetched(null)
    }

    private suspend fun plain(store: Store, key: String, url: String, conditional: Boolean): Fetched =
        withContext(Dispatchers.IO) {
            try {
                val b = Request.Builder().url(url)
                DeviceHeaders.navigation().forEach { (k, v) -> b.header(k, v) }
                if (conditional) {
                    val (etag, lastMod) = store.validators(key)
                    etag?.let { b.header("If-None-Match", it) }
                    lastMod?.let { b.header("If-Modified-Since", it) }
                }
                Http.web.newCall(b.build()).execute().use { r ->
                    when {
                        r.code == 304 -> Fetched(null, notModified = true)
                        !r.isSuccessful -> Fetched(null)
                        else -> {
                            val body = r.body?.string()
                            if (usable(body)) store.setValidators(key, r.header("ETag"), r.header("Last-Modified"))
                            Fetched(body)
                        }
                    }
                }
            } catch (e: IOException) {
                Fetched(null)
            }
        }

    private suspend fun webView(ctx: Context, url: String): String? = withContext(Dispatchers.Main) {
        val wv = WebView(ctx)
        try {
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = DeviceHeaders.userAgent()
            DeviceHeaders.applyClientHints(wv)
            CookieManager.getInstance().setAcceptCookie(true)

            var finished = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    finished = true
                }
            }
            wv.loadUrl(url)

            val deadline = SystemClock.elapsedRealtime() + 45_000
            var html: String? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(1500)
                if (!finished) continue
                val raw = evalJs(wv, "document.documentElement.outerHTML")
                if (usable(raw)) {
                    html = raw
                    break
                }
            }
            CookieManager.getInstance().flush()
            html
        } finally {
            runCatching { wv.stopLoading() }
            runCatching { wv.destroy() }
        }
    }

    private suspend fun evalJs(wv: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { value ->
            val decoded = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
            cont.resume(decoded)
        }
    }
}
