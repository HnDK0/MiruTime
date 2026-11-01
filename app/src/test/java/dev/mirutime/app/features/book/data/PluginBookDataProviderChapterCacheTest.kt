package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogPage
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.ChapterStatusRow
import dev.mirutime.data.library.ChapterTranslationQueueRow
import dev.mirutime.data.library.DownloadTaskEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.TranslatedChapterRow
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Opening a book with persisted chapters must never reach the plugin/network;
// only an empty DB falls back to the source. Explicit refresh stays the one
// network path (covered by refreshChapters, not this opening flow).
class PluginBookDataProviderChapterCacheTest {

    private val url = "https://site/book/1"
    private val sourceId = "fake-source"

    private fun book() = BookEntity(
        bookId = 7,
        installId = sourceId,
        url = url,
        title = "Book",
        author = "Author",
        coverUrl = null,
        contentType = "Novel",
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 2,
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

    private fun chapter(index: Int) = ChapterEntity(
        chapterId = index.toLong(),
        bookId = 7,
        idx = index,
        url = "https://site/ch/$index",
        title = "Stored $index",
        updatedAt = 1L,
    )

    private class FakeDao(
        private val book: BookEntity,
        private val chapters: List<ChapterEntity>,
    ) {
        val reconciledSeeds = mutableListOf<ChapterSeed>()
        var totalUpdates = 0

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "bookByUrl" -> book.takeIf { it.url == args?.get(0) }
                // Status snapshot returns null without further queries.
                "book" -> null
                "chapters" -> chapters
                "tagsForBook" -> emptyList<dev.mirutime.data.library.TagEntity>()
                "readChapterIndexes" -> emptyList<Int>()
                "durationForBook" -> 0L
                "bookByInstallAndUrl" -> book.takeIf {
                    it.installId == args?.get(0) && it.url == args?.get(1)
                }
                "reconcileChapters" -> {
                    args?.get(1).let { seeds ->
                        if (seeds is List<*>) reconciledSeeds += seeds.filterIsInstance<ChapterSeed>()
                    }
                    chapters
                }
                "updateTotalChapters" -> totalUpdates++
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    private class StatusDao(
        private val book: BookEntity,
        private val chapters: List<ChapterEntity>,
    ) {
        var statusCalls = 0

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "bookByUrl" -> book
                "book" -> book
                "chapters" -> chapters
                "chapterStatusRows" -> {
                    statusCalls++
                    chapters.map {
                        ChapterStatusRow(
                            chapterId = it.chapterId,
                            idx = it.idx,
                            url = it.url,
                            read = false,
                            downloaded = false,
                            hasBody = false,
                            hasPages = false,
                            sizeBytes = 0,
                        )
                    }
                }
                "translatedChapterRows" -> emptyList<TranslatedChapterRow>()
                "chapterTranslationQueueRows" -> emptyList<ChapterTranslationQueueRow>()
                "lastReadChapterRow" -> null
                "downloadTasksForBook" -> emptyList<DownloadTaskEntity>()
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    private class FakePluginSource(
        private val fetched: List<Chapter> = emptyList(),
        // Typed source failure (e.g. parsePage page 2): refresh must keep the
        // stored rows and write nothing.
        private val error: PluginError? = null,
    ) : PluginSource {
        var chapterCalls = 0

        override val metadata = PluginMetadata(
            id = "fake-source",
            name = "Fake",
            baseUrl = "https://site/",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "1",
            sha256 = "sha",
        )

        override suspend fun catalog(index: Int): Outcome<CatalogPage> = error("unused")

        override suspend fun search(index: Int, query: String): Outcome<CatalogPage> =
            error("unused")

        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CatalogPage> = error("unused")

        override suspend fun book(url: String): Outcome<BookMeta> = error("unused")

        override suspend fun chapters(url: String): Outcome<List<Chapter>> {
            chapterCalls++
            val failure = error
            return if (failure != null) Outcome.Err(failure) else Outcome.Ok(fetched)
        }

        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            error("unused")

        override suspend fun filters(): Outcome<List<Map<String, String>>> = error("unused")

        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
            error("unused")
    }

    @After
    fun tearDown() {
        PluginBookIndex.clear()
    }

    @Test
    fun `cached metadata opens without consulting the plugin`() = runTest {
        PluginBookIndex.put(url, sourceId)
        var sourceLookups = 0
        val provider = PluginBookDataProvider(
            library = LibraryRepository(FakeDao(book(), listOf(chapter(1), chapter(2))).proxy),
            pluginSourceFor = {
                sourceLookups++
                throw AssertionError("opening a cached book must not query the plugin")
            },
        )

        val details = provider.book(url)

        assertEquals("Book", details?.title)
        assertEquals(0, sourceLookups)
    }

    @Test
    fun `stored rows are returned without consulting the plugin`() = runTest {
        PluginBookIndex.put(url, sourceId)
        var sourceLookups = 0
        val provider = PluginBookDataProvider(
            library = LibraryRepository(FakeDao(book(), listOf(chapter(1), chapter(2))).proxy),
            pluginSourceFor = {
                sourceLookups++
                throw AssertionError("plugin must not be queried for stored chapters")
            },
        )

        val chapters = provider.chapters(url)

        assertEquals(0, sourceLookups)
        assertEquals(listOf(1, 2), chapters.map { it.index })
        assertEquals(listOf("Stored 1", "Stored 2"), chapters.map { it.title })
        assertEquals(
            listOf("https://site/ch/1", "https://site/ch/2"),
            chapters.map { it.url },
        )
    }

    @Test
    fun `empty db falls back to the plugin chapter list`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val source = FakePluginSource(
            listOf(
                Chapter("Fetched 1", "https://site/ch/1"),
                Chapter("Fetched 2", "https://site/ch/2"),
            ),
        )
        val provider = PluginBookDataProvider(
            library = LibraryRepository(FakeDao(book(), emptyList()).proxy),
            pluginSourceFor = { source },
        )

        val chapters = provider.chapters(url)

        assertEquals(1, source.chapterCalls)
        assertEquals(listOf("Fetched 1", "Fetched 2"), chapters.map { it.title })
        assertEquals(
            listOf("https://site/ch/1", "https://site/ch/2"),
            chapters.map { it.url },
        )
    }

    @Test
    fun `chaptersOutcome returns stored rows without consulting the plugin`() = runTest {
        PluginBookIndex.put(url, sourceId)
        var sourceLookups = 0
        val provider = PluginBookDataProvider(
            library = LibraryRepository(FakeDao(book(), listOf(chapter(1), chapter(2))).proxy),
            pluginSourceFor = {
                sourceLookups++
                throw AssertionError("plugin must not be queried for stored chapters")
            },
        )

        val result = provider.chaptersOutcome(url)

        assertEquals(0, sourceLookups)
        assertEquals(
            listOf(1, 2),
            (result as Outcome.Ok).value.map { it.index },
        )
    }

    // The book page opens through the typed path: an empty store with a failed
    // source must be an Err, never the empty-list answer that reads as
    // "No chapters".
    @Test
    fun `chaptersOutcome surfaces a typed failure for an empty store`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), emptyList())
        val source = FakePluginSource(
            error = PluginError.ScriptError("parsePage failed on page 2"),
        )
        val provider = PluginBookDataProvider(
            library = LibraryRepository(dao.proxy),
            pluginSourceFor = { source },
        )

