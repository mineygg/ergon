package com.mineygg.ergon

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The links and names shown in Settings. */
class AppInfoTest {
    private fun host(url: String) = URI(url).host

    @Test
    fun `the repository is on GitHub over https`() {
        assertTrue(AppInfo.REPO_URL.startsWith("https://github.com/"))
        assertEquals("github.com", host(AppInfo.REPO_URL))
        assertTrue(!AppInfo.REPO_URL.endsWith("/"))
    }

    @Test
    fun `the license is named`() {
        assertTrue(AppInfo.LICENSE_NAME.contains("GNU General Public License"))
    }

    @Test
    fun `the Keep Android Open links go to the campaign and the petition`() {
        assertEquals("https", URI(AppInfo.KEEP_ANDROID_OPEN_URL).scheme)
        assertEquals("keepandroidopen.org", host(AppInfo.KEEP_ANDROID_OPEN_URL))
        assertEquals("https", URI(AppInfo.KEEP_ANDROID_OPEN_PETITION_URL).scheme)
        assertEquals("www.change.org", host(AppInfo.KEEP_ANDROID_OPEN_PETITION_URL))
        assertTrue(AppInfo.KEEP_ANDROID_OPEN_PETITION_URL.contains("keep-android-open"))
    }
}
