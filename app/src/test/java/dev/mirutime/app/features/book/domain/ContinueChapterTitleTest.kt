package dev.mirutime.app.features.book.domain

import org.junit.Assert.assertEquals
import org.junit.Test

// Regression: with translation on, the Continue CTA used to prefer the raw
// source chapter title whenever a saved position existed, so the button read as
// untranslated while the chapter list showed translated names.
class ContinueChapterTitleTest {
    private fun chapter(index: Int, title: String) = BookChapter(
        index = index,
        title = title,
        isRead = false,
        downloaded = false,
        translated = false,
        downloading = false,
    )

    @Test
    fun `translated title wins over the saved raw title`() {
        val chapters = listOf(chapter(1, "第1章 墨画"), chapter(2, "第2章 重生"))

        val title = continueChapterTitle(
            savedIndex = 1,
            continueChapter = chapters[0],
            chapters = chapters,
            translatedTitles = mapOf(1 to "Глава 1. Живопись тушью."),
        )

        assertEquals("Глава 1. Живопись тушью.", title)
    }

    @Test
    fun `raw title is the fallback while metadata is missing`() {
        val chapters = listOf(chapter(1, "第1章 墨画"))

        val title = continueChapterTitle(
            savedIndex = 1,
            continueChapter = chapters[0],
            chapters = chapters,
            translatedTitles = emptyMap(),
        )

        assertEquals("第1章 墨画", title)
    }

    @Test
    fun `unsaved continue uses its own translated title`() {
        val chapters = listOf(chapter(1, "Chapter 1"), chapter(2, "Chapter 2"))

        val title = continueChapterTitle(
            savedIndex = null,
            continueChapter = chapters[1],
            chapters = chapters,
            translatedTitles = mapOf(2 to "Глава 2. Пробуждение"),
        )

        assertEquals("Глава 2. Пробуждение", title)
    }

    @Test
    fun `no chapter yields an empty label`() {
        assertEquals("", continueChapterTitle(null, null, emptyList(), emptyMap()))
    }
}
