package dev.mirutime.app.features.book.data

import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.features.library.data.BookCoverStore
import dev.mirutime.app.features.library.data.isDeadCoverError
import dev.mirutime.imageloader.FetchedImage
import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogPage
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import java.io.File
import java.lang.reflect.Proxy
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Cover repair (Hitomi token rotation / dead CDN):
// - one source re-resolve + forced fetch, never a books.coverUrl write;
// - 10-minute cooldown per book so a grid of broken covers cannot storm;
// - a persisted dead-cover error is the automatic trigger.
class CoverRefreshTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bookUrl = "https://site.test/book/1"
    private val sourceId = "fake-source"
    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private var now = 1_000_000L

    @Before
    fun setUp() {
        PluginBookIndex.clear()
        PluginBookIndex.put(bookUrl, sourceId)
    }

    @After
    fun tearDown() {
        PluginBookIndex.clear()
    }

    private class Rows {
        val map = mutableMapOf<Long, BookCoverEntity>()
        val load: suspend (Long) -> BookCoverEntity? = { map[it] }
        val save: suspend (BookCoverEntity) -> Unit = { map[it.bookId] = it }
    }

    private fun bookRow() = BookEntity(
        bookId = 7,
        installId = sourceId,
        url = bookUrl,
        title = "Book",
        author = "Author",
        coverUrl = "https://stale.test/old.png",
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 1,
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

    // Only bookByUrl is allowed: any provider.book()/saveBook write path would
    // fail the test instead of silently updating books.coverUrl.
    private class BookDao(private val book: BookEntity) {
        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "bookByUrl" -> book.takeIf { it.url == args?.get(0) }
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    private class FakeSource(
        private val result: Outcome<BookMeta>,
        private var calls: Int = 0,
    ) : PluginSource {
        override val metadata = PluginMetadata(
            id = "fake-source",
            name = "Fake",
            baseUrl = "https://site.test",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "1.0.0",
            sha256 = "abc",
        )

        val bookCalls: Int get() = calls

        override suspend fun book(url: String): Outcome<BookMeta> {
            calls++
            return result
        }

        override suspend fun catalog(index: Int): Outcome<CatalogPage> = error("unused")
        override suspend fun search(index: Int, query: String): Outcome<CatalogPage> = error("unused")
        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CatalogPage> = error("unused")
        override suspend fun chapters(url: String): Outcome<List<dev.mirutime.core.api.Chapter>> =
            error("unused")
        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            error("unused")
        override suspend fun filters(): Outcome<List<Map<String, String>>> = error("unused")
        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> = error("unused")
    }

    private suspend fun provider(
        root: File,
        rows: Rows,
        source: FakeSource,
        fetched: MutableList<String> = mutableListOf(),
    ): PluginBookDataProvider {
        rows.map.putIfAbsent(7L, BookCoverEntity(bookId = 7L, remoteUrl = "https://cdn.test/dead.png"))
        val store = BookCoverStore(
            root = root,
            load = rows.load,
            save = rows.save,
            fetch = { _, url, _ ->
                fetched += url
                FetchedImage(200, png)
            },
            clock = { now },
        )
        return PluginBookDataProvider(
            library = LibraryRepository(BookDao(bookRow()).proxy),
            coverStore = store,
            now = { now },
            pluginSourceFor = { source },
        )
    }

    @Test
    fun `refresh re-resolves the cover and forces one fetch`() = runTest {
        val rows = Rows()
        val source = FakeSource(Outcome.Ok(BookMeta("Book", coverUrl = "https://cdn2.test/new.png")))
        val fetched = mutableListOf<String>()
        val provider = provider(temp.newFolder("covers"), rows, source, fetched)
        // A live 404 cooldown must not block the repair.
        rows.map[7L] = rows.map.getValue(7L).copy(
            lastError = "HTTP 404",
            retryAt = now + 24 * 60 * 60 * 1000L,
        )

        val result = provider.refreshCover(bookUrl)

        assertTrue(result is Outcome.Ok)
        assertEquals(listOf("https://cdn2.test/new.png"), fetched)
        assertEquals(1, source.bookCalls)
        val row = rows.map.getValue(7L)
        assertEquals("https://cdn2.test/new.png", row.remoteUrl)
        assertNull(row.lastError)
        assertNull(row.retryAt)
        assertArrayEquals(png, File(temp.root, "covers/7/cover").readBytes())
    }

    @Test
    fun `a repeated repair is cooldowned per book`() = runTest {
        val rows = Rows()
        val source = FakeSource(Outcome.Ok(BookMeta("Book", coverUrl = "https://cdn2.test/new.png")))
        val provider = provider(temp.newFolder("covers"), rows, source)

        val first = provider.refreshCover(bookUrl)
        val second = provider.refreshCover(bookUrl)
        assertEquals(1, source.bookCalls)
        assertEquals(first, second)

        now += 10 * 60 * 1000L
        provider.refreshCover(bookUrl)
        assertEquals(2, source.bookCalls)
    }

    @Test
    fun `a failed source keeps the typed failure for the shared report`() = runTest {
        val rows = Rows()
        val failure = PluginError.Network(
            cause = "HTTP 503",
            status = 503,
            reason = "Service Unavailable",
            host = "site.test",
            path = "/book/1",
        )
        val source = FakeSource(Outcome.Err(failure))
        val provider = provider(temp.newFolder("covers"), rows, source)

        val result = provider.refreshCover(bookUrl)

        assertTrue(result is Outcome.Err)
        assertEquals(failure, (result as Outcome.Err).error)
        val report = buildPluginErrorReport(
            error = result.error as PluginError,
            sourceId = sourceId,
            operation = "cover",
            identity = bookUrl,
        )
        assertTrue(report.detailText.contains("op=cover"))
        assertTrue(report.detailText.contains("HTTP 503 Service Unavailable"))
        assertFalse(report.reportText.contains("stale.test"))
    }

    @Test
    fun `a persisted dead cover error triggers one repair from ensureCover`() = runTest {
        val rows = Rows()
        val source = FakeSource(Outcome.Ok(BookMeta("Book", coverUrl = "https://cdn2.test/new.png")))
        val fetched = mutableListOf<String>()
        val provider = provider(temp.newFolder("covers"), rows, source, fetched)
        rows.map[7L] = rows.map.getValue(7L).copy(
            lastError = "HTTP 404",
            retryAt = now + 24 * 60 * 60 * 1000L,
        )

        provider.ensureCover(bookUrl)

        assertEquals(1, source.bookCalls)
        assertEquals(listOf("https://cdn2.test/new.png"), fetched)
        assertNull(rows.map.getValue(7L).lastError)
    }

    @Test
    fun `a transient cover error does not trigger a repair`() = runTest {
        val rows = Rows()
        val source = FakeSource(Outcome.Ok(BookMeta("Book", coverUrl = "https://cdn2.test/new.png")))
        val fetched = mutableListOf<String>()
        val provider = provider(temp.newFolder("covers"), rows, source, fetched)
        rows.map[7L] = rows.map.getValue(7L).copy(
            lastError = "HTTP 500",
            retryAt = now + 15 * 60 * 1000L,
        )

        provider.ensureCover(bookUrl)

        assertEquals(0, source.bookCalls)
        assertTrue(fetched.isEmpty())
        assertEquals("HTTP 500", rows.map.getValue(7L).lastError)
    }

    @Test
    fun `only dead-cover errors are repair triggers`() {
        assertTrue(isDeadCoverError("HTTP 404"))
        assertTrue(isDeadCoverError("HTTP 410"))
        assertTrue(isDeadCoverError("HTTP 0"))
        assertTrue(isDeadCoverError("invalid image"))
        assertFalse(isDeadCoverError("HTTP 500"))
        assertFalse(isDeadCoverError("write failed"))
        assertFalse(isDeadCoverError(null))
    }
}
