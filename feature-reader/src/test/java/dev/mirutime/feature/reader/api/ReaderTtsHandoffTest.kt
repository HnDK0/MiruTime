package dev.mirutime.feature.reader.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTtsHandoffTest {
    @Test
    fun `handoff applies only to its target chapter`() {
        assertTrue(ttsHandoffApplies(activeChapterIndex = 3, target = 3))
        assertFalse(ttsHandoffApplies(activeChapterIndex = 3, target = 4))
    }
}
