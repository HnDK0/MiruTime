package dev.mirutime.feature.reader.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAnchorOwnershipTest {
    @Test
    fun `idle reader applies every anchor`() {
        assertTrue(shouldApplyReaderAnchor(false, false, null, "book#chapter=1"))
        assertTrue(shouldApplyReaderAnchor(false, false, "book#chapter=2", "book#chapter=1"))
    }

    @Test
    fun `tts keeps the chapter it speaks`() {
        assertTrue(shouldApplyReaderAnchor(true, false, "book#chapter=2", "book#chapter=2"))
        assertFalse(shouldApplyReaderAnchor(true, false, "book#chapter=2", "book#chapter=1"))
    }

    @Test
    fun `handoff ignores stale feed anchors`() {
        assertFalse(shouldApplyReaderAnchor(true, true, "book#chapter=2", "book#chapter=1"))
        assertFalse(shouldApplyReaderAnchor(true, true, null, "book#chapter=1"))
    }

    @Test
    fun `missing cursor never blocks the feed`() {
        assertTrue(shouldApplyReaderAnchor(true, false, null, "book#chapter=1"))
    }
}
