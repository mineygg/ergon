package com.mineygg.ergon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How Ergon decides that a release is newer than the installed version. */
class UpdaterTest {
    @Test
    fun `a higher number in any place is newer`() {
        assertTrue(Updater.isNewer("1.3.1", "1.3.0"))
        assertTrue(Updater.isNewer("1.4.0", "1.3.9"))
        assertTrue(Updater.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `an older or equal version is not newer`() {
        assertFalse(Updater.isNewer("1.3.0", "1.3.1"))
        assertFalse(Updater.isNewer("1.3.0", "1.3.0"))
        assertFalse(Updater.isNewer("1.2.9", "1.3.0"))
    }

    @Test
    fun `versions are compared as numbers, not as text`() {
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertFalse(Updater.isNewer("1.9.9", "1.10.0"))
    }

    @Test
    fun `a leading v is ignored on either side`() {
        assertTrue(Updater.isNewer("v1.4.0", "1.3.0"))
        assertTrue(Updater.isNewer("1.4.0", "v1.3.0"))
        assertFalse(Updater.isNewer("v1.3.0", "1.3.0"))
    }

    @Test
    fun `a missing part counts as zero`() {
        assertFalse(Updater.isNewer("1.3", "1.3.0"))
        assertFalse(Updater.isNewer("1.3.0", "1.3"))
        assertTrue(Updater.isNewer("1.3.0.1", "1.3.0"))
        assertTrue(Updater.isNewer("2", "1.9.9"))
        assertFalse(Updater.isNewer("1.9.9", "2"))
    }

    @Test
    fun `a suffix on the same version does not make it newer`() {
        assertFalse(Updater.isNewer("1.3.0-beta", "1.3.0"))
        assertFalse(Updater.isNewer("1.3.0", "1.3.0-beta"))
        assertFalse(Updater.isNewer("1.3.0+5", "1.3.0"))
    }

    @Test
    fun `a newer version with a suffix is still newer`() {
        assertTrue(Updater.isNewer("1.3.1-beta", "1.3.0"))
    }

    @Test
    fun `text that is not a version counts as zero`() {
        assertFalse(Updater.isNewer("abc", "1.0"))
        assertTrue(Updater.isNewer("1.0", "abc"))
        assertFalse(Updater.isNewer("", "0"))
    }

    @Test
    fun `an update is not offered before one is looked for`() {
        assertEquals(UpdateState.Idle, Updater.state.value)
    }

    @Test
    fun `update info keeps what the release says`() {
        val info = UpdateInfo("1.4.0", "https://github.com/mineygg/ergon/releases/download/v1.4.0/ergon.apk", 1234L)
        assertEquals("1.4.0", info.version)
        assertEquals(1234L, info.size)
        assertEquals(UpdateState.Available(info), UpdateState.Available(info))
    }
}
