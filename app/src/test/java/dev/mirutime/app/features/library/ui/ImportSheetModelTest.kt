package dev.mirutime.app.features.library.ui

import dev.mirutime.app.features.library.data.ImportSummary
import dev.mirutime.app.features.library.data.PickedImportFile
import org.junit.Assert.assertEquals
import org.junit.Test

// The picked model must keep the SAF URI it was built from; imports no longer
// identify a book by display name.
class ImportSheetModelTest {

    @Test
    fun `picked file carries the document uri name and format`() {
        val picked = PickedImportFile(
            uri = "content://com.android.providers.downloads.documents/document/42",
            fileName = "Book.EPUB",
            format = formatForFileName("Book.EPUB"),
        )
        assertEquals("content://com.android.providers.downloads.documents/document/42", picked.uri)
        assertEquals("Book.EPUB", picked.fileName)
        assertEquals("EPUB", picked.format)
    }

    @Test
    fun `format follows the extension`() {
        assertEquals("EPUB", formatForFileName("a.epub"))
        assertEquals("FB2", formatForFileName("a.FB2"))
        assertEquals("CBZ", formatForFileName("a.cbz"))
    }

    @Test
    fun `duplicates count as accepted for the toast`() {
        assertEquals(3, ImportSummary(imported = 2, duplicates = 1, failures = 4).accepted)
        assertEquals(0, ImportSummary(failures = 2).accepted)
    }
}
