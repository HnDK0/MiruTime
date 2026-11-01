package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryPagingTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repository = LibraryRepository(db.libraryDao())
        repository.ensureDefaultShelves()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `keyset pages cover every row in order for all sorts and directions`() = runTest {
        val books = seedLibrary(137)
        val unread = unreadCounts(books)

        for (sort in LibrarySort.entries) {
            for (direction in LibrarySortDirection.entries) {
                val pages = collectPages(sort = sort, direction = direction)
                val ids = pages.flatMap { page -> page.books.map { it.bookId } }
                val expected = expectedOrder(books, unread, sort, direction)

                assertEquals(137, ids.size, "$sort/$direction row count")
                assertEquals(ids.size, ids.distinct().size, "$sort/$direction duplicates")
                assertEquals(expected, ids, "$sort/$direction order")
                assertTrue(pages.dropLast(1).all { it.books.size == 60 }, "$sort/$direction page size")
                assertEquals(17, pages.last().books.size, "$sort/$direction remainder")
                assertTrue(pages.all { it.total == 137 }, "$sort/$direction total")
                assertEquals(
                    pages.mapIndexed { index, page -> page.hasMore == (index < pages.lastIndex) },
                    List(pages.size) { true },
                    "$sort/$direction hasMore",
                )
            }
        }
    }

    @Test
    fun `tied keys and null lastRead stay stable across page boundaries`() = runTest {
        val books = listOf(
            seedOne("A", lastReadAt = 100L, addedAt = 10L),
            seedOne("B", lastReadAt = 100L, addedAt = 10L),
            seedOne("C", lastReadAt = 100L, addedAt = 5L),
            seedOne("D", lastReadAt = null, addedAt = 9L),
            seedOne("E", lastReadAt = null, addedAt = 9L),
            seedOne("F", lastReadAt = null, addedAt = 1L),
        )
        val unread = unreadCounts(books)

        for (direction in LibrarySortDirection.entries) {
            val pages = collectPages(
                books = books,
                sort = LibrarySort.LastRead,
                direction = direction,
                limit = 2,
            )
            val ids = pages.flatMap { page -> page.books.map { it.bookId } }
            assertEquals(
                expectedOrder(books, unread, LibrarySort.LastRead, direction),
                ids,
                "direction $direction",
            )
            assertEquals(ids.size, ids.distinct().size, "direction $direction duplicates")
        }
    }

    @Test
    fun `sql filters match count page and select all`() = runTest {
        val books = seedLibrary(137)
        val tagOnly = books.filter { indexOf(it) % 6 == 0 }.map { it.bookId }.toSet()
        val jsonOnly = books.filter { indexOf(it) % 4 == 0 }.map { it.bookId }.toSet()
        val completed = books.filter { indexOf(it) % 2 == 1 }.map { it.bookId }.toSet()
        val sourceOne = books.filter { indexOf(it) % 3 == 1 }.map { it.bookId }.toSet()
        val manga = sourceOne
        val combined = books
            .filter { indexOf(it) % 12 == 0 }
            .map { it.bookId }
            .toSet()

        assertFilter(books, LibraryPageFilters(shelfId = "completed"), completed)
        assertFilter(books, LibraryPageFilters(sources = setOf("src-1")), sourceOne)
        assertFilter(books, LibraryPageFilters(contentType = LibraryContentType.Manga), manga)
        assertFilter(books, LibraryPageFilters(genres = setOf("tag-genre")), tagOnly)
        assertFilter(books, LibraryPageFilters(genres = setOf("json-genre")), jsonOnly)
        // A genresJson-only book (divisible by 4 but not 6) must still match.
        assertTrue(jsonOnly.any { it !in tagOnly })
        assertFilter(
            books,
            LibraryPageFilters(
                shelfId = "reading",
                sources = setOf("src-0"),
                contentType = LibraryContentType.Novel,
                genres = setOf("json-genre"),
            ),
            combined,
        )
    }

    @Test
    fun `facets select all and shelf summaries use the same library rows`() = runTest {
        val books = seedLibrary(137)
        val facets = repository.libraryFacets()

        assertEquals(listOf("src-0", "src-1", "src-2"), facets.sources)
        assertTrue("tag-genre" in facets.genres)
        assertTrue("json-genre" in facets.genres)
        assertTrue("g0" in facets.genres)
        assertTrue(facets.genres.none { it.isBlank() })
        assertEquals(facets.genres.distinct().sorted(), facets.genres)

        val allUrls = repository.matchingLibraryUrls()
        assertEquals(137, allUrls.size)
        assertEquals(137, allUrls.distinct().size)

        val jsonUrls = repository.matchingLibraryUrls(
            LibraryPageFilters(genres = setOf("json-genre")),
        ).toSet()
        val expectedJsonUrls = books
            .filter { indexOf(it) % 4 == 0 }
            .map { it.url }
            .toSet()
        assertEquals(expectedJsonUrls, jsonUrls)

        val summaries = repository.shelfSummaries().associateBy { it.shelfId }
        assertEquals(69, summaries.getValue("reading").bookCount)
        assertEquals(68, summaries.getValue("completed").bookCount)

        val readingCover = books
            .filter { it.shelfId == "reading" }
            .sortedWith(orderComparator(LibrarySort.LastRead, unreadCounts(books)))
            .first()
        assertEquals(readingCover.title, summaries.getValue("reading").coverTitle)
        assertEquals(readingCover.coverUrl, summaries.getValue("reading").coverUrl)
    }

    @Test
    fun `unread sort subtracts read chapters from the total`() = runTest {
        val books = listOf(
            seedOne("A", lastReadAt = 1L, addedAt = 1L, totalChapters = 10),
            seedOne("B", lastReadAt = 2L, addedAt = 2L, totalChapters = 10),
            seedOne("C", lastReadAt = 3L, addedAt = 3L, totalChapters = 10),
            seedOne("D", lastReadAt = 4L, addedAt = 4L, totalChapters = 10),
        )
        val dao = db.libraryDao()
        // A: 1 read -> 9 unread, B: 3 read -> 7, C: 0 read -> 10, D: 10 read -> 0.
        markReadChapters(dao, books[0], 1)
        markReadChapters(dao, books[1], 3)
        markReadChapters(dao, books[3], 10)
        val unread = mapOf(
            books[0].bookId to 9,
            books[1].bookId to 7,
            books[2].bookId to 10,
            books[3].bookId to 0,
        )

        val asc = collectPages(
            books = books,
            sort = LibrarySort.Unread,
            direction = LibrarySortDirection.Asc,
            limit = 2,
        )
        val ascIds = asc.flatMap { page -> page.books.map { it.bookId } }
        assertEquals(expectedOrder(books, unread, LibrarySort.Unread, LibrarySortDirection.Asc), ascIds)

        val desc = collectPages(
            books = books,
            sort = LibrarySort.Unread,
            direction = LibrarySortDirection.Desc,
            limit = 3,
        )
        val descIds = desc.flatMap { page -> page.books.map { it.bookId } }
        assertEquals(expectedOrder(books, unread, LibrarySort.Unread, LibrarySortDirection.Desc), descIds)
    }

    @Test
    fun `novel content filter follows the mapper default`() = runTest {
        val exact = seedOne("N", contentType = "Novel")
        val unknown = seedOne("W", contentType = "Webtoon")
        val manga = seedOne("M", contentType = "Manga")

        assertEquals(
            setOf(exact.url, unknown.url),
            repository.matchingLibraryUrls(
                LibraryPageFilters(contentType = LibraryContentType.Novel),
            ).toSet(),
        )
        assertEquals(
            setOf(manga.url),
            repository.matchingLibraryUrls(
                LibraryPageFilters(contentType = LibraryContentType.Manga),
            ).toSet(),
        )
    }

    @Test
    fun `keyset page query uses an index for the shelf filter`() = runTest {
        seedLibrary(137)
        val plan = libraryPageQuery(
            limit = 60,
            cursor = null,
            filters = LibraryPageFilters(shelfId = "reading"),
            sort = LibrarySort.LastRead,
            direction = LibrarySortDirection.Desc,
        )
        val details = db.useConnection(isReadOnly = true) { transactor ->
            transactor.usePrepared("EXPLAIN QUERY PLAN ${plan.sql}") { statement ->
                statement.bindAll(plan.args)
                val rows = mutableListOf<String>()
                while (statement.step()) rows += statement.getText(3)
                rows
            }
        }

        assertTrue(
            details.any { it.contains("USING INDEX", ignoreCase = true) },
            "expected an index scan, got $details",
        )
        assertFalse(
            details.any { it.contains("SCAN b", ignoreCase = true) },
            "shelf filter must not scan books, got $details",
        )
    }

    // --- helpers -------------------------------------------------------------

    private suspend fun collectPages(
        books: List<BookEntity> = emptyList(),
        sort: LibrarySort,
        direction: LibrarySortDirection,
        limit: Int = 60,
        filters: LibraryPageFilters = LibraryPageFilters(),
    ): List<LibraryPage> {
        val pages = mutableListOf<LibraryPage>()
        var cursor: LibraryPageCursor? = null
        do {
            val page = repository.libraryPage(
                limit = limit,
                cursor = cursor,
                filters = filters,
                sort = sort,
                direction = direction,
            )
            pages += page
            cursor = page.nextCursor
            if (pages.size > books.size.coerceAtLeast(138)) error("pagination did not terminate")
        } while (page.hasMore)
        return pages
    }

    private suspend fun assertFilter(
        books: List<BookEntity>,
        filters: LibraryPageFilters,
        expectedIds: Set<Long>,
    ) {
        val pages = collectPages(sort = LibrarySort.LastRead, direction = LibrarySortDirection.Desc, filters = filters)
        val ids = pages.flatMap { page -> page.books.map { it.bookId } }
        assertEquals(expectedIds, ids.toSet(), "$filters ids")
        assertEquals(expectedIds.size, repository.libraryPageCount(filters), "$filters count")
        assertTrue(pages.all { it.total == expectedIds.size }, "$filters page total")
        assertTrue(ids.none { it !in expectedIds }, "$filters unexpected row")
    }

    private suspend fun seedLibrary(count: Int): List<BookEntity> {
        val dao = db.libraryDao()
        val books = mutableListOf<BookEntity>()
        for (i in 0 until count) {
            val entity = BookEntity(
                installId = "src-${i % 3}",
                url = "https://site/book/$i",
                title = "Book ${i.toString().padStart(3, '0')}",
                author = "Author",
                coverUrl = if (i % 3 == 0) "https://covers/$i.jpg" else null,
                contentType = when (i % 3) {
                    0 -> "Novel"
                    1 -> "Manga"
                    else -> "Video"
                },
                rating = 4.0,
                shelfId = if (i % 2 == 0) "reading" else "completed",
                totalChapters = 5 + (i % 9) * 10,
                inLibrary = true,
                addedAt = 1_000L + i,
                updatedAt = 1L,
                lastReadAt = if (i % 5 == 0) null else (i % 4) * 100L,
                lastChapterId = null,
                lastChapterIndex = null,
                chapterListHash = null,
                lastUpdateAt = null,
                genresJson = if (i % 4 == 0) """["json-genre","g${i % 3}"]""" else "[]",
            )
            val id = dao.upsertBook(entity)
            books += entity.copy(bookId = id)
        }

        val tagId = dao.upsertTag(
            TagEntity(
                name = "tag-genre",
                normalizedName = "tag-genre",
                kind = "GENRE",
                createdAt = 1L,
            ),
        )
        for (book in books.filter { indexOf(it) % 6 == 0 }) {
            dao.upsertBookTag(BookTagEntity(bookId = book.bookId, tagId = tagId))
        }
        return books
    }

    private suspend fun seedOne(
        title: String,
        lastReadAt: Long? = null,
        addedAt: Long = 1L,
        totalChapters: Int = 10,
        contentType: String = "Novel",
    ): BookEntity {
        val id = db.libraryDao().upsertBook(
            BookEntity(
                installId = "src-0",
                url = "https://site/$title",
                title = title,
                author = "Author",
                coverUrl = "https://covers/$title.jpg",
                contentType = contentType,
                rating = 4.0,
                shelfId = "reading",
                totalChapters = totalChapters,
                inLibrary = true,
                addedAt = addedAt,
                updatedAt = 1L,
                lastReadAt = lastReadAt,
                lastChapterId = null,
                lastChapterIndex = null,
                chapterListHash = null,
                lastUpdateAt = null,
                genresJson = "[]",
            ),
        )
        return repository.book(id)!!
    }

    private suspend fun unreadCounts(books: List<BookEntity>): Map<Long, Int> {
        val stats = repository.readChapterStatsFor(books.map { it.bookId })
        return books.associate { book ->
            val read = stats[book.bookId]?.readCount ?: 0
            book.bookId to (book.totalChapters - read).coerceAtLeast(0)
        }
    }

    private fun expectedOrder(
        books: List<BookEntity>,
        unread: Map<Long, Int>,
        sort: LibrarySort,
        direction: LibrarySortDirection,
    ): List<Long> {
        val comparator = orderComparator(sort, unread)
        val base = books.sortedWith(comparator)
        // LastRead/LastUpdate/Added base is newest-first (Desc); Title/Unread
        // base is ascending, so Desc is their reverse.
        val reverse = when (sort) {
            LibrarySort.LastRead, LibrarySort.LastUpdate, LibrarySort.Added ->
                direction == LibrarySortDirection.Asc
            LibrarySort.Title, LibrarySort.Unread ->
                direction == LibrarySortDirection.Desc
        }
        val ordered = if (reverse) base.reversed() else base
        return ordered.map { it.bookId }
    }

    private fun orderComparator(sort: LibrarySort, unread: Map<Long, Int>): Comparator<BookEntity> =
        when (sort) {
            LibrarySort.LastRead, LibrarySort.LastUpdate, LibrarySort.Added ->
                compareByDescending<BookEntity> { it.lastReadAt ?: -1L }
                    .thenByDescending { it.addedAt }
                    .thenBy { it.bookId }

            LibrarySort.Title -> compareBy<BookEntity> { it.title }.thenBy { it.bookId }
            LibrarySort.Unread ->
                compareBy<BookEntity> { unread.getValue(it.bookId) }.thenBy { it.bookId }
        }

    private suspend fun markReadChapters(dao: LibraryDao, book: BookEntity, count: Int) {
        for (idx in 1..count) {
            dao.upsertChapter(
                ChapterEntity(
                    bookId = book.bookId,
                    idx = idx,
                    url = "${book.url}/$idx",
                    title = "Chapter $idx",
                    read = true,
                    readAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }
        dao.updateTotalChapters(book.bookId, book.totalChapters)
    }

    private fun indexOf(book: BookEntity): Int = book.url.substringAfterLast('/').toInt()

    private fun SQLiteStatement.bindAll(args: List<Any?>) {
        args.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> bindNull(position)
                is String -> bindText(position, value)
                is Long -> bindLong(position, value)
                is Int -> bindLong(position, value.toLong())
                is Boolean -> bindLong(position, if (value) 1L else 0L)
                else -> error("Unsupported filter bind: $value")
            }
        }
    }
}
