package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Split-mode TTS highlight must mark only the sentence the cursor is inside,
// not the whole pre-split paragraph (FR-027).
class ReaderHighlightScopeTest {

    @Test
    fun `a cursor inside the span is active`() {
        assertTrue(containsCursor(start = 10, end = 20, cursor = 10))
        assertTrue(containsCursor(start = 10, end = 20, cursor = 19))
    }

    @Test
    fun `a cursor outside the span is not active`() {
        assertFalse(containsCursor(start = 10, end = 20, cursor = 9))
        assertFalse(containsCursor(start = 10, end = 20, cursor = 20))
    }

    @Test
    fun `the last empty-guarded span still matches its own start`() {
        // An empty span (start == end) would otherwise never match.
        assertTrue(containsCursor(start = 20, end = 20, cursor = 20))
        assertFalse(containsCursor(start = 20, end = 20, cursor = 21))
    }
}
