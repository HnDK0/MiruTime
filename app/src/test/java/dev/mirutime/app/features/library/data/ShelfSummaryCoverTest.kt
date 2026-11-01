package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ShelfSummaryRow
import dev.mirutime.imageloader.FetchedImage
import java.lang.reflect.Proxy
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Shelf thumbnails must use the same cover precedence as library rows: a
// stored local file wins over the raw legacy coverUrl from the summary query.
class ShelfSummaryCoverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @Test
    fun `shelf summary resolves the representative local cover`() = runTest {
        val rows = mutableMapOf<Long, BookCoverEntity>()
        val covers = BookCoverStore(
            root = temp.root,
            load = { rows[it] },
            save = { rows[it.bookId] = it },
            fetch = { _, _, _ -> FetchedImage(0) },
        )
        assertTrue(
            covers.storeLocalCover(
                bookId = 7L,
                remoteUrl = "https://cdn.test/7.jpg",
                bytes = png,
            ),
        )

        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "shelfSummaries" -> listOf(
                    ShelfSummaryRow(
                        shelfId = "reading",
                        bookCount = 2,
                        coverUrl = "https://legacy.test/raw.jpg",
                        coverTitle = "Book 7",
                        coverBookId = 7L,
                    ),
                )
                "bookCoversFor" -> listOf(rows.getValue(7L))
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

        val provider = RoomLibraryDataProvider(
            library = LibraryRepository(dao),
            coverStore = covers,
            translations = null,
            now = { 1_000L },
        )

        val summary = provider.shelfSummaries().single()

        assertEquals(7L, summary.coverBookId)
        assertEquals("Book 7", summary.coverTitle)
        assertTrue(summary.coverUrl!!.startsWith("file:"))
    }
}
