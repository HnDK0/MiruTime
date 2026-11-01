package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceLabelTest {
    @Test
    fun `update label is empty at zero and accent plus N above`() {
        assertEquals("", newChaptersLabel(0))
        assertEquals("", newChaptersLabel(-1))
        assertEquals("+3", newChaptersLabel(3))
    }
}
