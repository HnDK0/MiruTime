package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryTest {
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
    fun `read chapter stats chunks ids below the sqlite bind limit`() = runTest {
        val chunkSizes = mutableListOf<Int>()
        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "readChapterStatsFor" -> {
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    chunkSizes += ids.size
                    ids.map { id -> BookReadStatsRow(id, 1, id.toInt()) }
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

        val stats = LibraryRepository(dao).readChapterStatsFor((1L..1801L).toList())

        assertEquals(listOf(900, 900, 1), chunkSizes)
        assertEquals(1801, stats.size)
        assertEquals(1, stats[1801L]?.readCount)
    }

    @Test
    fun `tag and cover reads chunk ids below the sqlite bind limit`() = runTest {
        val tagChunks = mutableListOf<Int>()
        val coverChunks = mutableListOf<Int>()
        val dao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "tagsByBookFor" -> {
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    tagChunks += ids.size
                    ids.flatMap { id ->
                        listOf(BookTagNameRow(id, "a-$id"), BookTagNameRow(id, "a-$id"))
                    }
                }
                "bookCoversFor" -> {
                    val ids = (args!!.first() as List<*>).filterIsInstance<Long>()
                    coverChunks += ids.size
                    ids.map { id ->
                        BookCoverEntity(bookId = id, remoteUrl = "https://cdn.test/$id.jpg")
                    }
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao

        val repository = LibraryRepository(dao)
        val ids = (1L..1801L).toList()

        val tags = repository.tagsByBookFor(ids)
        assertEquals(listOf(900, 900, 1), tagChunks)
        assertEquals(1801, tags.size)
        // Duplicate names and the per-book position order survive chunking.
        assertEquals(listOf("a-1", "a-1"), tags[1L])
        assertEquals(listOf("a-1801", "a-1801"), tags[1801L])

        val covers = repository.bookCoversFor(ids)
        assertEquals(listOf(900, 900, 1), coverChunks)
        assertEquals(1801, covers.size)
        assertEquals("https://cdn.test/1801.jpg", covers[1801L]?.remoteUrl)

        assertTrue(repository.tagsByBookFor(emptyList()).isEmpty())
        assertTrue(repository.bookCoversFor(emptyList()).isEmpty())
    }


    @Test
    fun `default shelves are seeded once`() = runTest {
        repository.ensureDefaultShelves()
        assertEquals(
            listOf("reading", "completed"),
            repository.shelves().map { it.id },
        )
    }

    @Test
    fun `library excludes history-only books and shelf changes stick`() = runTest {
        val historical = book("https://site/book/1", inLibrary = false, lastReadAt = 10L)
        val saved = book("https://site/book/2", inLibrary = true, lastReadAt = 20L)
        repository.saveBook(historical)
        repository.saveBook(saved)
        // History is its own table now; a touch records the independent row.
        repository.touchHistory(historical.bookUrl, chapterIndex = 1, at = 10L)
        repository.touchHistory(saved.bookUrl, chapterIndex = 1, at = 20L)

        assertEquals(listOf(saved.bookUrl), repository.libraryBooks().map { it.bookUrl })
        assertEquals(listOf(saved.bookUrl, historical.bookUrl), repository.history(10).map { it.bookUrl })

        repository.setShelf(saved.bookUrl, "completed")
        assertEquals("completed", repository.book(saved.bookUrl)?.shelfId)
    }

    @Test
    fun `toggle and read state are persisted`() = runTest {
        val book = book("https://site/book/3", inLibrary = false, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(4, "${book.bookUrl}/4", "Four"), ChapterSeed(5, "${book.bookUrl}/5", "Five")),
            at = 1L,
        )

        repository.setInLibrary(book.bookUrl, true)
        assertTrue(repository.isInLibrary(book.bookUrl))

        repository.markChapterRead(book.bookUrl, 4, true, at = 100L)
        repository.markChapterRead(book.bookUrl, 5, true, at = 100L)
        repository.markChapterRead(book.bookUrl, 5, false, at = 100L)

        assertEquals(listOf(4), repository.readChapterIndexes(book.bookUrl))
    }

    @Test
    fun `touch history updates last chapter and ordering`() = runTest {
        val first = book("https://site/book/4", inLibrary = true, lastReadAt = 10L)
        val second = book("https://site/book/5", inLibrary = true, lastReadAt = 20L)
        repository.saveBook(first)
        repository.saveBook(second)
        val firstId = repository.book(first.bookUrl)!!.bookId
        repository.replaceChapters(firstId, listOf(ChapterSeed(12, "${first.bookUrl}/12", "Twelve")), at = 1L)

        repository.touchHistory(first.bookUrl, chapterIndex = 12, at = 30L)
        repository.touchHistory(second.bookUrl, chapterIndex = 1, at = 20L)

        val history = repository.history(10)
        assertEquals(listOf(first.bookUrl, second.bookUrl), history.map { it.bookUrl })
        assertEquals(12, history.first().lastChapterIndex)
        assertNotNull(history.first().lastChapterId)
        assertFalse(repository.isInLibrary("https://site/missing"))
    }

    @Test
    fun `total chapters are updated in place`() = runTest {
        val book = book("https://site/book/6", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId

        repository.updateTotalChapters(bookId, 250)

        assertEquals(250, repository.book(bookId)?.totalChapters)
        assertEquals("reading", repository.book(bookId)?.shelfId)
    }

    @Test
    fun `chapter list reconciliation keeps ids and read state`() = runTest {
        val book = book("https://site/book/reconcile", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val first = repository.replaceChapters(
            bookId,
            listOf(
                ChapterSeed(1, "https://site/ch/1", "One"),
                ChapterSeed(2, "https://site/ch/2", "Two"),
            ),
            at = 10L,
        )
        val oneId = first.first { it.idx == 1 }.chapterId
        val twoId = first.first { it.idx == 2 }.chapterId
        repository.markChapterRead(bookId, 1, true, at = 20L)

        val second = repository.replaceChapters(
            bookId,
            listOf(
                ChapterSeed(1, "https://site/ch/1", "One updated"),
                ChapterSeed(3, "https://site/ch/3", "Three"),
            ),
            at = 30L,
        )

        assertEquals(listOf(1, 3), second.map { it.idx })
        assertEquals(oneId, second.first { it.idx == 1 }.chapterId)
        assertEquals("One updated", second.first { it.idx == 1 }.title)
        assertTrue(second.first { it.idx == 1 }.read)
        assertEquals(listOf(1), repository.readChapterIndexes(bookId))

        val readded = repository.replaceChapters(
            bookId,
            listOf(
                ChapterSeed(1, "https://site/ch/1", "One updated"),
                ChapterSeed(2, "https://site/ch/2", "Two"),
            ),
            at = 40L,
        )
        assertEquals(twoId, readded.first { it.idx == 2 }.chapterId)
    }

    @Test
    fun `rebase rewrites urls but keeps book chapter and progress ids`() = runTest {
        repository.saveInstall(
            PluginInstallEntity(
                installId = "chikari",
                pluginId = "chikari",
                baseUrlDefault = "https://old.site",
                baseUrlEffective = "https://old.site",
                installedAt = 1L,
            ),
        )
        val book = book("https://old.site/book/1", inLibrary = true, lastReadAt = null)
            .copy(installId = "chikari")
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val chapters = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://old.site/ch/1", "One")),
            at = 10L,
        )
        val chapterId = chapters.single().chapterId
        repository.saveProgress(
            ProgressEntity(bookId, chapterId, blockIndex = 1, charOffset = 0, sentenceIndex = null, offsetPx = 40, split = true, seq = 1, updatedAt = 10L),
        )

        val report = repository.rebaseInstall(
            installId = "chikari",
            oldBase = "https://old.site",
            newBase = "https://new.site",
            override = null,
            effective = "https://new.site",
            at = 20L,
        )

        assertEquals(RebaseReport(books = 1, chapters = 1), report)
        val rebased = repository.book(bookId)!!
        assertEquals("https://new.site/book/1", rebased.url)
        assertEquals("https://new.site/ch/1", repository.chapters(bookId).single().url)
        assertEquals(chapterId, repository.chapters(bookId).single().chapterId)
        assertEquals(chapterId, repository.progress(bookId)?.chapterId)
    }

    @Test
    fun `late progress writes with lower seq are rejected`() = runTest {
        val book = book("https://site/book/progress", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val chapterId = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://site/ch/1", "One")),
            at = 1L,
        ).single().chapterId

        repository.saveProgress(ProgressEntity(bookId, chapterId, 1, 10, null, 10, false, 10, 100L))
        repository.saveProgress(ProgressEntity(bookId, chapterId, 1, 20, null, 20, false, 5, 200L))
        assertEquals(10, repository.progress(bookId)?.charOffset)

        repository.saveProgress(ProgressEntity(bookId, chapterId, 1, 30, null, 30, false, 20, 300L))
        assertEquals(30, repository.progress(bookId)?.charOffset)
    }

    @Test
    fun `bulk chapter delete clears progress last chapter and bodies`() = runTest {
        val book = book("https://site/book/bulk", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val chapters = repository.replaceChapters(
            bookId,
            listOf(
                ChapterSeed(1, "https://site/ch/1", "One"),
                ChapterSeed(2, "https://site/ch/2", "Two"),
                ChapterSeed(3, "https://site/ch/3", "Three"),
            ),
            at = 1L,
        )
        val second = chapters.first { it.idx == 2 }
        repository.saveChapterBody(ChapterBodyEntity(second.chapterId, "text", "/tmp/bulk", 1, "sha", 1L))
        repository.saveProgress(ProgressEntity(bookId, second.chapterId, 0, 0, null, 0, false, 1, 1L))
        repository.touchHistory(bookId, second.chapterId, 2, 10L)

        repository.deleteChaptersByIndex(bookId, from = 1, to = 2, at = 20L)

        assertEquals(listOf(3), repository.chapters(bookId).map { it.idx })
        assertNull(repository.progress(bookId))
        assertNull(repository.chapterBody(second.chapterId))
        assertNull(repository.book(bookId)?.lastChapterId)
        assertEquals(1, repository.book(bookId)?.totalChapters)
    }

    @Test
    fun `bulk chapter read range updates only active chapters`() = runTest {
        val book = book("https://site/book/bulk-read", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        repository.replaceChapters(
            bookId,
            (1..3).map { ChapterSeed(it, "https://site/ch/$it", "Chapter $it") },
            at = 1L,
        )

        repository.markChapterRangeRead(bookId, from = 2, to = 3, read = true, at = 20L)

        assertEquals(listOf(2, 3), repository.readChapterIndexes(bookId))
    }

    @Test
    fun `update check records new chapters and mark seen clears them`() = runTest {
        val book = book("https://site/book/updates", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId

        repository.recordUpdateCheck(bookId, total = 120, newChapters = 20, hash = null, at = 100L)
        val checked = repository.book(bookId)!!
        assertEquals(120, checked.totalChapters)
        assertEquals(20, checked.newChapters)
        assertEquals(100L, checked.lastUpdateCheckAt)
        assertEquals(100L, checked.lastUpdateAt)
        assertEquals(listOf(bookId), repository.booksWithUpdates().map { it.bookId })

        repository.markUpdatesSeen(bookId, at = 200L)
        assertEquals(0, repository.book(bookId)?.newChapters)
        assertTrue(repository.booksWithUpdates().isEmpty())
    }

    @Test
    fun `update check keeps the unseen badge and accumulates new chapters`() = runTest {
        val book = book("https://site/book/updates2", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId

        repository.recordUpdateCheck(bookId, total = 100, newChapters = 3, hash = "h1", at = 1L)
        assertEquals(3, repository.book(bookId)?.newChapters)

        // A later no-change run must NOT erase the unseen badge.
        repository.recordUpdateCheck(bookId, total = 100, newChapters = 0, hash = "h1", at = 2L)
        assertEquals(3, repository.book(bookId)?.newChapters)
        assertEquals(listOf(bookId), repository.booksWithUpdates().map { it.bookId })

        // A further delta accumulates on top of the unseen count.
        repository.recordUpdateCheck(bookId, total = 105, newChapters = 5, hash = "h2", at = 3L)
        assertEquals(8, repository.book(bookId)?.newChapters)

        repository.markUpdatesSeen(bookId, at = 4L)
        assertEquals(0, repository.book(bookId)?.newChapters)
    }

    @Test
    fun `add to library is idempotent and preserves added timestamp`() = runTest {
        val book = book("https://site/book/add", inLibrary = false, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId

        repository.addToLibrary(bookId, at = 100L)
        val first = repository.book(bookId)!!
        assertEquals(true, first.inLibrary)
        assertEquals(100L, first.addedToLibraryAt)

        repository.addToLibrary(bookId, at = 200L)
        val second = repository.book(bookId)!!
        assertEquals(100L, second.addedToLibraryAt)
    }

    @Test
    fun `sessions aggregate by kind and delete book cascades`() = runTest {
        val book = book("https://site/book/cascade", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val chapterId = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://site/ch/1", "One")),
            at = 1L,
        ).single().chapterId
        repository.saveChapterBody(ChapterBodyEntity(chapterId, "text", "/tmp/x", 10, "sha", 1L))
        repository.saveDownloadTask(
            DownloadTaskEntity(
                bookId = bookId,
                chapterId = chapterId,
                kind = "text",
                state = "done",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        repository.saveProgress(
            ProgressEntity(bookId, chapterId, 0, 0, null, 0, false, 1, 1L),
        )
        repository.saveSession(
            ReadingSessionEntity(
                bookId = bookId,
                chapterId = chapterId,
                startedAt = 100L,
                endedAt = 160L,
                durationMs = 60_000L,
                kind = "reading",
            ),
        )
        repository.saveSession(
            ReadingSessionEntity(
                bookId = bookId,
                chapterId = chapterId,
                startedAt = 200L,
                endedAt = 230L,
                durationMs = 30_000L,
                kind = "tts",
            ),
        )

        assertEquals(60_000L, repository.durationBetween(0L, 1_000L, "reading"))
        assertEquals(30_000L, repository.durationBetween(0L, 1_000L, "tts"))

        repository.deleteBook(bookId)

        assertNull(repository.book(bookId))
        assertTrue(repository.chapters(bookId).isEmpty())
        assertNull(repository.progress(bookId))
        assertTrue(repository.downloadTasks().isEmpty())
        assertTrue(repository.sessions(bookId, 10).isEmpty())
        assertNull(repository.chapterBody(chapterId))
    }

    @Test
    fun `duration for book sums only that book and kind`() = runTest {
        repository.saveBook(book("https://site/book/a", inLibrary = true, lastReadAt = null))
        repository.saveBook(book("https://site/book/b", inLibrary = true, lastReadAt = null))
        val first = repository.book("https://site/book/a")!!.bookId
        val second = repository.book("https://site/book/b")!!.bookId

        suspend fun session(bookId: Long, kind: String, ms: Long) {
            repository.saveSession(
                ReadingSessionEntity(
                    bookId = bookId,
                    chapterId = null,
                    startedAt = 1L,
                    endedAt = null,
                    durationMs = ms,
                    kind = kind,
                ),
            )
        }
        session(first, "reading", 60_000L)
        session(first, "reading", 120_000L)
        session(first, "tts", 30_000L)
        session(second, "reading", 600_000L)
        session(second, "tts", 240_000L)

        assertEquals(180_000L, repository.durationForBook(first, "reading"))
        assertEquals(30_000L, repository.durationForBook(first, "tts"))
        assertEquals(600_000L, repository.durationForBook(second, "reading"))
        assertEquals(240_000L, repository.durationForBook(second, "tts"))
        assertEquals(0L, repository.durationForBook(first, "unknown"))
    }

    @Test
    fun `shelves are created and renamed`() = runTest {
        repository.saveShelf(ShelfEntity(id = "wuxia", label = "Wuxia", position = 2))
        assertEquals(
            "Wuxia",
            repository.shelves().first { it.id == "wuxia" }.label,
        )

        repository.saveShelf(ShelfEntity(id = "wuxia", label = "Wuxia stash", position = 2))
        assertEquals(
            "Wuxia stash",
            repository.shelves().first { it.id == "wuxia" }.label,
        )
    }

    @Test
    fun `deleting a shelf moves its books to reading`() = runTest {
        repository.saveShelf(ShelfEntity(id = "wuxia", label = "Wuxia", position = 2))
        val shelved = book("https://site/book/7", inLibrary = true, lastReadAt = null)
            .copy(shelfId = "wuxia")
        repository.saveBook(shelved)

        repository.deleteShelf("wuxia")

        assertFalse(repository.shelves().any { it.id == "wuxia" })
        assertEquals("reading", repository.book(shelved.bookUrl)?.shelfId)
    }

    @Test
    fun `reorder pins system shelves and rewrites positions`() = runTest {
        repository.saveShelf(ShelfEntity(id = "alpha", label = "Alpha", position = 7))
        repository.saveShelf(ShelfEntity(id = "zeta", label = "Zeta", position = 8))
        val current = repository.shelves().associateBy { it.id }

        repository.reorderShelves(
            listOf(
                current.getValue("zeta"),
                current.getValue("alpha"),
                current.getValue("reading"),
                current.getValue("completed"),
            ),
        )

        val shelves = repository.shelves()
        assertEquals(listOf("reading", "completed", "zeta", "alpha"), shelves.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), shelves.map { it.position })
    }


    @Test
    fun `library urls are resolved in one batch and exclude history rows`() = runTest {
        val inLibrary = book("https://site/in-library", inLibrary = true, lastReadAt = null)
        val historyOnly = book("https://site/history-only", inLibrary = false, lastReadAt = 1L)
        repository.saveBook(inLibrary)
        repository.saveBook(historyOnly)

        val found = repository.libraryUrls(
            listOf(inLibrary.url, historyOnly.url, "https://site/missing", "  "),
        )

        assertEquals(setOf(inLibrary.url), found)
        assertTrue(repository.libraryUrls(emptyList()).isEmpty())
    }

    @Test
    fun `local book files are found by sha and cascade with the book`() = runTest {
        val book = book("local://book/one", inLibrary = true, lastReadAt = null)
            .copy(installId = "local-books")
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val file = LocalBookFileEntity(
            bookId = bookId,
            uri = "content://book",
            displayName = "book.epub",
            format = "EPUB",
            mime = "application/epub+zip",
            sizeBytes = 10,
            sha256 = "sha-one",
            copyPath = "local_books/local-books/sha-one.epub",
            importedAt = 1L,
        )
        repository.saveLocalBookFile(file)

        assertEquals(file, repository.localBookFileBySha("sha-one"))
        assertEquals(file, repository.localBookFileByUri("content://book"))
        assertNull(repository.localBookFileByUri("content://other"))
        assertNull(repository.localBookFileBySha("sha-missing"))
        assertEquals(1, repository.libraryBookCount("local-books"))
        assertEquals(0, repository.libraryBookCount("local-manga"))

        repository.deleteBook(bookId)

        assertNull(repository.localBookFileBySha("sha-one"))
        assertEquals(0, repository.libraryBookCount("local-books"))
    }

    @Test
    fun `v4 metadata tags covers highlights and sessions round-trip`() = runTest {
        val book = book("https://site/book/v4", inLibrary = true, lastReadAt = null).copy(
            description = "A synopsis",
            originLanguage = "en",
            authorRaw = "Last, First",
            authorsJson = "[]",
            ratingRaw = "RANK 216",
            statusRaw = "Ongoing",
            statusNormalized = "ONGOING",
            extrasJson = "{\"tags\":\"Magic, Towers\"}",
            addedToLibraryAt = 50L,
        )
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val chapterId = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://site/ch/1", "One")),
            at = 10L,
        ).single().chapterId

        repository.saveBookCover(
            BookCoverEntity(bookId = bookId, remoteUrl = "https://cdn/c.jpg"),
        )
        assertEquals("https://cdn/c.jpg", repository.bookCover(bookId)?.remoteUrl)

        repository.saveTag(TagEntity(name = "Magic", normalizedName = "magic", kind = "TAG", createdAt = 1L))
        val tagId = repository.tagByNormalized("magic")!!.tagId
        repository.saveBookTag(BookTagEntity(bookId = bookId, tagId = tagId))
        assertEquals(listOf("Magic"), repository.tagsForBook(bookId).map { it.name })

        repository.saveHighlight(
            HighlightEntity(
                bookId = bookId,
                chapterId = chapterId,
                blockIndex = 0,
                startOffset = 0,
                endOffset = 5,
                textKind = "ORIGINAL",
                colorArgb = 0xFFFF6D00,
                sourceHash = "hash",
                parserVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        assertEquals(1, repository.highlights(bookId).size)

        repository.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = bookId,
                uri = "content://book",
                displayName = "book.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 10,
                sha256 = "sha",
                importedAt = 1L,
            ),
        )
        assertNotNull(repository.localBookFile(bookId))

        val sessionKey = "session-1"
        repository.saveSession(
            ReadingSessionEntity(
                bookId = bookId,
                chapterId = chapterId,
                startedAt = 1L,
                endedAt = 2L,
                durationMs = 1_000L,
                kind = "reading",
                sessionKey = sessionKey,
            ),
        )
        repository.saveSession(
            ReadingSessionEntity(
                bookId = bookId,
                chapterId = chapterId,
                startedAt = 1L,
                endedAt = 2L,
                durationMs = 1_000L,
                kind = "reading",
                sessionKey = sessionKey,
            ),
        )
        assertEquals(1, repository.sessions(bookId, 10).size)

        repository.deleteBook(bookId)

        assertNull(repository.bookCover(bookId))
        assertTrue(repository.tagsForBook(bookId).isEmpty())
        assertTrue(repository.highlights(bookId).isEmpty())
        assertNull(repository.localBookFile(bookId))
        assertTrue(repository.sessions(bookId, 10).isEmpty())
    }

    @Test
    fun `chapter translations round trip and upsert by cache key`() = runTest {
        val book = book("https://site/book/translation", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val chapterId = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://site/ch/1", "One")),
            at = 1L,
        ).single().chapterId

        val firstId = repository.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = chapterId,
                target = "ru",
                provider = "GEMINI",
                model = "m",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                status = "COMPLETE",
                updatedAt = 1L,
            ),
        )
        repository.saveTranslationBlock(
            TranslationBlockEntity(firstId, 0, "source-hash", "translated"),
        )

        val stored = repository.chapterTranslation(chapterId, "", "ru", "GEMINI", "p", "s", "v")
        assertEquals(firstId, stored?.translationId)
        assertEquals("translated", repository.translationBlocks(firstId).single().translatedText)

        val secondId = repository.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = chapterId,
                target = "ru",
                provider = "GEMINI",
                model = "m2",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                status = "COMPLETE",
                updatedAt = 2L,
            ),
        )
        assertEquals(firstId, secondId)
    }

    @Test
    fun `multi-language translations coexist per source and target`() = runTest {
        val book = book("https://site/book/languages", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        val chapterId = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://site/ch/languages", "One")),
            at = 1L,
        ).single().chapterId

        repository.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = chapterId,
                source = "en",
                target = "ru",
                provider = "GEMINI",
                model = null,
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                status = "COMPLETE",
                updatedAt = 1L,
            ),
        )
        repository.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = chapterId,
                source = "de",
                target = "ru",
                provider = "GEMINI",
                model = null,
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                status = "COMPLETE",
                updatedAt = 2L,
            ),
        )

        assertNotNull(
            repository.chapterTranslation(chapterId, "en", "ru", "GEMINI", "p", "s", "v"),
        )
        assertNotNull(
            repository.chapterTranslation(chapterId, "de", "ru", "GEMINI", "p", "s", "v"),
        )

        repository.saveBookTranslation(
            BookTranslationEntity(
                bookId = bookId,
                source = "en",
                target = "ru",
                provider = "GEMINI",
                model = null,
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                title = "English source",
                status = "COMPLETE",
                updatedAt = 1L,
            ),
        )
        repository.saveBookTranslation(
            BookTranslationEntity(
                bookId = bookId,
                source = "de",
                target = "ru",
                provider = "GEMINI",
                model = null,
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                title = "German source",
                status = "COMPLETE",
                updatedAt = 2L,
            ),
        )

        assertEquals(
            "English source",
            repository.bookTranslation(bookId, "en", "ru", "GEMINI", "p", "v")?.title,
        )
        assertEquals(
            "German source",
            repository.bookTranslation(bookId, "de", "ru", "GEMINI", "p", "v")?.title,
        )
        assertEquals(
            mapOf(bookId to "English source"),
            repository.bookTranslations(listOf(bookId), "en", "ru", "GEMINI", "p", "v")
                .mapValues { it.value.title },
        )
    }

    @Test
    fun `book translation same key updates the row and keeps chapter titles`() = runTest {
        val book = book("https://site/book/book-translation", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId

        val firstId = repository.saveBookTranslation(
            BookTranslationEntity(
                bookId = bookId,
                source = "en",
                target = "ru",
                provider = "GEMINI",
                model = null,
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                title = "Первый",
                description = "Описание",
                chapterTitlesJson = "{\"1\":\"Первая\"}",
                status = "COMPLETE",
                updatedAt = 1L,
            ),
        )
        val secondId = repository.saveBookTranslation(
            BookTranslationEntity(
                bookId = bookId,
                source = "en",
                target = "ru",
                provider = "GEMINI",
                model = null,
                promptHash = "p",
                sourceHash = "s2",
                pipelineVersion = "v",
                title = "Второй",
                description = "Описание",
                chapterTitlesJson = "{\"1\":\"Первая\"}",
                status = "COMPLETE",
                updatedAt = 2L,
            ),
        )

        assertEquals(firstId, secondId)
        val row = repository.bookTranslation(bookId, "en", "ru", "GEMINI", "p", "v")
        assertEquals("Второй", row?.title)
        assertEquals("{\"1\":\"Первая\"}", row?.chapterTitlesJson)
        assertEquals(
            1,
            repository.bookTranslations(listOf(bookId), "en", "ru", "GEMINI", "p", "v").size,
        )
    }

    @Test
    fun `fts finds title author tags description and translated title`() = runTest {
        val mage = book("https://site/book/fts-mage", inLibrary = true, lastReadAt = null).copy(
            title = "Silver Mage",
            authorRaw = "Ivan Petrov",
            description = "Keeper of the lost flame",
        )
        val other = book("https://site/book/fts-other", inLibrary = true, lastReadAt = null).copy(
            title = "Iron Lantern",
            authorRaw = "Wuxia Archive",
            description = "A different story",
        )
        repository.saveBook(mage)
        repository.saveBook(other)
        val mageId = repository.book(mage.bookUrl)!!.bookId
        repository.saveTag(
            TagEntity(name = "Cultivation", normalizedName = "cultivation", kind = "GENRE", createdAt = 1L),
        )
        repository.saveBookTag(
            BookTagEntity(bookId = mageId, tagId = repository.tagByNormalized("cultivation")!!.tagId),
        )
        repository.saveBookTranslation(
            BookTranslationEntity(
                bookId = mageId,
                target = "ru",
                provider = "GEMINI",
                model = "m",
                promptHash = "p",
                sourceHash = "s",
                pipelineVersion = "v",
                title = "Серебряный маг",
                status = "COMPLETE",
                updatedAt = 10L,
            ),
        )

        assertEquals(listOf(mageId), repository.searchBooks("silver").map { it.bookId })
        assertEquals(listOf(mageId), repository.searchBooks("petrov").map { it.bookId })
        assertEquals(listOf(mageId), repository.searchBooks("cultivation").map { it.bookId })
        assertEquals(listOf(mageId), repository.searchBooks("flame").map { it.bookId })
        assertEquals(listOf(mageId), repository.searchBooks("Серебряный").map { it.bookId })
        assertEquals(
            listOf(repository.book(other.bookUrl)!!.bookId),
            repository.searchBooks("iron").map { it.bookId },
        )
    }

    @Test
    fun `fts sanitizes quotes operators and injection attempts`() = runTest {
        val book = book("https://site/book/fts-special", inLibrary = true, lastReadAt = null)
            .copy(title = "C# and quotes")
        repository.saveBook(book)

        val queries = listOf(
            "\"",
            "\"\"",
            "*",
            "OR",
            "AND",
            "NOT",
            "NEAR(foo bar)",
            "title:foo",
            "-",
            "(",
            ")",
            "\"unclosed",
            "'; DROP TABLE books; --",
            "100%",
            "\\",
            "a | b",
        )
        for (query in queries) {
            // Any result is fine; the point is that bad syntax never throws.
            repository.searchBooks(query)
        }

        // The injection attempt must not have touched the real tables.
        assertTrue(repository.libraryBooks().any { it.bookUrl == book.bookUrl })
        assertEquals(listOf(book.bookUrl), repository.searchBooks("quotes").map { it.bookUrl })
    }

    @Test
    fun `tag edits and book delete leave no stale fts rows`() = runTest {
        val book = book("https://site/book/fts-stale", inLibrary = true, lastReadAt = null)
            .copy(title = "Signal Lost")
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId
        repository.saveTag(
            TagEntity(name = "Obsolete", normalizedName = "obsolete", kind = "TAG", createdAt = 1L),
        )
        repository.saveBookTag(
            BookTagEntity(bookId = bookId, tagId = repository.tagByNormalized("obsolete")!!.tagId),
        )
        assertEquals(listOf(bookId), repository.searchBooks("obsolete").map { it.bookId })

        // Renaming the tag itself must refresh the indexed text too.
        val obsolete = repository.tagByNormalized("obsolete")!!
        repository.saveTag(obsolete.copy(name = "Renamed", normalizedName = "renamed"))
        assertEquals(listOf(bookId), repository.searchBooks("renamed").map { it.bookId })
        assertTrue(repository.searchBooks("obsolete").isEmpty())

        repository.deleteBookTags(bookId)
        assertTrue(repository.searchBooks("renamed").isEmpty())

        repository.saveTag(
            TagEntity(name = "Fresh", normalizedName = "fresh", kind = "TAG", createdAt = 2L),
        )
        repository.saveBookTag(
            BookTagEntity(bookId = bookId, tagId = repository.tagByNormalized("fresh")!!.tagId),
        )
        assertEquals(listOf(bookId), repository.searchBooks("fresh").map { it.bookId })
        assertTrue(repository.searchBooks("obsolete").isEmpty())

        repository.deleteBook(bookId)
        assertTrue(repository.searchBooks("signal").isEmpty())
        assertTrue(repository.searchBooks("fresh").isEmpty())
    }

    @Test
    fun `fts pages results and blank query falls back to the library`() = runTest {
        val ids = (1..3).map { index ->
            val row = book("https://site/book/fts-page-$index", inLibrary = true, lastReadAt = 100L - index)
                .copy(title = "Paged Book")
            repository.saveBook(row)
            repository.book(row.bookUrl)!!.bookId
        }
        val hidden = book("https://site/book/fts-page-hidden", inLibrary = false, lastReadAt = null)
            .copy(title = "Paged Secret")
        repository.saveBook(hidden)
        val hiddenId = repository.book(hidden.bookUrl)!!.bookId

        val firstPage = repository.searchBooks("paged", limit = 2, offset = 0)
        val secondPage = repository.searchBooks("paged", limit = 2, offset = 2)
        assertEquals(ids.sorted().take(2), firstPage.map { it.bookId })
        assertEquals(ids.sorted().drop(2), secondPage.map { it.bookId })

        // Library-only by default; an explicit opt-out sees the hidden row.
        assertFalse(repository.searchBooks("secret").any { it.bookId == hiddenId })
        assertEquals(
            listOf(hiddenId),
            repository.searchBooks("secret", libraryOnly = false).map { it.bookId },
        )

        // Blank / punctuation-only queries behave like the normal library page.
        assertEquals(3, repository.searchBooks("   ", limit = 10).size)
        assertEquals(2, repository.searchBooks("\"", limit = 2).size)
        assertEquals(ids, repository.searchBooks("", limit = 10).map { it.bookId })
    }

    @Test
    fun `rebuild search index restores every book`() = runTest {
        val book = book("https://site/book/fts-rebuild", inLibrary = true, lastReadAt = null)
            .copy(title = "Rebuild Me", description = "Regenerated from books and tags")
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId

        db.libraryDao().clearBookSearch()
        assertTrue(repository.searchBooks("rebuild").isEmpty())

        repository.rebuildSearchIndex()

        assertEquals(listOf(bookId), repository.searchBooks("rebuild").map { it.bookId })
        assertEquals(listOf(bookId), repository.searchBooks("regenerated").map { it.bookId })
    }

    @Test
    fun `storage usage sums bodies pages covers icons and local files`() = runTest {
        val remote = book("https://site/book/storage", inLibrary = true, lastReadAt = null)
        repository.saveBook(remote)
        val remoteId = repository.book(remote.bookUrl)!!.bookId
        val remoteChapter = repository.replaceChapters(
            remoteId,
            listOf(ChapterSeed(1, "https://site/storage/1", "One")),
            at = 1L,
        ).single().chapterId
        repository.saveChapterBody(
            ChapterBodyEntity(remoteChapter, "text", "/tmp/remote.html", 100, "sha1", 1L),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = remoteChapter,
                format = "images",
                pagePathsJson = "[\"/tmp/remote/1.jpg\"]",
                totalBytes = 200,
            ),
        )
        repository.saveBookCover(
            BookCoverEntity(
                bookId = remoteId,
                remoteUrl = "https://cdn/remote.jpg",
                localPath = "/tmp/remote-cover.jpg",
                bytes = 300,
            ),
        )
        repository.saveSourceIcon(
            SourceIconEntity(
                installId = "chikari",
                urlHash = "icon",
                localPath = "/tmp/icon.png",
                bytes = 400,
            ),
        )

        val novel = book("local://book/storage-novel", inLibrary = true, lastReadAt = null)
            .copy(installId = "local-books")
        repository.saveBook(novel)
        val novelId = repository.book(novel.bookUrl)!!.bookId
        val novelChapter = repository.replaceChapters(
            novelId,
            listOf(ChapterSeed(1, "local://book/storage-novel#chapter=1", "One")),
            at = 1L,
        ).single().chapterId
        repository.saveChapterBody(
            ChapterBodyEntity(novelChapter, "html", "local_books/one.html", 150, "sha2", 1L),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = novelChapter,
                format = "images",
                pagePathsJson = "[\"local_books/one/1.jpg\"]",
                totalBytes = 250,
            ),
        )
        repository.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = novelId,
                uri = "content://novel",
                displayName = "novel.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 500,
                sha256 = "novel-sha",
                copyPath = "local_books/local-books/novel.epub",
                importedAt = 1L,
            ),
        )
        repository.saveBookCover(
            BookCoverEntity(
                bookId = novelId,
                remoteUrl = null,
                localPath = "local_books/novel-cover.jpg",
                bytes = 50,
            ),
        )

        val manga = book("local://book/storage-manga", inLibrary = true, lastReadAt = null)
            .copy(installId = "local-manga")
        repository.saveBook(manga)
        val mangaId = repository.book(manga.bookUrl)!!.bookId
        repository.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = mangaId,
                uri = "content://manga",
                displayName = "manga.cbz",
                format = "CBZ",
                mime = "application/vnd.comicbook+zip",
                sizeBytes = 600,
                sha256 = "manga-sha",
                copyPath = "local_books/local-manga/manga.cbz",
                importedAt = 1L,
            ),
        )

        val usage = repository.storageUsage()

        assertEquals(250L, usage.chapterBodyBytes) // remote 100 + local 150
        assertEquals(450L, usage.chapterPageBytes) // remote 200 + local 250
        assertEquals(350L, usage.coverBytes) // remote 300 + imported cover 50
        assertEquals(400L, usage.sourceIconBytes)
        assertEquals(500L, usage.localNovelBytes)
        assertEquals(600L, usage.localMangaBytes)
        assertEquals(1100L, usage.localBookBytes)
        assertEquals(1, usage.clearableChapterBodyCount)
        assertEquals(1, usage.clearableChapterPageCount)
        assertEquals(3, usage.bookCount)
        assertEquals(2, usage.chapterCount)
        assertEquals(2, usage.coverCount)
        assertEquals(2, usage.localFileCount)
        assertEquals(2, usage.chapterBodyFiles.size)
        assertEquals(2, usage.chapterPageFiles.size)
        assertEquals(2, usage.coverFiles.size)
        assertEquals(1, usage.sourceIconFiles.size)
        assertEquals(1, usage.localNovelFiles.size)
        assertEquals(1, usage.localMangaFiles.size)

        // Clearing text/page cache removes the remote rows only.
        assertEquals(listOf("/tmp/remote.html"), repository.clearRemovableChapterBodies())
        assertNull(repository.chapterBody(remoteChapter))
        assertNotNull(repository.chapterBody(novelChapter))
        assertEquals(listOf("[\"/tmp/remote/1.jpg\"]"), repository.clearRemovableChapterPages())
        assertNull(repository.chapterPage(remoteChapter))
        assertNotNull(repository.chapterPage(novelChapter))

        // Clearing images removes the remote cover and icons, keeps the
        // imported novel cover.
        assertEquals(
            listOf("/tmp/remote-cover.jpg", "/tmp/icon.png"),
            repository.clearImageCache(),
        )
        assertNull(repository.bookCover(remoteId))
        assertNotNull(repository.bookCover(novelId))
        assertNull(repository.sourceIcon("chikari", "", "icon"))
    }

    @Test
    fun `bulk delete removes rows fts and returns collected file paths`() = runTest {
        val first = book("https://site/book/bulk-delete-1", inLibrary = true, lastReadAt = null)
            .copy(title = "Bulk Signal One")
        val second = book("https://site/book/bulk-delete-2", inLibrary = true, lastReadAt = null)
            .copy(title = "Bulk Signal Two")
        repository.saveBook(first)
        repository.saveBook(second)
        val firstId = repository.book(first.bookUrl)!!.bookId
        val secondId = repository.book(second.bookUrl)!!.bookId

        repository.replaceChapters(
            firstId,
            listOf(ChapterSeed(1, "${first.bookUrl}/1", "One")),
            at = 1L,
        )
        val chapterId = repository.chapterByIndex(firstId, 1)!!.chapterId
        repository.saveChapterBody(
            ChapterBodyEntity(chapterId, "html", "local_books/bulk/1.html", 10, "sha-body", 1L),
        )
        repository.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapterId,
                format = "images",
                pagePathsJson = "[\"local_books/bulk/1.jpg\"]",
            ),
        )
        repository.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = firstId,
                uri = "content://bulk-one",
                displayName = "bulk-one.epub",
                format = "EPUB",
                mime = "application/epub+zip",
                sizeBytes = 10,
                sha256 = "sha-bulk-one",
                copyPath = "local_books/local-books/bulk-one.epub",
                importedAt = 1L,
            ),
        )
        repository.saveBookCover(
            BookCoverEntity(
                bookId = firstId,
                remoteUrl = null,
                localPath = "covers/$firstId/cover",
            ),
        )

        val missing = "https://site/book/bulk-missing"
        val result = repository.deleteBooks(listOf(first.bookUrl, second.bookUrl, missing))

        assertEquals(setOf(first.bookUrl, second.bookUrl), result.deleted)
        assertEquals(setOf(missing), result.missing)
        assertNull(repository.book(first.bookUrl))
        assertNull(repository.book(second.bookUrl))
        assertTrue(repository.chapters(firstId).isEmpty())
        assertNull(repository.chapterBody(chapterId))
        assertNull(repository.localBookFile(firstId))
        assertNull(repository.bookCover(firstId))
        assertTrue(repository.searchBooks("bulk signal").isEmpty())

        val files = result.files.first { it.bookId == firstId }
        assertEquals("covers/$firstId/cover", files.coverPath)
        assertEquals("local_books/local-books/bulk-one.epub", files.copyPath)
        assertFalse(files.folderCopy)
        assertEquals(listOf("local_books/bulk/1.html"), files.bodyPaths)
        assertEquals(listOf("[\"local_books/bulk/1.jpg\"]"), files.pagePathsJson)
    }

    @Test
    fun `bulk move updates every book and preserves system shelves`() = runTest {
        repository.saveShelf(ShelfEntity(id = "wuxia", label = "Wuxia", position = 2))
        val first = book("https://site/book/bulk-move-1", inLibrary = true, lastReadAt = null)
        val second = book("https://site/book/bulk-move-2", inLibrary = true, lastReadAt = null)
        repository.saveBook(first)
        repository.saveBook(second)

        val missing = "https://site/book/bulk-move-missing"
        val result = repository.moveBooks(
            listOf(first.bookUrl, second.bookUrl, missing),
            "completed",
        )

        assertEquals(setOf(first.bookUrl, second.bookUrl), result.succeeded)
        assertEquals(setOf(missing), result.missing)
        assertEquals("completed", repository.book(first.bookUrl)?.shelfId)
        assertEquals("completed", repository.book(second.bookUrl)?.shelfId)
        assertEquals(
            listOf("reading", "completed", "wuxia"),
            repository.shelves().map { it.id },
        )

        // An unknown target is rejected instead of writing an orphan shelf.
        val rejected = repository.moveBooks(listOf(first.bookUrl), "no-such-shelf")
        assertTrue(rejected.succeeded.isEmpty())
        assertEquals(setOf(first.bookUrl), rejected.missing)
        assertEquals("completed", repository.book(first.bookUrl)?.shelfId)
    }

    @Test
    fun `bulk mark read and unread is one statement over chapters`() = runTest {
        val first = book("https://site/book/bulk-read-1", inLibrary = true, lastReadAt = null)
        val second = book("https://site/book/bulk-read-2", inLibrary = true, lastReadAt = null)
        repository.saveBook(first)
        repository.saveBook(second)
        val firstId = repository.book(first.bookUrl)!!.bookId
        val secondId = repository.book(second.bookUrl)!!.bookId
        repository.replaceChapters(
            firstId,
            listOf(
                ChapterSeed(1, "${first.bookUrl}/1", "One"),
                ChapterSeed(2, "${first.bookUrl}/2", "Two"),
            ),
            at = 1L,
        )
        repository.replaceChapters(
            secondId,
            listOf(ChapterSeed(1, "${second.bookUrl}/1", "One")),
            at = 1L,
        )

        val read = repository.markBooksRead(
            listOf(first.bookUrl, second.bookUrl),
            read = true,
            at = 10L,
        )
        assertEquals(setOf(first.bookUrl, second.bookUrl), read.succeeded)
        assertEquals(listOf(1, 2), repository.readChapterIndexes(firstId))
        assertEquals(listOf(1), repository.readChapterIndexes(secondId))

        val unread = repository.markBooksRead(listOf(first.bookUrl), read = false, at = 20L)
        assertEquals(setOf(first.bookUrl), unread.succeeded)
        assertTrue(repository.readChapterIndexes(firstId).isEmpty())
        assertEquals(listOf(1), repository.readChapterIndexes(secondId))
    }

    @Test
    fun `repeated remove is idempotent and keeps fts clean`() = runTest {
        val book = book("https://site/book/idempotent-remove", inLibrary = true, lastReadAt = null)
            .copy(title = "Idempotent Signal")
        repository.saveBook(book)

        val first = repository.deleteBookWithFiles(book.bookUrl)
        assertEquals(setOf(book.bookUrl), first.deleted)
        assertTrue(first.missing.isEmpty())

        val second = repository.deleteBookWithFiles(book.bookUrl)
        assertTrue(second.deleted.isEmpty())
        assertEquals(setOf(book.bookUrl), second.missing)
        assertTrue(second.files.isEmpty())
        assertTrue(repository.searchBooks("idempotent signal").isEmpty())
    }

    @Test
    fun `replace book tags batches links and indexes all tags at once`() = runTest {
        val book = book("https://site/book/tag-batch", inLibrary = true, lastReadAt = null)
        repository.saveBook(book)
        val bookId = repository.book(book.bookUrl)!!.bookId

        repository.replaceBookTags(
            bookId,
            listOf(
                TagEntity(name = "Wuxia", normalizedName = "wuxia", kind = "GENRE", createdAt = 1L),
                TagEntity(name = "Cultivation", normalizedName = "cultivation", kind = "TAG", createdAt = 1L),
            ),
        )

        assertEquals(listOf("Cultivation", "Wuxia"), repository.tagsForBook(bookId).map { it.name }.sorted())
        assertEquals(listOf(book.bookUrl), repository.searchBooks("wuxia").map { it.bookUrl })
        assertEquals(listOf(book.bookUrl), repository.searchBooks("cultivation").map { it.bookUrl })

        repository.replaceBookTags(
            bookId,
            listOf(TagEntity(name = "Fresh", normalizedName = "fresh", kind = "TAG", createdAt = 2L)),
        )
        assertEquals(listOf("Fresh"), repository.tagsForBook(bookId).map { it.name })
        assertTrue(repository.searchBooks("wuxia").isEmpty())
        assertTrue(repository.searchBooks("cultivation").isEmpty())
        assertEquals(listOf(book.bookUrl), repository.searchBooks("fresh").map { it.bookUrl })
    }

    @Test
    fun `membership row becomes visible before chapter and tag enrichment`() = runTest {
        val book = book("https://site/book/fast-membership", inLibrary = false, lastReadAt = null)
        repository.saveBook(book)
        val id = repository.book(book.bookUrl)!!.bookId

        // The add path is one UPDATE; chapters/tags are still empty here.
        repository.addToLibrary(id, at = 10L)

        assertTrue(repository.isInLibrary(book.bookUrl))
        assertEquals(listOf(book.bookUrl), repository.libraryBooks().map { it.bookUrl })
        assertTrue(repository.chapters(id).isEmpty())
        assertTrue(repository.tagsForBook(id).isEmpty())
    }

    @Test
    fun `batch read stats tags and covers across 120 books`() = runTest {
        val ids = mutableListOf<Long>()
        for (index in 1..120) {
            val row = book(
                url = "https://site/book/batch-$index",
                inLibrary = true,
                lastReadAt = (120 - index).toLong(),
            )
            repository.saveBook(row)
            val id = repository.book(row.bookUrl)!!.bookId
            ids += id
            repository.replaceChapters(
                id,
                (1..5).map { chapter ->
                    ChapterSeed(chapter, "${row.bookUrl}/$chapter", "Chapter $chapter")
                },
                at = 1L,
            )
            val read = if (index % 10 == 0) 5 else index % 4
            if (read > 0) repository.markChapterRangeRead(id, 1, read, true, at = 10L)
            repository.replaceBookTags(
                id,
                listOf(
                    TagEntity(
                        name = "tag-$index",
                        normalizedName = "tag-$index",
                        kind = "GENRE",
                        createdAt = 1L,
                    ),
                    TagEntity(
                        name = "shared",
                        normalizedName = "shared",
                        kind = "TAG",
                        createdAt = 1L,
                    ),
                ),
            )
            if (index == 1) {
                repository.saveBookCover(
                    BookCoverEntity(bookId = id, remoteUrl = "https://cdn.test/cover.jpg"),
                )
            }
        }

        val stats = repository.readChapterStats()
        // Books with zero read chapters have no GROUP BY row.
        assertEquals(96, stats.size)
        assertEquals(1, stats[ids.first()]?.readCount)
        assertEquals(1, stats[ids.first()]?.maxReadIndex)
        assertEquals(2, stats[ids[1]]?.readCount)
        assertNull(stats[ids[3]])
        assertEquals(5, stats[ids[9]]?.readCount)

        val counts = repository.readChapterCounts()
        assertEquals(1, counts[ids.first()])
        assertEquals(5, counts[ids[9]])
        assertNull(counts[ids[3]])

        // Same aggregation, restricted to the requested ids (dashboard).
        val subset = repository.readChapterStatsFor(
            listOf(ids.first(), ids[1], ids[3], ids[9]),
        )
        assertEquals(3, subset.size)
        assertEquals(stats[ids.first()], subset[ids.first()])
        assertEquals(stats[ids[1]], subset[ids[1]])
        assertEquals(stats[ids[9]], subset[ids[9]])
        assertNull(subset[ids[3]])
        assertTrue(repository.readChapterStatsFor(emptyList()).isEmpty())

        val tags = repository.tagsByBook()
        assertEquals(setOf("tag-1", "shared"), tags[ids.first()]?.toSet())
        assertEquals(setOf("tag-120", "shared"), tags[ids.last()]?.toSet())

        val covers = repository.bookCovers()
        assertEquals("https://cdn.test/cover.jpg", covers[ids.first()]?.remoteUrl)
        assertEquals(1, covers.size)

        // Restricted reads return the same rows as the full-table maps.
        val requested = listOf(ids.first(), ids.last(), ids[3])
        assertEquals(
            tags.filterKeys { it in requested },
            repository.tagsByBookFor(requested),
        )
        assertEquals(
            covers.filterKeys { it in requested },
            repository.bookCoversFor(requested),
        )

        val nextUp = repository.unfinishedLibraryBooks(4)
        assertEquals(4, nextUp.size)
        assertTrue(nextUp.none { it.bookId == ids[9] })
        assertEquals(ids.take(4), nextUp.map { it.bookId })
    }

    @Test
    fun `video progress upserts and marks the chapter read at 95 percent`() = runTest {
        val bookId = repository.saveBook(book("https://video.test/book", true, null))
        val chapterId = repository.replaceChapters(
            bookId,
            listOf(ChapterSeed(1, "https://video.test/book/1", "One")),
            at = 1L,
        ).single().chapterId

        repository.saveVideoProgress(bookId, chapterId, 94_999, 100_000, at = 10L)
        assertFalse(repository.chapters(bookId).single().read)

        repository.saveVideoProgress(bookId, chapterId, 95_000, 100_000, at = 20L)
        val saved = assertNotNull(repository.videoProgress(bookId, chapterId))
        assertEquals(95_000, saved.positionMs)
        assertEquals(20L, saved.updatedAt)
        assertTrue(repository.chapters(bookId).single().read)

        repository.clearVideoProgress(bookId, chapterId)
        assertNull(repository.videoProgress(bookId, chapterId))
    }

    private fun book(url: String, inLibrary: Boolean, lastReadAt: Long?) = BookEntity(
        installId = "chikari",
        url = url,
        title = "Title",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 4.5,
        shelfId = "reading",
        totalChapters = 100,
        inLibrary = inLibrary,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = lastReadAt,
        lastChapterId = null,
        lastChapterIndex = lastReadAt?.toInt(),
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )
}
