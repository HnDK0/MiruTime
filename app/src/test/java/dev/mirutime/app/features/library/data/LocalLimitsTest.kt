package dev.mirutime.app.features.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Snapshot of the format caps: changing a number must be a deliberate edit here
// (and in the README) instead of a silent regression.
class LocalLimitsTest {

    private fun mb(value: Long): Long = value * 1024 * 1024

    @Test
    fun `format caps match the documented values`() {
        assertEquals(mb(64), LocalLimits.FB2)
        assertEquals(mb(128), LocalLimits.TXT)
        assertEquals(mb(128), LocalLimits.EPUB)
        assertEquals(mb(512), LocalLimits.CBZ)
        assertEquals(mb(512), LocalLimits.PDF)
    }

    @Test
    fun `every format resolves to its cap`() {
        assertEquals(LocalLimits.FB2, importLimit(LocalImportFormat.FB2))
        assertEquals(LocalLimits.TXT, importLimit(LocalImportFormat.TXT))
        assertEquals(LocalLimits.EPUB, importLimit(LocalImportFormat.EPUB))
        assertEquals(LocalLimits.CBZ, importLimit(LocalImportFormat.CBZ))
        assertEquals(LocalLimits.PDF, importLimit(LocalImportFormat.PDF))
        LocalImportFormat.entries.forEach { assertTrue(importLimit(it) > 0) }
    }
}
