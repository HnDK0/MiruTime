package dev.mirutime.app.features.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards the tag extraction: BookScreen must use the shared tag primitives and
// the interactive tag must keep a 48dp touch slot.
class BookTagKitContractTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("source not found: $relative")

    @Test
    fun `book screen uses the shared tags`() {
        val book = source("src/main/java/dev/mirutime/app/features/book/ui/BookScreen.kt")
        assertFalse(book.contains("private fun GenreTag("))
        assertFalse(book.contains("private fun ShelfGenreFlow("))
        assertTrue(book.contains("MiruTag("))
        assertTrue(book.contains("MiruSourceChip("))
    }

    @Test
    fun `interactive tag keeps a dense-layout touch slot`() {
        val tag = source("ui-kit/src/main/java/dev/mirutime/uikit/MiruTag.kt")
        assertTrue(tag.contains("heightIn(min = if (onClick != null)"))
        assertTrue(tag.contains("MiruSize.RowDense else MiruSize.Chip"))
    }
}
