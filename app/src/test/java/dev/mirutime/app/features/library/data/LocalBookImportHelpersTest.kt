package dev.mirutime.app.features.library.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pure JVM coverage for the pieces of the importer that do not need Android:
// format/kind detection, content-addressed copy naming, sha256 and the size
// guard.
class LocalBookImportHelpersTest {

    @Test
    fun `extension picks the install source and format`() {
        assertEquals(LocalImportFormat.EPUB, LocalImportFormat.forFile("Book.EPUB"))
        assertEquals(LocalImportFormat.FB2, LocalImportFormat.forFile("book.fb2"))
        assertEquals(LocalImportFormat.CBZ, LocalImportFormat.forFile("comic.cbz"))
        assertNull(LocalImportFormat.forFile("notes.txt"))
    }

    @Test
    fun `mime is the fallback when the name has no extension`() {
        assertEquals(LocalImportFormat.EPUB, LocalImportFormat.forFile("book", "application/epub+zip"))
        assertEquals(
            LocalImportFormat.FB2,
            LocalImportFormat.forFile("book", "application/x-fictionbook+xml"),
        )
        assertEquals(
            LocalImportFormat.CBZ,
            LocalImportFormat.forFile("comic", "application/vnd.comicbook+zip"),
        )
    }

    @Test
    fun `copy path is content addressed and not title based`() {
        assertEquals(
            "local_books/local-books/abc123.epub",
            localCopyPath(LocalImportFormat.EPUB, "abc123"),
        )
        assertEquals(
            "local_books/local-manga/def456.cbz",
            localCopyPath(LocalImportFormat.CBZ, "def456"),
        )
    }

    @Test
    fun `sha256 is stable hex`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `read limited returns bytes under the cap and null over it`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(bytes, readLimited(ByteArrayInputStream(bytes), maxBytes = 5))
        assertNull(readLimited(ByteArrayInputStream(bytes), maxBytes = 4))
    }

    @Test
    fun `import summary counts new duplicate and failed files`() {
        val summary = summarizeImports(
            listOf(
                LocalImportResult.Success(1, "local://a", "A", duplicate = false),
                LocalImportResult.Success(2, "local://b", "B", duplicate = true),
                LocalImportResult.Failure("bad"),
            ),
        )
        assertEquals(1, summary.imported)
        assertEquals(1, summary.duplicates)
        assertEquals(1, summary.failures)
        assertEquals(2, summary.accepted)
    }
}
