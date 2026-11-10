package dev.mirutime.app.features.local.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPageNamesTest {
    @Test
    fun `natural sort orders numbers, not text`() {
        assertEquals("vol 000000000002", naturalSortKey("vol 2"))
        assertTrue(naturalCompare("vol 2", "vol 10") < 0)
        assertTrue(naturalCompare("ch1", "ch10") < 0)
    }

    @Test
    fun `page names reject unsupported formats and cache artifacts`() {
        assertFalse(isPageImageName("001.avif"))
        assertTrue(isPageImageName("001.JPG"))
        assertFalse(isPageImageName("thumb.jpg"))
        assertFalse(isPageImageName("._001.jpg"))
        assertFalse(isPageImageName("cover.txt"))
    }
}
