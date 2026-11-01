package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.book.data.localBookCoverUrl
import dev.mirutime.app.features.dashboard.data.dashboardCoverUrl
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.imageloader.FetchedImage
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Cover precedence shared by Library, Dashboard, Book and local catalogue.
class BookCoverResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private class Rows(initial: Map<Long, BookCoverEntity> = emptyMap()) {
        val map = initial.toMutableMap()
        val load: suspend (Long) -> BookCoverEntity? = { map[it] }
        val save: suspend (BookCoverEntity) -> Unit = { map[it.bookId] = it }
    }

    private fun store(rows: Rows) = BookCoverStore(
        root = temp.root,
        load = rows.load,
        save = rows.save,
        fetch = { _, _, _ -> FetchedImage(0) },
    )

    @Test
    fun `local imported cover wins and resolves to a file uri`() = runTest {
        val rows = Rows()
        val covers = store(rows)
        covers.storeLocalCover(
            bookId = 21,
            remoteUrl = "https://cdn.test/fallback.jpg",
            bytes = png,
        )

        val resolved = resolveStoredCover(
            coverStore = covers,
            bookId = 21,
            fallback = "https://legacy.test/row.jpg",
        )

        assertTrue(resolved!!.startsWith("file:"))
        assertTrue(resolved.contains("/21/cover"))
    }

    @Test
    fun `remote row is used when no local file exists`() = runTest {
        val rows = Rows(
            mapOf(
                22L to BookCoverEntity(
                    bookId = 22,
                    remoteUrl = "https://cdn.test/remote.jpg",
                    localPath = "missing/22/cover",
                ),
            ),
        )

        assertEquals(
            "https://cdn.test/remote.jpg",
            resolveStoredCover(store(rows), 22, "https://legacy.test/row.jpg"),
        )
    }

    @Test
    fun `missing cover row falls back to legacy coverUrl`() = runTest {
        assertEquals(
            "https://legacy.test/row.jpg",
            resolveStoredCover(store(Rows()), 23, "https://legacy.test/row.jpg"),
        )
        assertNull(resolveStoredCover(store(Rows()), 24, null))
        assertNull(resolveStoredCover(null, 24, null))
    }

    @Test
    fun `imported epub cbz and folder rows map covers for book and dashboard`() = runTest {
        val rows = Rows()
        val covers = store(rows)
        // The import path is the same for EPUB/FB2/CBZ/folder: one local cover
        // file and one book_covers row keyed by bookId. Content type does not
        // change how the cover is resolved.
        val ids = listOf(31L, 32L, 33L)
        ids.forEach { id ->
            assertTrue(
                covers.storeLocalCover(
                    bookId = id,
                    remoteUrl = "https://cdn.test/$id.jpg",
                    bytes = png,
                ),
            )
        }

        ids.forEach { id ->
            val row = localRow(id, legacyCover = "https://legacy.test/$id.jpg")
            val bookCover = localBookCoverUrl(row, covers)
            val dashboardCover = dashboardCoverUrl(covers, id, rows.map[id], row.coverUrl)

            assertTrue(bookCover!!.startsWith("file:"))
            assertEquals(bookCover, dashboardCover)
        }
    }

    @Test
    fun `dashboard resolves a cover persisted by a direct open without library membership`() = runTest {
        val rows = Rows()
        val covers = store(rows)
        // ensureHistoryBook stores the embedded cover but leaves inLibrary=false.
        covers.storeLocalCover(bookId = 41, remoteUrl = null, bytes = png)
        val row = localRow(41, legacyCover = null).copy(inLibrary = false, addedToLibraryAt = null)

        val dashboard = dashboardCoverUrl(covers, row.bookId, rows.map[row.bookId], row.coverUrl)
        val book = localBookCoverUrl(row, covers)

        assertTrue(dashboard!!.startsWith("file:"))
        assertEquals(dashboard, book)
    }

    @Test
    fun `missing imported cover keeps the row fallback for book and dashboard`() = runTest {
        val row = localRow(34, legacyCover = "https://legacy.test/34.jpg")
        assertEquals("https://legacy.test/34.jpg", localBookCoverUrl(row, null))
        assertEquals(
            "https://legacy.test/34.jpg",
            dashboardCoverUrl(null, 34, null, row.coverUrl),
        )
    }

    private fun localRow(id: Long, legacyCover: String?) = BookEntity(
        bookId = id,
        installId = "local-books",
        url = "local://$id",
        title = "Local $id",
        author = "Unknown",
        coverUrl = legacyCover,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 10,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )
}
