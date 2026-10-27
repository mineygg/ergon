package com.mineygg.ergon.core

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

/**
 * Builds request headers from this device's real WebView (Chrome) instead of pretending to be a
 * Windows browser. Cloudflare binds its clearance cookie to the User-Agent, so the same UA is used
 * by the WebView, OkHttp and the HoYo API calls.
 */
object DeviceHeaders {
    private const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var ua: String = FALLBACK

    /** Call once from Application.onCreate (main thread). */
    fun init(ctx: Context) {
        ua = runCatching {
            // WebView adds "; wv" and "Version/4.0"; real Chrome doesn't.
            WebSettings.getDefaultUserAgent(ctx).replace("; wv", "").replace("Version/4.0 ", "")
        }.getOrDefault(FALLBACK)
    }

    fun userAgent(): String = ua

    private fun chromeMajor(): String = Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1) ?: "130"

    fun acceptLanguage(): String {
        val l = Locale.getDefault()
        val tag = l.toLanguageTag()
        return if (l.language == "en") "$tag,en;q=0.9" else "$tag,${l.language};q=0.9,en;q=0.8"
    }

    /** Headers a Chrome-on-Android top-level page navigation would send. */
    fun navigation(): Map<String, String> {
        val v = chromeMajor()
        return linkedMapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to acceptLanguage(),
            "Upgrade-Insecure-Requests" to "1",
            "sec-ch-ua" to "\"Chromium\";v=\"$v\", \"Google Chrome\";v=\"$v\", \"Not_A Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-User" to "?1",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun fullChromeVersion(): String = Regex("""Chrome/([\d.]+)""").find(ua)?.groupValues?.get(1) ?: "130.0.0.0"

    /**
     * A plain WebView reports itself as "Android WebView" in the client-hint brands (Sec-CH-UA and
     * navigator.userAgentData) even when the User-Agent string says Chrome. Make them agree, so
     * the browser identity is consistent everywhere a page can look.
     */
    fun applyClientHints(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        runCatching {
            val major = chromeMajor()
            val full = fullChromeVersion()
            fun brand(name: String, maj: String, ver: String) =
                UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(maj).setFullVersion(ver).build()
            val meta = UserAgentMetadata.Builder()
                .setBrandVersionList(
                    listOf(
                        brand("Chromium", major, full),
                        brand("Google Chrome", major, full),
                        brand("Not_A Brand", "24", "24.0.0.0")
                    )
                )
                .setFullVersion(full)
                .setPlatform("Android")
                .setPlatformVersion(Build.VERSION.RELEASE)
                .setModel(Build.MODEL)
                .setMobile(true)
                .build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, meta)
        }
    }
}