        val result = provider.chaptersOutcome(url)

        assertEquals(1, source.chapterCalls)
        assertTrue(result is Outcome.Err)
        assertEquals(
            PluginError.ScriptError("parsePage failed on page 2"),
            (result as Outcome.Err).error,
        )
        assertEquals(emptyList<ChapterSeed>(), dao.reconciledSeeds)
        assertEquals(0, dao.totalUpdates)
    }

    @Test
    fun `chaptersOutcome reports a successful empty list as Ok`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val provider = PluginBookDataProvider(
            library = LibraryRepository(FakeDao(book(), emptyList()).proxy),
            pluginSourceFor = { FakePluginSource(emptyList()) },
        )

        assertEquals(Outcome.Ok(emptyList<BookChapter>()), provider.chaptersOutcome(url))
    }

    @Test
    fun `cachedChapters returns stored rows without the plugin`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val provider = PluginBookDataProvider(
            library = LibraryRepository(FakeDao(book(), listOf(chapter(1), chapter(2))).proxy),
            pluginSourceFor = {
                throw AssertionError("cachedChapters must not query the plugin")
            },
        )

        val cached = provider.cachedChapters(url)

        assertEquals(listOf(1, 2), cached?.map { it.index })
        assertEquals(listOf("Stored 1", "Stored 2"), cached?.map { it.title })
    }

    @Test
    fun `cachedChapters is null for an empty db without the plugin`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val provider = PluginBookDataProvider(
            library = LibraryRepository(FakeDao(book(), emptyList()).proxy),
            pluginSourceFor = {
                throw AssertionError("cachedChapters must not query the plugin")
            },
        )

        assertNull(provider.cachedChapters(url))
    }

    @Test
    fun `refresh of a stored book asks the plugin and persists the new rows`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), listOf(chapter(1), chapter(2)))
        val source = FakePluginSource(
            listOf(
                Chapter("Fresh 1", "https://site/ch/1"),
                Chapter("Fresh 2", "https://site/ch/2"),
            ),
        )
        val provider = PluginBookDataProvider(
            library = LibraryRepository(dao.proxy),
            pluginSourceFor = { source },
        )

        val refreshed = provider.refreshChapters(url)

        assertEquals(1, source.chapterCalls)
        assertEquals(listOf("Fresh 1", "Fresh 2"), refreshed.map { it.title })
        assertEquals(
            listOf("Fresh 1", "Fresh 2"),
            dao.reconciledSeeds.map { it.title },
        )
    }

    @Test
    fun `partial plugin response keeps stored chapters and skips reconcile`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), listOf(chapter(1), chapter(2)))
        val source = FakePluginSource(
            listOf(Chapter("Partial 1", "https://site/ch/1")),
        )
        val provider = PluginBookDataProvider(
            library = LibraryRepository(dao.proxy),
            pluginSourceFor = { source },
        )

        val refreshed = provider.refreshChapters(url)

        assertEquals(1, source.chapterCalls)
        assertEquals(listOf(1, 2), refreshed.map { it.index })
        assertEquals(listOf("Stored 1", "Stored 2"), refreshed.map { it.title })
        assertEquals(emptyList<ChapterSeed>(), dao.reconciledSeeds)
    }

    // A typed source failure (parsePage page>1 now returns Err) must not write
    // anything: stored rows survive untouched and a book with no rows stays
    // empty. Repair is the next explicit refresh, never a partial persist.
    @Test
    fun `pageFailureKeepsStored`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val storeWithRows = FakeDao(book(), listOf(chapter(1), chapter(2)))
        val storedSource = FakePluginSource(error = PluginError.ScriptError("parsePage failed on page 2"))
        val storedProvider = PluginBookDataProvider(
            library = LibraryRepository(storeWithRows.proxy),
            pluginSourceFor = { storedSource },
        )

        val refreshed = storedProvider.refreshChapters(url)

        assertEquals(1, storedSource.chapterCalls)
        assertEquals(listOf(1, 2), refreshed.map { it.index })
        assertEquals(listOf("Stored 1", "Stored 2"), refreshed.map { it.title })
        assertEquals(emptyList<ChapterSeed>(), storeWithRows.reconciledSeeds)
        assertEquals(0, storeWithRows.totalUpdates)

        val emptyStore = FakeDao(book(), emptyList())
        val emptyProvider = PluginBookDataProvider(
            library = LibraryRepository(emptyStore.proxy),
            pluginSourceFor = { FakePluginSource(error = PluginError.ScriptError("parsePage failed on page 2")) },
        )

        val firstFetch = emptyProvider.refreshChapters(url)

        assertEquals(emptyList<BookChapter>(), firstFetch)
        assertEquals(emptyList<ChapterSeed>(), emptyStore.reconciledSeeds)
        assertEquals(0, emptyStore.totalUpdates)
    }

    // The book page consumes the typed value so it can render op=chapters and
    // log ok=false; the kept-stored behaviour stays exactly as the List path.
    @Test
    fun `typed refresh surfaces the failure and keeps stored rows`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), listOf(chapter(1), chapter(2)))
        val source = FakePluginSource(
            error = PluginError.ScriptError("parsePage failed on page 2"),
        )
        val provider = PluginBookDataProvider(
            library = LibraryRepository(dao.proxy),
            pluginSourceFor = { source },
        )

        val result = provider.refreshChaptersOutcome(url)

        assertTrue(result is Outcome.Err)
        assertEquals(
            PluginError.ScriptError("parsePage failed on page 2"),
            (result as Outcome.Err).error,
        )
        assertEquals(listOf(1, 2), provider.cachedChapters(url)?.map { it.index })
        assertEquals(emptyList<ChapterSeed>(), dao.reconciledSeeds)
        assertEquals(0, dao.totalUpdates)

        val emptyStore = FakeDao(book(), emptyList())
        val emptyProvider = PluginBookDataProvider(
            library = LibraryRepository(emptyStore.proxy),
            pluginSourceFor = { source },
        )

        assertTrue(emptyProvider.refreshChaptersOutcome(url) is Outcome.Err)
        assertNull(emptyProvider.cachedChapters(url))
    }

    @Test
    fun `typed refresh reports a genuinely empty list as Ok`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = FakeDao(book(), emptyList())
        val provider = PluginBookDataProvider(
            library = LibraryRepository(dao.proxy),
            pluginSourceFor = { FakePluginSource(emptyList()) },
        )

        val result = provider.refreshChaptersOutcome(url)

        assertEquals(Outcome.Ok(emptyList<BookChapter>()), result)
    }

    @Test
    fun `chapter download invalidation drops the cached status snapshot`() = runTest {
        PluginBookIndex.put(url, sourceId)
        val dao = StatusDao(book(), listOf(chapter(1), chapter(2)))
        val provider = PluginBookDataProvider(
            library = LibraryRepository(dao.proxy),
            pluginSourceFor = { null },
        )

        provider.chapters(url)
        provider.chapters(url)
        assertEquals(1, dao.statusCalls)

        provider.setChapterDownloaded(url, 1..2, false)
        provider.chapters(url)

        assertEquals(2, dao.statusCalls)
    }
}
