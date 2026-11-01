package dev.mirutime.app.features.library.data

import dev.mirutime.app.features.dashboard.data.RoomDashboardDataProvider
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.app.features.translation.data.RoomMetadataTranslationStore
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookTranslationEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// Persisted translated-title overlays: the providers resolve the book's
// current config key and read every title in one batch lane; a row from a
// different language pair must never leak into the active one.
class TitleOverlayTest {

    private class TitleRow(
        val bookId: Long,
        val target: String,
        val title: String,
    )

    private class CountingDao(
        val books: List<BookEntity>,
        val titles: List<TitleRow>,
    ) {
        var translationCalls = 0
        var booksByUrlCalls = 0
        var perBookCalls = 0

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "libraryBooks" -> books
                "history" -> books.take(10)
                "searchBooks" -> books.take(10)
                "unfinishedLibraryBooks" -> books.take(4)
                "booksWithUpdates" -> books
                "readChapterStats", "readChapterStatsFor" -> emptyList<Any>()
                "tagsByBook", "tagsByBookFor" -> emptyList<Any>()
                "bookCovers", "bookCoversFor" -> emptyList<BookCoverEntity>()
                "booksByUrls" -> {
                    booksByUrlCalls++
                    val urls = args!![0] as List<*>
                    books.filter { it.url in urls }
                }
                "bookTranslations" -> {
                    translationCalls++
                    val ids = args!![0] as List<*>
                    val source = args[1] as String
                    val target = args[2] as String
                    val provider = args[3] as String
                    val promptHash = args[4] as String
                    val pipeline = args[5] as String
                    titles.mapNotNull { title ->
                        if (title.bookId !in ids || title.target != target) return@mapNotNull null
                        BookTranslationEntity(
                            translationId = title.bookId,
                            bookId = title.bookId,
                            source = source,
                            target = title.target,
                            provider = provider,
                            model = null,
                            promptHash = promptHash,
                            sourceHash = "",
                            pipelineVersion = pipeline,
                            title = title.title,
                            status = "COMPLETE",
                            updatedAt = title.bookId,
                        )
                    }
                }
                "bookTranslation", "bookByUrl" -> {
                    perBookCalls++
                    throw AssertionError("Per-book query used: ${method.name}")
                }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    private val one = "https://site/book/1"
    private val two = "https://site/book/2"

    @Before
    fun setUp() {
        AppSettings.novelTranslation[one] = pair("ru")
        AppSettings.novelTranslation[two] = pair("de")
    }

    @After
    fun tearDown() {
        AppSettings.novelTranslation.keys
            .filter { it.startsWith("https://site/book/") }
            .forEach { AppSettings.novelTranslation.remove(it) }
    }

    private fun pair(target: String) = TranslationConfig(
        enabled = true,
        source = "en",
        target = target,
        provider = "GOOGLE_PA",
        full = true,
    )

    private fun books() = (1..3).map { index ->
        BookEntity(
            bookId = index.toLong(),
            installId = "plugin",
            url = "https://site/book/$index",
            title = "Book $index",
            author = "Author",
            coverUrl = null,
            contentType = "Novel",
            rating = 0.0,
            shelfId = "reading",
            totalChapters = 10,
            inLibrary = true,
            addedAt = 1L,
            updatedAt = 1L,
            lastReadAt = 1L,
            lastChapterId = null,
            lastChapterIndex = 1,
            chapterListHash = null,
            lastUpdateAt = null,
            genresJson = "[]",
            newChapters = 1,
        )
    }

    private fun provider(dao: CountingDao) = RoomLibraryDataProvider(
        library = LibraryRepository(dao.proxy),
        translations = RoomMetadataTranslationStore(LibraryRepository(dao.proxy)),
        now = { 1_000L },
    )

    private fun dashboard(dao: CountingDao) = RoomDashboardDataProvider(
        library = LibraryRepository(dao.proxy),
        translations = RoomMetadataTranslationStore(LibraryRepository(dao.proxy)),
        now = { 1_000L },
    )

    @Test
    fun `each book gets only the row for its active language pair`() = runTest {
        val dao = CountingDao(
            books = books(),
            titles = listOf(
                TitleRow(1, "ru", "Книга 1"),
                TitleRow(1, "de", "Buch 1"),
                TitleRow(2, "de", "Buch 2"),
                TitleRow(2, "ru", "Книга 2"),
                TitleRow(3, "ru", "Книга 3"),
            ),
        )

        val mapped = provider(dao).books()

        assertEquals("Книга 1", mapped[0].translatedTitle)
        assertEquals("Buch 2", mapped[1].translatedTitle)
        // No FULL pair for book 3: the original title stays.
        assertNull(mapped[2].translatedTitle)
        // One batch per distinct config, never one query per book.
        assertEquals(2, dao.translationCalls)
        assertEquals(2, dao.booksByUrlCalls)
        assertEquals(0, dao.perBookCalls)
    }

    @Test
    fun `same config reads all titles in one batch`() = runTest {
        val rows = (1..120).map { index ->
            BookEntity(
                bookId = index.toLong(),
                installId = "plugin",
                url = "https://site/book/$index",
                title = "Book $index",
                author = "Author",
                coverUrl = null,
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
        rows.forEach { AppSettings.novelTranslation[it.url] = pair("ru") }
        val dao = CountingDao(rows, rows.map { TitleRow(it.bookId, "ru", "Книга ${it.bookId}") })

        val mapped = provider(dao).books()

        assertEquals(1, dao.translationCalls)
        assertEquals(1, dao.booksByUrlCalls)
        assertEquals(0, dao.perBookCalls)
        assertEquals("Книга 1", mapped.first().translatedTitle)
        assertEquals("Книга 120", mapped.last().translatedTitle)
    }

    @Test
    fun `resume history and updates carry the persisted title`() = runTest {
        val dao = CountingDao(
            books = books(),
            titles = listOf(
                TitleRow(1, "ru", "Книга 1"),
                TitleRow(2, "de", "Buch 2"),
            ),
        )
        val lib = provider(dao)
        val dash = dashboard(dao)

        assertEquals("Книга 1", lib.lastRead()?.translatedTitle)
        assertEquals("Книга 1", dash.continueReading()?.translatedTitle)
        assertEquals("Книга 1", dash.fullHistory().first().book.translatedTitle)
        assertEquals("Buch 2", dash.updates()[1].book.translatedTitle)
    }
}
