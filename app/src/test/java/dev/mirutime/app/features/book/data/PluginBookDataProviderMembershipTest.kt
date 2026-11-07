package dev.mirutime.app.features.book.data

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
import dev.mirutime.data.library.TagEntity
import dev.mirutime.data.library.TranslatedChapterRow
import dev.mirutime.data.library.libraryBookKey
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The bookmark add passes a route key. The provider must persist the real URL,
// resolve the row by key and land membership in the library.
class PluginBookDataProviderMembershipTest {

    private val realUrl = "https://site/book/1"
    private val sourceId = "fake-source"
    private val key = libraryBookKey(sourceId, realUrl)

    @After
    fun tearDown() {
        PluginBookIndex.clear()
    }

    private class FakeDao {
        var saved: BookEntity? = null
        var nextId = 42L
        val added = mutableListOf<Long>()
        val readIndexes = linkedSetOf<Int>()
        var touched = false
        private val rows = mutableListOf<ChapterEntity>()

        val proxy: LibraryDao = Proxy.newProxyInstance(
            LibraryDao::class.java.classLoader,
            arrayOf(LibraryDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "book" -> saved?.takeIf { it.bookId == args?.getOrNull(0) }
                "bookByUrl" -> saved?.takeIf { it.url == args?.getOrNull(0) }
                "bookByInstallAndUrl" -> saved?.takeIf {
                    it.installId == args?.getOrNull(0) && it.url == args?.getOrNull(1)
                }
                "upsertBook" -> {
                    val book = args?.getOrNull(0) as BookEntity
                    val id = book.bookId.takeIf { it > 0 } ?: nextId++
                    saved = book.copy(bookId = id)
                    id
                }
                "upsertBookByIdentity" -> {
                    val book = args?.getOrNull(0) as BookEntity
                    val existing = saved?.takeIf {
                        it.installId == book.installId && it.url == book.url
                    }
                    if (existing != null) {
                        saved = book.copy(
                            bookId = existing.bookId,
                            inLibrary = existing.inLibrary || book.inLibrary,
                        )
                        dev.mirutime.data.library.UpsertResult(existing.bookId, false)
                    } else {
                        val id = nextId++
                        saved = book.copy(bookId = id)
                        dev.mirutime.data.library.UpsertResult(id, true)
                    }
                }
                "addToLibrary" -> {
                    val id = args?.getOrNull(0) as Long
                    saved = saved?.copy(inLibrary = true, addedToLibraryAt = args?.getOrNull(1) as Long)
                    added += id
                    Unit
                }
                "tagsForBook" -> emptyList<TagEntity>()
                "latestBookTranslationTitle" -> null
                "upsertBookSearch" -> Unit
                "deleteBookTags" -> Unit
                "tagByNormalized" -> null
                "upsertTag" -> 1L
                "upsertBookTags" -> Unit
                "chapters" -> rows.filter { it.bookId == args?.getOrNull(0) }
                "chapterByIndex" -> rows.firstOrNull {
                    it.bookId == args?.getOrNull(0) && it.idx == args?.getOrNull(1)
                }
                "reconcileChapters" -> {
                    val bookId = args?.getOrNull(0) as Long
                    val seeds = args?.getOrNull(1) as List<*>
                    rows.clear()
                    seeds.filterIsInstance<ChapterSeed>().forEach { seed ->
                        rows += ChapterEntity(
                            chapterId = seed.index.toLong(),
                            bookId = bookId,
                            idx = seed.index,
                            url = seed.url,
                            title = seed.title,
                            updatedAt = 1L,
                        )
                    }
                    rows.toList()
                }
                "chapterStatusRows" -> rows.map {
                    ChapterStatusRow(
                        chapterId = it.chapterId,
                        idx = it.idx,
                        url = it.url,
                        read = it.idx in readIndexes,
                        downloaded = false,
                        hasBody = false,
                        hasPages = false,
                        sizeBytes = 0,
                    )
                }
                "translatedChapterRows" -> emptyList<TranslatedChapterRow>()
                "chapterTranslationQueueRows" -> emptyList<ChapterTranslationQueueRow>()
                "lastReadChapterRow" -> null
                "downloadTasksForBook" -> emptyList<DownloadTaskEntity>()
                "readChapterIndexes" -> readIndexes.toList()
                "durationForBook" -> 0L
                "touchHistoryAndRecord" -> {
                    touched = true
                    Unit
                }
                "setChapterRead" -> {
                    val index = args?.getOrNull(1) as Int
                    if (args?.getOrNull(2) as Boolean) readIndexes += index else readIndexes -= index
                    Unit
                }
                "history" -> if (touched) listOfNotNull(saved) else emptyList()
                else -> throw AssertionError("Unexpected DAO call: ${method.name}")
            }
        } as LibraryDao
    }

    private class FakeSource : PluginSource {
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

        override suspend fun book(url: String): Outcome<BookMeta> =
            Outcome.Ok(BookMeta(title = "Book", genres = listOf("Fantasy")))

        override suspend fun chapters(url: String): Outcome<List<Chapter>> =
            Outcome.Ok(listOf(Chapter(title = "Chapter 1", url = "https://site/ch/1")))

        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            error("unused")

        override suspend fun filters(): Outcome<List<Map<String, String>>> = error("unused")

        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
            error("unused")
    }

    private fun provider(
        dao: FakeDao,
        scope: CoroutineScope,
    ): Pair<PluginBookDataProvider, LibraryRepository> {
        val repository = LibraryRepository(dao.proxy)
        val source = FakeSource()
        val provider = PluginBookDataProvider(
            library = repository,
            pluginSourceFor = { id -> source.takeIf { id == sourceId } },
            scope = scope,
        )
        return provider to repository
    }

    @Test
    fun `bookmark add by key persists the real url and joins the library`() = runTest {
        val dao = FakeDao()
        val (provider, _) = provider(dao, this)

        provider.book(key)
        val result = provider.setInLibrary(key, true)

        assertTrue(result.success)
        val saved = requireNotNull(dao.saved)
        assertEquals(realUrl, saved.url)
        assertTrue(saved.inLibrary)
        assertTrue(dao.added.contains(saved.bookId))
    }

    @Test
    fun `history progress and read marks work for a book outside the library`() = runTest {
        val dao = FakeDao()
        val (provider, repository) = provider(dao, this)

        provider.book(key)
        provider.ensureHistoryBook(key)
        provider.chapters(key)
        repository.touchHistory(key, 1, 100L)
        provider.markChapterRead(key, 1, true)

        val snapshot = requireNotNull(repository.chapterStatusSnapshot(key))
        assertTrue(snapshot.readIndexes.contains(1))
        assertEquals(listOf(requireNotNull(dao.saved).bookId), repository.history(10).map { it.bookId })
    }

    @Test
    fun `opening a persisted row fills the header title index`() = runTest {
        val dao = FakeDao()
        val (provider, _) = provider(dao, this)
        provider.book(key)
        provider.setInLibrary(key, true)
        PluginBookIndex.clear()

        // A fresh provider has no in-memory details: this must read the row.
        val (freshProvider, _) = provider(dao, this)
        val cached = freshProvider.book(key)

        assertEquals("Book", cached?.title)
        assertEquals("Book", PluginBookIndex.titleFor(key))
    }
}
