package com.mineygg.ergon.core

import android.webkit.CookieManager
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

object Http {
    /** Only used by the login screen's optional "Desktop" browser mode. */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"

    /** HoYo API calls: no cookie jar, cookies are set by hand per request. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Scraping: shares cookies (including Cloudflare's cf_clearance) with the WebView. */
    val web: OkHttpClient by lazy { client.newBuilder().cookieJar(WebCookieJar).build() }
}

/**
 * Bridges OkHttp to the WebView's cookie store, so a Cloudflare clearance earned once by the hidden
 * browser is automatically reused by plain requests (and vice versa), like a real browser would.
 */
object WebCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
        cm.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
