package dev.mirutime.app.features.migration.ui

import dev.mirutime.app.di.MigrationGraph
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.finder.data.FakeFinderDataProvider
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.migration.data.BookMigrationTargets
import dev.mirutime.app.features.migration.data.MigrationSearch
import dev.mirutime.app.features.migration.data.MigrationSourceGate
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.migration.BookMigrationCoordinator
import dev.mirutime.data.library.migration.BookMigrationFileStore
import dev.mirutime.data.library.migration.BookMigrationSettingsStore
import dev.mirutime.data.library.migration.MigrationStoredText
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationFlowCancellationTest {
    @Test
    fun `two selected books search in parallel without reordering results`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val concurrent = AtomicInteger()
            val maxSeen = AtomicInteger()
            val bothStarted = CompletableDeferred<Unit>()
            val finder = object : FinderDataProvider by FakeFinderDataProvider() {
                override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> {
                    val now = concurrent.incrementAndGet()
                    maxSeen.updateAndGet { maxOf(it, now) }
                    try {
                        if (now == 2) bothStarted.complete(Unit)
                        withTimeoutOrNull(500) { bothStarted.await() }
                        return listOf(hit(query))
                    } finally {
                        concurrent.decrementAndGet()
                    }
                }
            }
            val state = MassMigrationState(graph(finder), scope)
            val books = listOf(candidate(1, "https://old/1", "Book One"), candidate(2, "https://old/2", "Book Two"))
            state.syncSources(listOf(source("a")))
            state.toggleBooks(books.map { it.bookUrl }.toSet(), select = true)

            state.search(books, listOf(source("a")))
            await { state.searchUi is MassSearchUi.Ready }

            val ready = state.searchUi as MassSearchUi.Ready
            assertEquals(listOf("Book One", "Book Two"), ready.results.map { it.candidate.title })
            assertTrue("both books must run at once, saw ${maxSeen.get()}", maxSeen.get() == 2)
            assertTrue(state.searchingUrls.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resetting selection cancels an in-flight mass search and drops its answer`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            val cancelled = AtomicInteger()
            val finder = object : FinderDataProvider by FakeFinderDataProvider() {
                override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.incrementAndGet()
                    }
                }
            }
            val state = MassMigrationState(graph(finder), scope)
            val book = candidate(1, "https://old/1", "Book One")
            state.syncSources(listOf(source("a")))
            state.toggleBooks(setOf(book.bookUrl), select = true)

            state.search(listOf(book), listOf(source("a")))
            withTimeout(2_000) { started.await() }
            state.toggleBook(book.bookUrl)
            await { cancelled.get() > 0 }

            delay(50)
            assertEquals(MassSearchUi.Idle, state.searchUi)
            assertTrue(state.progress.isEmpty())
            assertTrue(state.searchingUrls.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `new mass search replaces a cancelled run with fresh results`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            val hang = AtomicInteger(1)
            val cancelled = AtomicInteger()
            val finder = object : FinderDataProvider by FakeFinderDataProvider() {
                override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> {
                    if (hang.get() == 1) {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.incrementAndGet()
                        }
                    }
                    return listOf(hit(query))
                }
            }
            val state = MassMigrationState(graph(finder), scope)
            val book = candidate(1, "https://old/1", "Book One")
            state.syncSources(listOf(source("a")))
            state.toggleBooks(setOf(book.bookUrl), select = true)
            state.search(listOf(book), listOf(source("a")))
            withTimeout(2_000) { started.await() }

            hang.set(0)
            state.search(listOf(book), listOf(source("a")))
            await { state.searchUi is MassSearchUi.Ready }

            await { cancelled.get() > 0 }
            val ready = state.searchUi as MassSearchUi.Ready
            assertEquals(listOf("Book One"), ready.results.map { it.candidate.title })
            assertTrue("stale result must not survive", state.progress.size == 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clearing the single query cancels the search and ignores its answer`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            val cancelled = AtomicInteger()
            val finder = object : FinderDataProvider by FakeFinderDataProvider() {
                override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> {
                    if (query == "slow") {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.incrementAndGet()
                        }
                    }
                    return listOf(hit(query))
                }
            }
            val state = MigrationSingleState(candidate(1, "https://old/1", "Book One"), graph(finder), scope)
            state.syncSources(listOf(source("a")))
            state.query = "slow"

            state.search()
            withTimeout(2_000) { started.await() }
            state.clearQuery()
            await { cancelled.get() > 0 }

            delay(50)
            assertEquals(MigrationSearchUi.Idle, state.searchUi)

            state.query = "fast"
            state.search()
            await { state.searchUi is MigrationSearchUi.Ready }
            val ready = state.searchUi as MigrationSearchUi.Ready
            assertEquals(listOf("fast"), ready.result.hits.map { it.book.title })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `new single query cancels the previous in-flight search`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            val cancelled = AtomicInteger()
            val finder = object : FinderDataProvider by FakeFinderDataProvider() {
                override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> {
                    if (query == "slow") {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cancelled.incrementAndGet()
                        }
                    }
                    return listOf(hit(query))
                }
            }
            val state = MigrationSingleState(candidate(1, "https://old/1", "Book One"), graph(finder), scope)
            state.syncSources(listOf(source("a")))
            state.query = "slow"

            state.search()
            await { state.searchUi is MigrationSearchUi.Searching }
            withTimeout(2_000) { started.await() }
            state.query = "fast"
            state.search()
            await { state.searchUi is MigrationSearchUi.Ready }

            await { cancelled.get() > 0 }
            val ready = state.searchUi as MigrationSearchUi.Ready
            assertEquals(listOf("fast"), ready.result.hits.map { it.book.title })
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5)
        }
    }

    private fun hit(query: String) = CatalogBook(
        bookUrl = "https://new/${query.replace(' ', '-')}",
        title = query,
        rating = 0f,
        chapters = 1,
        genres = emptyList(),
        ongoing = false,
    )

    private fun source(id: String) = CatalogSource(
        id = id,
        name = "Source $id",
        language = "EN",
        type = ContentType.Novel,
        version = "1",
        pinned = false,
    )

    private fun candidate(id: Long, url: String, title: String) = MigrationCandidateBook(
        bookId = id,
        bookUrl = url,
        title = title,
        author = "Author",
        sourceId = "old",
        sourceName = "Old source",
        unknownSource = false,
        chapterCount = 2,
    )

    private fun graph(
        finder: FinderDataProvider,
        bookProvider: BookDataProvider = EmptyBookProvider,
    ): MigrationGraph {
        val dao = Proxy.newProxyInstance(
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
        } as LibraryDao
        val repository = LibraryRepository(dao)
        val gate = MigrationSourceGate()
        return MigrationGraph(
            coordinator = BookMigrationCoordinator(
                dao = dao,
                repository = repository,
                files = NoopFileStore,
                settings = NoopSettingsStore,
                now = { 0L },
            ),
            search = MigrationSearch(finder, gate),
            targets = BookMigrationTargets(bookProvider, repository, gate),
        )
    }

    private object EmptyBookProvider : BookDataProvider {
        override suspend fun book(bookUrl: String): BookDetails? = null
        override suspend fun ensureHistoryBook(bookUrl: String) = Unit
        override suspend fun chapters(bookUrl: String): List<BookChapter> = emptyList()
        override suspend fun refreshChapters(bookUrl: String): List<BookChapter> = emptyList()
        override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? = null
        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit
        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult =
            MembershipResult(success = false)
        override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) = Unit
        override suspend fun downloadNext100(bookUrl: String) = Unit
        override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    private object NoopFileStore : BookMigrationFileStore {
        override suspend fun readText(path: String): String? = null
        override suspend fun writeText(bookId: Long, chapterId: Long, text: String): MigrationStoredText =
            MigrationStoredText("", 0, "")
        override suspend fun readBytes(path: String): ByteArray? = null
        override suspend fun writePage(
            bookId: Long,
            chapterId: Long,
            index: Int,
            sourcePath: String,
            bytes: ByteArray,
        ): String = ""
        override suspend fun pagePaths(pagePathsJson: String): List<String> = emptyList()
        override suspend fun encodePagePaths(paths: List<String>): String = "[]"
        override suspend fun deleteText(bookId: Long, chapterId: Long) = Unit
        override suspend fun deletePages(bookId: Long, chapterId: Long) = Unit
        override suspend fun deleteBookFiles(bookId: Long) = Unit
    }

    private object NoopSettingsStore : BookMigrationSettingsStore {
        override suspend fun copyNovelSettings(oldBookUrl: String, newBookUrl: String) = Unit
        override suspend fun deleteNovelSettings(bookUrl: String) = Unit
    }
}
