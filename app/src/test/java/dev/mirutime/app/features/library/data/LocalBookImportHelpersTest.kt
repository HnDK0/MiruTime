package dev.mirutime.app.features.library.data

import dev.mirutime.core.api.ContentType
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(LocalImportFormat.PDF, LocalImportFormat.forFile("x.pdf"))
        assertNull(LocalImportFormat.forFile("notes.txt"))
    }

    @Test
    fun `pdf is a books-shelf manga read page by page`() {
        val pdf = LocalImportFormat.forFile("x.pdf", "application/pdf")

        assertEquals(LocalImportFormat.PDF, pdf)
        assertEquals(LocalImportFormat.PDF, LocalImportFormat.forFile("x", "application/x-pdf"))
        assertEquals(LocalImportFormat.PDF, LocalImportFormat.forFile("x", "application/pdf; charset=binary"))
        assertEquals(LocalBooksInstallId, pdf?.installId)
        assertEquals(ContentType.MANGA, pdf?.contentType)
        assertTrue(pdf?.isManga == true)
    }

    @Test
    fun `a pdf row is manga even though it sits on the books shelf`() {
        // The reader dispatches on the content type, not on the install id.
        assertTrue(isLocalManga(LocalBooksInstallId, "PDF"))
        assertTrue(isLocalManga(LocalMangaInstallId, "CBZ"))
        assertTrue(!isLocalManga(LocalBooksInstallId, "EPUB"))
        assertEquals(ContentType.MANGA, localBookContentType(LocalBooksInstallId, "PDF"))
        assertEquals(ContentType.NOVEL, localBookContentType(LocalBooksInstallId, "FB2"))
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
    fun `zipped fb2 and catalog mime spellings are recognized`() {
        assertEquals(LocalImportFormat.FB2, LocalImportFormat.forFile("book.fb2.zip"))
        assertEquals(LocalImportFormat.FB2, LocalImportFormat.forFile("book.FB2.ZIP"))
        assertEquals(LocalImportFormat.FB2, LocalImportFormat.forFile("opds", "application/fb2+zip"))
        assertEquals(LocalImportFormat.FB2, LocalImportFormat.forFile("opds", "text/fb2+xml"))
        assertEquals(
            LocalImportFormat.FB2,
            LocalImportFormat.forFile("opds", "application/fb2+zip; charset=utf-8"),
        )
    }

    @Test
    fun `catalogue import writes origin and keeps the acquisition url`() {
        val origin = ImportOrigin(
            catalogUrl = "https://opds.test/root",
            catalogName = "Test Catalog",
            entryUrl = "https://opds.test/entry/1",
            sourceFileUrl = "https://opds.test/files/1.epub",
        )

        val row = localBookFileEntity(
            bookId = 7,
            uri = "file:///cache/opds/dead-temp.epub",
            displayName = "1.epub",
            format = LocalImportFormat.EPUB,
            mime = "application/epub+zip",
            size = 42,
            sha256 = "sha",
            target = "local_books/local-books/sha.epub",
            timestamp = 5L,
            origin = origin,
        )

        assertEquals("https://opds.test/files/1.epub", row.uri)
        assertEquals("https://opds.test/root", row.originCatalogUrl)
        assertEquals("https://opds.test/entry/1", row.originEntryUrl)
        assertEquals("Test Catalog", row.originCatalogName)
        assertEquals("local_books/local-books/sha.epub", row.copyPath)
    }

    @Test
    fun `device import leaves the origin empty and keeps its own uri`() {
        val row = localBookFileEntity(
            bookId = 8,
            uri = "content://device/book.epub",
            displayName = "book.epub",
            format = LocalImportFormat.EPUB,
            mime = null,
            size = 42,
            sha256 = "sha",
            target = "local_books/local-books/sha.epub",
            timestamp = 5L,
            origin = null,
        )

        assertEquals("content://device/book.epub", row.uri)
        assertEquals("", row.originCatalogUrl)
        assertEquals("", row.originEntryUrl)
        assertEquals("", row.originCatalogName)
    }

    @Test
    fun `a duplicate import of a device file keeps its own uri and records the origin`() {
        val origin = ImportOrigin(
            catalogUrl = "https://opds.test/root",
            catalogName = "Test Catalog",
            entryUrl = "https://opds.test/entry/1",
            sourceFileUrl = "https://opds.test/files/1.epub",
        )
        // A device-file row: its own SAF uri must survive the catalogue add.
        val row = localBookFileEntity(
            bookId = 9,
            uri = "content://device/book.epub",
            displayName = "book.epub",
            format = LocalImportFormat.EPUB,
            mime = null,
            size = 42,
            sha256 = "sha",
            target = "local_books/local-books/sha.epub",
            timestamp = 5L,
            origin = null,
        )

        val updated = row.withOrigin(origin)

        // Not catalogue-sourced: the device uri stays, the badge/provenance
        // fields are still written.
        assertEquals("content://device/book.epub", updated.uri)
        assertEquals("Test Catalog", updated.originCatalogName)
        assertEquals("https://opds.test/entry/1", updated.originEntryUrl)
        assertEquals("https://opds.test/root", updated.originCatalogUrl)
        // Identity of the file itself never moves.
        assertEquals("sha", updated.sha256)
        assertEquals(9L, updated.bookId)
    }

    @Test
    fun `a duplicate catalogue import follows the newest acquisition url`() {
        val first = ImportOrigin(
            catalogUrl = "https://opds.test/root",
            catalogName = "Test Catalog",
            entryUrl = "https://opds.test/entry/1",
            sourceFileUrl = "https://opds.test/files/1.epub",
        )
        val second = first.copy(
            entryUrl = "https://opds.test/entry/1-renamed",
            sourceFileUrl = "https://opds.test/files/1-renamed.epub",
        )
        val row = localBookFileEntity(
            bookId = 9,
            uri = "content://device/book.epub",
            displayName = "book.epub",
            format = LocalImportFormat.EPUB,
            mime = null,
            size = 42,
            sha256 = "sha",
            target = "local_books/local-books/sha.epub",
            timestamp = 5L,
            origin = first,
        )

        val updated = row.withOrigin(second)

        assertEquals("https://opds.test/files/1-renamed.epub", updated.uri)
        assertEquals("https://opds.test/entry/1-renamed", updated.originEntryUrl)
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
