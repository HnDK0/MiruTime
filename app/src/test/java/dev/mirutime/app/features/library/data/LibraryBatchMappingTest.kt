package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookReadStats
import org.junit.Assert.assertEquals
import org.junit.Test

// The list mapper only sees batch maps: no repository/DAO is in this test, so
// a regression to per-book reads/tags would have to reintroduce a dependency
// here and fail to compile.
class LibraryBatchMappingTest {

    @Test
    fun `120 rows map read counts tags and cover fallback from batch maps`() {
        val rows = (1..120).map { index ->
            book(
                id = index.toLong(),
                total = 10,
                lastChapterIndex = if (index % 3 == 0) 7 else null,
                genresJson = "[\"fallback\"]",
            )
        }
        val stats = rows.associate { row ->
            row.bookId to BookReadStats(
                readCount = (row.bookId % 6).toInt(),
                maxReadIndex = (row.bookId % 6).toInt(),
            )
        }
        val tags = rows.associate { row ->
            row.bookId to if (row.bookId % 2L == 0L) listOf("tag-${row.bookId}") else emptyList()
        }

        val mapped = rows.mapIndexed { rank, row ->
            mapLibraryBookRow(
                entity = row,
                rank = rank,
                timestamp = 1_000_000L,
                stats = stats,
                tags = tags,
                covers = emptyMap(),
                coverStore = null,
            )
        }

        assertEquals(120, mapped.size)
        assertEquals((1L % 6).toInt(), mapped.first().readChapters)
        // The saved last-read chapter still drives Continue...
        assertEquals(7, mapped[2].progressChapter)
        assertEquals(4, mapped[3].progressChapter)
        // ...while the card progress bar reads the finished-chapter count.
        assertEquals(3, mapped[2].readChapters)
        assertEquals(4, mapped[3].readChapters)
        assertEquals(listOf("tag-2"), mapped[1].genres)
        assertEquals(listOf("fallback"), mapped[2].genres)
        assertEquals("legacy-3", mapped[2].coverUrl)
        // Continue opens the saved last-read chapter, not last + 1: the
        // label must match the actual target.
        assertEquals("Chapter 7", mapped[2].continueLabel)
        assertEquals("Chapter 4", mapped[3].continueLabel)
    }

    private fun book(
        id: Long,
        total: Int,
        lastChapterIndex: Int?,
        genresJson: String,
    ) = BookEntity(
        bookId = id,
        installId = "plugin",
        url = "https://site/book/$id",
        title = "Book $id",
        author = "Author",
        coverUrl = "legacy-$id",
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = total,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = lastChapterIndex,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = genresJson,
    )
}
