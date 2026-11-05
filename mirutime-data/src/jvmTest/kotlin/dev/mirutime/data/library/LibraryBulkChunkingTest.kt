package dev.mirutime.data.library

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.lang.reflect.Proxy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

// Bulk URL/id operations must stay below SQLite's 999 bind limit, report what
// actually committed per chunk, and leave reconciling idempotent on retry.
class LibraryBulkChunkingTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var dao: LibraryDao

    @BeforeTest
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<LibraryDatabase>(
            factory = LibraryDatabaseConstructor::initialize,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        dao = db.libraryDao()
        repository = LibraryRepository(dao)
        repository.ensureDefaultShelves()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `url reads and title overlays chunk at 900 and keep their order`() = runTest {
        val recording = RecordingDao()
        val repository = LibraryRepository(recording.proxy)
        val urls = urls(1801)
        val ids = (1L..1801L).toList()

        val library = repository.libraryUrls(urls)
        assertEquals(urls, library.toList())
        assertEquals(listOf(900, 900, 1), recording.chunks("libraryUrls"))

        val idsByUrl = repository.bookIdsByUrls(urls)
        assertEquals(1801, idsByUrl.size)
        assertEquals(urls.first(), idsByUrl.keys.first())
        assertEquals(urls.last(), idsByUrl.keys.last())
        assertEquals(listOf(900, 900, 1), recording.chunks("booksByUrls"))

        val translations = repository.bookTranslations(ids, "en", "ru", "GEMINI", "p", "v")
        assertEquals(1801, translations.size)
        assertEquals("new-1", translations[1L]?.title)
        assertEquals("new-1801", translations[1801L]?.title)
        assertEquals(listOf(900, 900, 1), recording.chunks("bookTranslations"))

        assertTrue(repository.libraryUrls(emptyList()).isEmpty())
        assertTrue(repository.bookIdsByUrls(emptyList()).isEmpty())
        assertTrue(repository.bookTranslations(emptyList(), "en", "ru", "GEMINI", "p", "v").isEmpty())
    }

    @Test
    fun `bulk delete chunks at 900 and is idempotent on sqlite`() = runTest {
        val urls = urls(1801)
        urls.forEach { dao.upsertBook(book(it)) }

        val result = repository.deleteBooks(urls)
        assertEquals(urls.toSet(), result.deleted)
        assertEquals(urls.toList(), result.deleted.toList())
        assertTrue(result.missing.isEmpty())
        assertEquals(1801, result.files.size)
        assertEquals(0, dao.allBooks().size)

        val repeat = repository.deleteBooks(urls)
        assertTrue(repeat.deleted.isEmpty())
        assertEquals(urls.toSet(), repeat.missing)
        assertTrue(repeat.files.isEmpty())
    }

    @Test
    fun `cancelled bulk delete delivers committed chunk files before rethrow`() = runTest {
        val urls = urls(901)
        urls.forEach { dao.upsertBook(book(it)) }
        val delivered = mutableListOf<LibraryBookFiles>()
        val cancelling = InterceptingDao(dao) { call, _ ->
            if (call == 2) throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            LibraryRepository(cancelling).deleteBooks(urls) { delivered += it }
        }

        // The first 900 rows committed; their files are delivered twice
        // (per-chunk callback + NonCancellable flush), which is idempotent.
        assertEquals(900, delivered.map { it.bookId }.distinct().size)
        assertEquals(1, dao.allBooks().size)
        assertEquals(urls.last(), dao.allBooks().single().url)

        delivered.clear()
        val repeat = LibraryRepository(cancelling).deleteBooks(urls) { delivered += it }
        assertEquals(setOf(urls.last()), repeat.deleted)
        assertEquals(1, delivered.map { it.bookId }.distinct().size)
        assertTrue(dao.allBooks().isEmpty())
    }

    @Test
    fun `partial bulk delete delivers committed chunks and retries the rest`() = runTest {
        val urls = urls(1801)
        urls.forEach { dao.upsertBook(book(it)) }
        val delivered = mutableListOf<LibraryBookFiles>()
        val failing = InterceptingDao(dao) { call, _ ->
            if (call == 2) error("delete chunk failed")
        }

        val result = LibraryRepository(failing).deleteBooks(urls) { delivered += it }

        assertEquals(901, result.deleted.size)
        assertEquals(900, result.missing.size)
        assertEquals(901, delivered.map { it.bookId }.distinct().size)
        assertEquals(900, dao.allBooks().size)

        delivered.clear()
        val repeat = LibraryRepository(failing).deleteBooks(urls) { delivered += it }
        assertEquals(900, repeat.deleted.size)
        assertEquals(900, delivered.map { it.bookId }.distinct().size)
        assertTrue(dao.allBooks().isEmpty())
    }

    @Test
    fun `bulk move and mark read chunk at 900 and repeat cleanly`() = runTest {
        val urls = urls(1801)
        urls.forEach { dao.upsertBook(book(it)) }
        repository.saveShelf(ShelfEntity(id = "target", label = "Target", position = 2))

        val moved = repository.moveBooks(urls, "target")
        assertEquals(urls.toSet(), moved.succeeded)
        assertEquals(urls.toList(), moved.succeeded.toList())
        assertTrue(moved.missing.isEmpty())
        assertEquals(1801, dao.allBooks().count { it.shelfId == "target" })

        val read = repository.markBooksRead(urls, read = true, at = 10L)
        assertEquals(urls.toSet(), read.succeeded)
        assertTrue(read.missing.isEmpty())

        assertEquals(urls.toSet(), repository.moveBooks(urls, "target").succeeded)
        assertEquals(urls.toSet(), repository.markBooksRead(urls, read = true, at = 20L).succeeded)
    }

    @Test
    fun `bulk book translation read handles 1801 ids on sqlite`() = runTest {
        val ids = urls(1801).map { dao.upsertBook(book(it)) }
        ids.forEach { id ->
            dao.upsertBookTranslation(
                BookTranslationEntity(
                    bookId = id,
                    source = "en",
                    target = "ru",
                    provider = "GEMINI",
                    model = null,
                    promptHash = "p",
                    sourceHash = "s",
                    pipelineVersion = "v",
                    title = "new-$id",
                    status = "COMPLETE",
                    updatedAt = id,
                ),
            )
        }

        val translations = repository.bookTranslations(ids, "en", "ru", "GEMINI", "p", "v")

        assertEquals(1801, translations.size)
        assertEquals("new-${ids.first()}", translations[ids.first()]?.title)
        assertEquals("new-${ids.last()}", translations[ids.last()]?.title)
    }

    @Test
    fun `failed delete chunk is reported as missing instead of whole-batch success`() = runTest {
        val recording = RecordingDao().apply { failDeleteCall = 2 }
        val urls = urls(1801)

        val result = LibraryRepository(recording.proxy).deleteBooks(urls)

        assertEquals(listOf(900, 900, 1), recording.chunks("deleteBooksWithFiles"))
        assertEquals(901, result.deleted.size)
        assertEquals(900, result.missing.size)
        assertEquals(urls.take(900).toSet() + urls.last(), result.deleted)
        assertEquals(urls.subList(900, 1800).toSet(), result.missing)
    }

    @Test
    fun `failed move chunk is reported as missing instead of whole-batch success`() = runTest {
        val recording = RecordingDao().apply { failShelfCall = 2 }
        val urls = urls(1801)

        val result = LibraryRepository(recording.proxy).moveBooks(urls, "target")

        assertEquals(listOf(900, 900, 1), recording.chunks("setShelfForBooks"))
        assertEquals(901, result.succeeded.size)
        assertEquals(900, result.missing.size)
        assertEquals(urls.subList(900, 1800).toSet(), result.missing)
    }

    @Test
    fun `failed mark-read chunk is reported as missing instead of whole-batch success`() = runTest {
        val recording = RecordingDao().apply { failReadCall = 2 }
        val urls = urls(1801)

        val result = LibraryRepository(recording.proxy).markBooksRead(urls, read = true, at = 10L)

        assertEquals(listOf(900, 900, 1), recording.chunks("setChaptersReadForBookUrls"))
        assertEquals(901, result.succeeded.size)
        assertEquals(900, result.missing.size)
        assertEquals(urls.subList(900, 1800).toSet(), result.missing)
    }

    @Test
    fun `reconcile 1801 chapters keeps order ids and removes rows after all chunks`() = runTest {
        val url = "https://site/reconcile-1801"
        val bookId = repository.saveBook(book(url))
        val seeds = (1..1801).map { ChapterSeed(it, "$url/$it", "Chapter $it") }

        val saved = repository.replaceChapters(bookId, seeds, at = 1L)
        assertEquals(1801, saved.size)
        assertEquals((1..1801).toList(), saved.map { it.idx })
        assertEquals(1801, repository.book(bookId)?.totalChapters)

        val ids = saved.map { it.chapterId }
        val repeated = repository.replaceChapters(bookId, seeds, at = 2L)
        assertEquals(ids, repeated.map { it.chapterId })
        assertEquals((1..1801).toList(), repeated.map { it.idx })

        val kept = (1..900).map { ChapterSeed(it, "$url/$it", "Chapter $it") }
        val after = repository.replaceChapters(bookId, kept, at = 3L)
        assertEquals(900, after.size)
        assertEquals((1..900).toList(), after.map { it.idx })
        assertEquals(900, repository.book(bookId)?.totalChapters)

        val all = dao.allChapters(bookId)
        assertEquals(1801, all.size)
        assertEquals(901, all.count { it.removedAt != null })
        assertTrue(all.filter { it.idx > 900 }.all { it.removedAt != null })
    }

    @Test
    fun `reconcile chunk sizes and cancellation leave removals and total for retry`() = runTest {
        val existing = (1..2301).associate { index ->
            val url = "https://site/chunk/$index"
            url to ChapterEntity(
                chapterId = index.toLong(),
                bookId = 1,
                idx = index,
                url = url,
                title = "Chapter $index",
                updatedAt = 1L,
            )
        }
        val seeds = (1..1801).map { ChapterSeed(it, "https://site/chunk/$it", "Chapter $it") }
        val upserts = mutableListOf<Int>()
        val removals = mutableListOf<Int>()
        var totalUpdates = 0

        assertFailsWith<CancellationException> {
            reconcileChapterRows(
                bookId = 1,
                existing = existing,
                seeds = seeds,
                at = 2L,
                upsertChunk = { rows ->
                    upserts += rows.size
                    if (upserts.size == 2) throw CancellationException("cancelled")
                },
                markRemovedChunk = { ids, _ -> removals += ids.size },
                updateTotal = { totalUpdates++ },
                readChapters = { emptyList() },
            )
        }
        assertEquals(listOf(500, 500), upserts)
        assertTrue(removals.isEmpty())
        assertEquals(0, totalUpdates)

        val result = reconcileChapterRows(
            bookId = 1,
            existing = existing,
            seeds = seeds,
            at = 3L,
            upsertChunk = { rows -> upserts += rows.size },
            markRemovedChunk = { ids, _ -> removals += ids.size },
            updateTotal = { totalUpdates++ },
            readChapters = { emptyList() },
        )
        assertTrue(result.isEmpty())
        assertEquals(listOf(500, 500, 500, 500, 500, 301), upserts)
        assertEquals(listOf(500), removals)
        assertEquals(1, totalUpdates)
    }

    private fun urls(count: Int): List<String> =
        (1..count).map { "https://site/book/$it" }

    // Delegates the real Room DAO and lets a test cancel/fail one delete chunk
    // after the previous chunk transaction committed.
    private class InterceptingDao(
        private val delegate: LibraryDao,
        private val intercept: (call: Int, ids: List<Long>) -> Unit,
    ) : LibraryDao by delegate {
        private var calls = 0

        override suspend fun deleteBooksWithFiles(bookIds: List<Long>): List<BookFileRow> {
            calls++
            intercept(calls, bookIds)
            return delegate.deleteBooksWithFiles(bookIds)
        }
    }

    private class RecordingDao {
        private val recorded = linkedMapOf<String, MutableList<Int>>()
        private var deleteCalls = 0
        private var shelfCalls = 0
        private var readCalls = 0
        var failDeleteCall = -1
        var failShelfCall = -1
        var failReadCall = -1

        fun chunks(method: String): List<Int> = recorded[method].orEmpty()

        private fun record(method: String, args: Array<Any?>?): List<Any?> {
            val values = args!![0] as List<*>
            recorded.getOrPut(method) { mutableListOf() } += values.size
            return values
        }

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryUrls" -> record(method.name, args).filterIsInstance<String>()
                "booksByUrls" -> record(method.name, args)
                    .filterIsInstance<String>()
                    .map { url -> BookUrlRow(bookId = url.substringAfterLast('/').toLong(), url = url) }
                "deleteBooksWithFiles" -> {
                    val ids = record(method.name, args)
                    deleteCalls++
                    if (deleteCalls == failDeleteCall) error("delete chunk failed")
                    assertTrue(ids.size <= 900)
                    emptyList<BookFileRow>()
                }
                "shelves" -> listOf(ShelfEntity(id = "target", label = "Target", position = 1))
                "setShelfForBooks" -> {
                    val ids = record(method.name, args)
                    shelfCalls++
                    if (shelfCalls == failShelfCall) error("shelf chunk failed")
                    assertTrue(ids.size <= 900)
                    ids.size
                }
                "setChaptersReadForBookUrls" -> {
                    val urls = record(method.name, args)
                    readCalls++
                    if (readCalls == failReadCall) error("read chunk failed")
                    assertTrue(urls.size <= 900)
                    0
                }
                "bookTranslations" -> {
                    val ids = record(method.name, args).filterIsInstance<Long>()
                    assertTrue(ids.size <= 900)
                    ids.flatMap { id ->
                        listOf(
                            translation(id, updatedAt = 2L, title = "new-$id"),
                            translation(id, updatedAt = 1L, title = "old-$id"),
                        )
                    }
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }
}

private fun book(url: String) = BookEntity(
    installId = "chunk",
    url = url,
    title = "Title",
    author = "Author",
    coverUrl = null,
    contentType = "Novel",
    rating = 0.0,
    shelfId = "reading",
    totalChapters = 100,
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

private fun translation(bookId: Long, updatedAt: Long, title: String) = BookTranslationEntity(
    translationId = bookId * 10 + updatedAt,
    bookId = bookId,
    source = "en",
    target = "ru",
    provider = "GEMINI",
    model = null,
    promptHash = "p",
    sourceHash = "s",
    pipelineVersion = "v",
    title = title,
    status = "COMPLETE",
    updatedAt = updatedAt,
)
