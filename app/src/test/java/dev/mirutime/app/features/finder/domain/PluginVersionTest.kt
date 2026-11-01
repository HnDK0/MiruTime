package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginVersionTest {

    @Test
    fun `newer numeric versions are updates`() {
        assertTrue(isNewerVersion("1.15.0", "1.14.3"))
        assertTrue(isNewerVersion("1.0.10", "1.0.9"))
        assertTrue(isNewerVersion("2", "1.9"))
        assertTrue(isNewerVersion("v1.2.0", "1.1.9"))
    }

    @Test
    fun `older and equal versions are not updates`() {
        assertFalse(isNewerVersion("1.14.3", "1.15.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("2.0", "2.0.0"))
    }

    @Test
    fun `suffixes and missing segments are ignored`() {
        assertFalse(isNewerVersion("1.0.0", "1.0.0-beta"))
        assertTrue(isNewerVersion("1.2.1+build", "1.2"))
    }

    @Test
    fun `unparseable or missing versions are never updates`() {
        assertFalse(isNewerVersion(null, "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", null))
        assertFalse(isNewerVersion("", "1.0.0"))
        assertFalse(isNewerVersion("latest", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "beta"))
    }
}
