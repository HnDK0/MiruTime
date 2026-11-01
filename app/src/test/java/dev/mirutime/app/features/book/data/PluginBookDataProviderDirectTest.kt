package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.finder.data.LocalSafBook
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LocalBookFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBookDataProviderDirectTest {

    private val meta = LocalSafBook(
        bookUrl = "local-file://content%3A%2F%2Ftree%2Fdoc",
        documentUri = "content://tree/doc",
        displayName = "one.epub",
        title = "One",
        author = "Ann",
        description = "Desc",
        coverUrl = "file:///cache/cover.jpg",
        format = "EPUB",
        isManga = false,
        chapterCount = 2,
        cacheHash = "hash-one",
        sizeBytes = 10,
        installId = "local-books",
    )

    @Test
    fun `direct file row has no copy path and keeps legacy copy when present`() {
        val fresh = PluginBookDataProvider.directBookFileRow(meta, "content://tree/doc", null, 5)
        assertNull(fresh.copyPath)
        assertEquals(0L, fresh.bookId)
        assertEquals("hash-one", fresh.sha256)
        assertEquals("EPUB", fresh.format)

        val legacy = LocalBookFileEntity(
            bookId = 9,
            uri = "content://tree/doc",
            displayName = "one.epub",
            format = "EPUB",
            mime = null,
            sizeBytes = 10,
            sha256 = "old",
            copyPath = "local_books/local-books/old.epub",
            importedAt = 1,
        )
        val kept = PluginBookDataProvider.directBookFileRow(meta, "content://tree/doc", legacy, 6)
        assertEquals("local_books/local-books/old.epub", kept.copyPath)
        assertEquals(9L, kept.bookId)
    }

    @Test
    fun `direct history row starts outside the library and keeps membership on refresh`() {
        val fresh = PluginBookDataProvider.buildLocalSafBookRow(
            existing = null,
            meta = meta,
            bookUrl = meta.bookUrl,
            totalChapters = 2,
            timestamp = 100,
        )
        assertFalse(fresh.inLibrary)
        assertNull(fresh.addedToLibraryAt)
        assertEquals(2, fresh.totalChapters)
        assertEquals(meta.bookUrl, fresh.url)

        val existing = BookEntity(
            bookId = 3,
            installId = "local-books",
            url = meta.bookUrl,
            title = "Old",
            author = "Old",
            coverUrl = null,
            contentType = "Novel",
            rating = 4.0,
            shelfId = "completed",
            totalChapters = 1,
            inLibrary = true,
            addedAt = 10,
            updatedAt = 10,
            lastReadAt = 20,
            lastChapterId = null,
            lastChapterIndex = 1,
            chapterListHash = null,
            lastUpdateAt = null,
            genresJson = "[]",
        )
        val refreshed = PluginBookDataProvider.buildLocalSafBookRow(
            existing = existing,
            meta = meta,
            bookUrl = meta.bookUrl,
            totalChapters = 3,
            timestamp = 200,
        )
        assertTrue(refreshed.inLibrary)
        assertEquals("completed", refreshed.shelfId)
        assertEquals(20L, refreshed.lastReadAt)
        assertEquals(3, refreshed.totalChapters)
    }
}
