package dev.mirutime.app.features.migration.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.finder.data.FakeFinderDataProvider
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationSourceGateTest {
    @Test
    fun `one shared cap covers search and target loading`() = runBlocking {
        val probe = ConcurrencyProbe(holdMs = 20)
        val gate = MigrationSourceGate()
        val search = MigrationSearch(SearchProbeFinder(probe), gate)
        val targets = BookMigrationTargets(ProbeBookProvider(probe), FakeRepository.nullRows(), gate)
        val sources = listOf(source("a"), source("b"))

        coroutineScope {
            repeat(6) { index ->
                launch(Dispatchers.Default) {
                    search.search("query-$index", sources)
                }
            }
            repeat(6) { index ->
                launch(Dispatchers.Default) {
                    targets.load("source-$index", "Source $index", "https://book/$index", "Book $index")
                }
            }
        }

        assertTrue("cap must be enforced, saw ${probe.max}", probe.max in 2..4)
    }

    @Test
    fun `search keeps source order, isolates failures and dedupes by source and url`() = runBlocking {
        val gate = MigrationSourceGate()
        val finder = object : FinderDataProvider by FakeFinderDataProvider() {
            override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> = when (sourceId) {
                "a" -> {
                    delay(40)
                    listOf(book("a", "https://a/1"), book("a", "https://a/2"), book("a", "https://a/1"))
                }
                "b" -> throw IllegalStateException("source b is down")
                else -> {
                    delay(5)
                    listOf(book("c", "https://a/1"))
                }
            }
        }
        val result = MigrationSearch(finder, gate).search("query", listOf(source("a"), source("b"), source("c")))

        // A's duplicate URL collapses; C's same URL stays because identity is
        // (sourceId, bookUrl). Order follows the source list, not reply time.
        assertEquals(
            listOf("a" to "https://a/1", "a" to "https://a/2", "c" to "https://a/1"),
            result.hits.map { it.sourceId to it.book.bookUrl },
        )
        assertEquals(listOf("Source b"), result.failedSources)
    }

    private fun source(id: String) = CatalogSource(
        id = id,
        name = "Source $id",
        language = "EN",
        type = ContentType.Novel,
        version = "1",
        pinned = false,
    )

    private fun book(sourceId: String, url: String) = CatalogBook(
        bookUrl = url,
        title = "Book $sourceId",
        rating = 0f,
        chapters = 1,
        genres = emptyList(),
        ongoing = false,
    )

    private class SearchProbeFinder(private val probe: ConcurrencyProbe) :
        FinderDataProvider by FakeFinderDataProvider() {
        override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> =
            probe.call {
                listOf(
                    CatalogBook(
                        bookUrl = "https://$sourceId/1",
                        title = query,
                        rating = 0f,
                        chapters = 1,
                        genres = emptyList(),
                        ongoing = false,
                    ),
                )
            }
    }

    private class ProbeBookProvider(private val probe: ConcurrencyProbe) : BookDataProvider {
        override suspend fun book(bookUrl: String): BookDetails? = probe.call { null }

        override suspend fun ensureHistoryBook(bookUrl: String) = Unit

        override suspend fun chapters(bookUrl: String): List<BookChapter> = emptyList()

        override suspend fun refreshChapters(bookUrl: String): List<BookChapter> = probe.call {
            listOf(
                BookChapter(
                    index = 1,
                    title = "Chapter 1",
                    isRead = false,
                    downloaded = false,
                    translated = false,
                    downloading = false,
                    url = "$bookUrl/1",
                ),
            )
        }

        override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? = null

        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit

        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult =
            MembershipResult(success = false)

        override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) = Unit

        override suspend fun downloadNext100(bookUrl: String) = Unit

        override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    private class ConcurrencyProbe(private val holdMs: Long) {
        private val inFlight = AtomicInteger()
        private val maxSeen = AtomicInteger()

        val max: Int get() = maxSeen.get()

        suspend fun <T> call(block: () -> T): T {
            val current = inFlight.incrementAndGet()
            maxSeen.updateAndGet { maxOf(it, current) }
            try {
                delay(holdMs)
                return block()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private object FakeRepository {
        fun nullRows(): LibraryRepository = LibraryRepository(
            Proxy.newProxyInstance(
                LibraryDao::class.java.classLoader,
                arrayOf(LibraryDao::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "bookByInstallAndUrl", "bookByUrl" -> null
                    "hashCode" -> 0
                    "toString" -> "FakeDao"
                    "equals" -> false
                    else -> error("Unexpected DAO call: ${method.name}")
                }
            } as LibraryDao,
        )
    }
}
