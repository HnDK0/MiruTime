package dev.mirutime.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class StableKeysTest {
    @Test
    fun `reader block keys stay stable`() {
        assertEquals("block:3", readerBlockKey(3))
    }
}
