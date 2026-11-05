package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWindowTest {
    @Test
    fun `bars hide only with fullscreen and no chrome`() {
        assertTrue(shouldHideSystemBars(fullscreen = true, chromeVisible = false, panelOpen = false))
        assertFalse(shouldHideSystemBars(fullscreen = true, chromeVisible = true, panelOpen = false))
        assertFalse(shouldHideSystemBars(fullscreen = true, chromeVisible = false, panelOpen = true))
        assertFalse(shouldHideSystemBars(fullscreen = false, chromeVisible = false, panelOpen = false))
    }

    @Test
    fun `reader exit always restores the bars`() {
        // The old onDispose branch re-hid the bars when they were hidden on
        // entry; the shell then measured zero insets after the pop.
        assertFalse(readerExitKeepsSystemBarsHidden())
        // Toggling fullscreen off must show them again, not keep the mode.
        assertFalse(shouldHideSystemBars(fullscreen = false, chromeVisible = false, panelOpen = false))
    }

    @Test
    fun `back closes a sheet but leaves the reader from the menu`() {
        assertTrue(readerBackClosesOverlay(panelOpen = true))
        // The menu overlay is not modal: back exits instead of hiding it.
        assertFalse(readerBackClosesOverlay(panelOpen = false))
    }

    @Test
    fun `fullscreen padding ignores safe insets and stays stable`() {
        assertEquals(8f, readerTopPaddingDp(fullscreen = true, safeTopDp = 28f), 0.001f)
        assertEquals(24f, readerBottomPaddingDp(fullscreen = true, safeBottomDp = 32f), 0.001f)
        assertEquals(36f, readerTopPaddingDp(fullscreen = false, safeTopDp = 28f), 0.001f)
        assertEquals(56f, readerBottomPaddingDp(fullscreen = false, safeBottomDp = 32f), 0.001f)
    }
}
