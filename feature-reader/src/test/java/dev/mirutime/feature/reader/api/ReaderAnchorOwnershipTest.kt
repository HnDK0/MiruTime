package dev.mirutime.feature.reader.api

import dev.mirutime.feature.tts.TtsStatus
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

    @Test
    fun `moving speech guards its chapter`() {
        assertTrue(ttsGuardsReaderAnchor(TtsStatus.Playing))
        assertTrue(ttsGuardsReaderAnchor(TtsStatus.Loading))
    }

    /** A paused cursor follows the reader; guarding it deadlocks the chapter. */
    @Test
    fun `paused session does not guard its chapter`() {
        assertFalse(ttsGuardsReaderAnchor(TtsStatus.Paused))
        assertFalse(ttsGuardsReaderAnchor(TtsStatus.Idle))
        assertFalse(ttsGuardsReaderAnchor(TtsStatus.Failed))
    }

    /**
     * The regression: a paused anchor from the next chapter must land, while a
     * playing one must not pull the session off the chapter it speaks.
     */
    @Test
    fun `guard follows the live status`() {
        assertTrue(
            shouldApplyReaderAnchor(
                ttsGuardsReaderAnchor(TtsStatus.Paused), false, "book#chapter=6", "book#chapter=7",
            ),
        )
        assertFalse(
            shouldApplyReaderAnchor(
                ttsGuardsReaderAnchor(TtsStatus.Playing), false, "book#chapter=6", "book#chapter=7",
            ),
        )
    }
}
