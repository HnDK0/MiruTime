package dev.mirutime.app.features.book.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterSizeFormatTest {

    @Test
    fun `unknown size stays empty so the row shows nothing`() {
        assertEquals("", formatChapterSize(0))
        assertEquals("", formatChapterSize(-1))
    }

    @Test
    fun `bytes boundary at 1023 and 1024`() {
        assertEquals("1023 B", formatChapterSize(1023))
        assertEquals("1.0 KB", formatChapterSize(1024))
    }

    @Test
    fun `kb keeps one decimal below ten and rounds above`() {
        assertEquals("11 KB", formatChapterSize(11 * 1024L))
        assertEquals("10 KB", formatChapterSize(10 * 1024L))
    }

    @Test
    fun `mb boundary and rounding`() {
        assertEquals("1.0 MB", formatChapterSize(1024L * 1024))
        assertEquals("1.2 MB", formatChapterSize(1_258_291))
        assertEquals("12 MB", formatChapterSize(12L * 1024 * 1024))
    }
}
