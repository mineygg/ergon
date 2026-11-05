package com.mineygg.ergon.core

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ergon presents itself as its own browser on Chromium and never as Google Chrome. */
class DeviceHeadersTest {
    private lateinit var saved: Locale

    @Before
    fun saveLocale() {
        saved = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(saved)
    }

    @Test
    fun `the user agent names Ergon as the browser`() {
        val ua = DeviceHeaders.userAgent()
        assertTrue(ua, Regex(""".* Ergon/\d+(\.\d+)*$""").matches(ua))
        assertEquals("Ergon", DeviceHeaders.BROWSER_NAME)
    }

    @Test
    fun `the user agent keeps the Chromium engine and does not look like a bare WebView`() {
        val ua = DeviceHeaders.userAgent()
        assertTrue(ua.startsWith("Mozilla/5.0 (Linux; Android"))
        assertTrue(ua.contains("Chrome/"))
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
    }

    @Test
    fun `a page navigation sends the same user agent`() {
        assertEquals(DeviceHeaders.userAgent(), DeviceHeaders.navigation()["User-Agent"])
    }

    @Test
    fun `the client hints list Chromium and Ergon but not Google Chrome`() {
        val hints = DeviceHeaders.navigation().getValue("sec-ch-ua")
        assertTrue(hints.contains("\"Chromium\""))
        assertTrue(hints.contains("\"Ergon\""))
        assertTrue(hints.contains("\"Not_A Brand\""))
        assertFalse(hints.contains("Google Chrome"))
    }

    @Test
    fun `the Chromium version in the hints is the one in the user agent`() {
        val major = Regex("""Chrome/(\d+)""").find(DeviceHeaders.userAgent())!!.groupValues[1]
        assertTrue(DeviceHeaders.navigation().getValue("sec-ch-ua").contains("\"Chromium\";v=\"$major\""))
    }

    @Test
    fun `a navigation looks like a phone opening a page`() {
        val h = DeviceHeaders.navigation()
        assertEquals("?1", h["sec-ch-ua-mobile"])
        assertEquals("\"Android\"", h["sec-ch-ua-platform"])
        assertEquals("navigate", h["Sec-Fetch-Mode"])
        assertEquals("document", h["Sec-Fetch-Dest"])
        assertEquals("none", h["Sec-Fetch-Site"])
        assertEquals("?1", h["Sec-Fetch-User"])
        assertEquals("1", h["Upgrade-Insecure-Requests"])
        assertTrue(h.getValue("Accept").startsWith("text/html"))
    }

    @Test
    fun `English speakers get English first`() {
        Locale.setDefault(Locale.US)
        assertEquals("en-US,en;q=0.9", DeviceHeaders.acceptLanguage())
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("en,en;q=0.9", DeviceHeaders.acceptLanguage())
    }

    @Test
    fun `other languages ask for their own, then English`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("de-DE,de;q=0.9,en;q=0.8", DeviceHeaders.acceptLanguage())
        Locale.setDefault(Locale.FRANCE)
        assertEquals("fr-FR,fr;q=0.9,en;q=0.8", DeviceHeaders.acceptLanguage())
    }

    @Test
    fun `the navigation carries the language of the device`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("de-DE,de;q=0.9,en;q=0.8", DeviceHeaders.navigation()["Accept-Language"])
    }
}
