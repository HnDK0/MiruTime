package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaScrubberStateTest {
    @Test
    fun `drag seeks live and stays marked until finished`() {
        val state = MangaScrubberState()
        val seeks = mutableListOf<Int>()
        state.bind { seeks += it }

        state.seek(3)
        state.seek(4)

        assertEquals(listOf(3, 4), seeks)
        assertTrue(state.dragging)

        state.finish()
        assertFalse(state.dragging)
    }

    @Test
    fun `finish without a drag is harmless`() {
        val state = MangaScrubberState()
        state.finish()
        assertFalse(state.dragging)
    }
}
