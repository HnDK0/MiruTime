package dev.mirutime.feature.reader.session

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.ReaderConfig
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.SavedReading
import dev.mirutime.feature.reader.api.ReaderContentSource
import dev.mirutime.feature.reader.api.ReaderPositionStore
import dev.mirutime.feature.tts.TtsChapterDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionStoreTest {
    private val chapters = (1..4).map { ReaderChapter("book#chapter=$it", it, "Chapter $it", false) }
    private val docs = chapters.associate { chapter ->
        chapter.index to ReaderDocument(
            bookUrl = "book",
            chapterUrl = chapter.chapterUrl,
            chapterIndex = chapter.index,
            title = chapter.title,
            blocks = listOf(
                ReaderBlock(BlockId(chapter.chapterUrl, 0), BlockKind.Paragraph, "first"),
                ReaderBlock(BlockId(chapter.chapterUrl, 1), BlockKind.Paragraph, "second"),
            ),
        )
    }

    private class TestRepository(
        private val chapters: List<ReaderChapter>,
        private val docs: Map<Int, ReaderDocument>,
        /** Blocks [document] for this chapter until the gate completes. */
        private val gate: Pair<Int, CompletableDeferred<Unit>>? = null,
        /** Throws for this chapter to simulate a real content-source error. */
        private val failOn: Pair<Int, Throwable>? = null,
        /** Per-chapter gates, so two loads can finish out of order. */
        private val gates: Map<Int, CompletableDeferred<Unit>> = emptyMap(),
    ) : ReaderContentSource {
        val marked = mutableListOf<Int>()
        // The active chapter uses the history-touching path; prefetch and the
        // previous-chapter load use the non-history path. Counted separately so
        // a regression that moves history on prefetch is visible.
        val documentCalls = mutableListOf<Int>()
        val prefetchCalls = mutableListOf<Int>()
        val reloadCalls = mutableListOf<Int>()
        val reloadAllow = mutableListOf<Boolean>()

        fun calls(index: Int): Int =
            documentCalls.count { it == index } + prefetchCalls.count { it == index }

        override suspend fun book(bookUrl: String): ReaderBook =
            ReaderBook(bookUrl, "Book", chapters)

        override suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument? {
            documentCalls += chapterIndex
            return load(chapterIndex)
        }

        override suspend fun documentForPrefetch(
            bookUrl: String,
            chapterIndex: Int,
        ): ReaderDocument? {
            prefetchCalls += chapterIndex
            return load(chapterIndex)
        }

        override suspend fun documentForRulesReload(
            bookUrl: String,
            chapterIndex: Int,
            allowTranslationOnly: Boolean,
        ): ReaderDocument? {
            reloadCalls += chapterIndex
            reloadAllow += allowTranslationOnly
            return load(chapterIndex)
        }

        private suspend fun load(chapterIndex: Int): ReaderDocument? {
            if (failOn?.first == chapterIndex) throw failOn.second
            if (gate?.first == chapterIndex) gate.second.await()
            gates[chapterIndex]?.await()
            return docs[chapterIndex]
        }

        override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
            marked += chapterIndex
        }
    }

    /** Fails the first request for one chapter, then behaves like the normal source. */
    private class FailingOnceRepository(
        private val chapters: List<ReaderChapter>,
        private val docs: Map<Int, ReaderDocument>,
        private val chapterIndex: Int,
        private val error: Throwable,
    ) : ReaderContentSource {
        private val callsByChapter = mutableMapOf<Int, Int>()

        fun calls(index: Int): Int = callsByChapter[index] ?: 0

        override suspend fun book(bookUrl: String): ReaderBook =
            ReaderBook(bookUrl, "Book", chapters)

        override suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument? =
            load(chapterIndex)

        override suspend fun documentForPrefetch(
            bookUrl: String,
            chapterIndex: Int,
        ): ReaderDocument? = load(chapterIndex)

        private fun load(index: Int): ReaderDocument? {
            val seen = (callsByChapter[index] ?: 0) + 1
            callsByChapter[index] = seen
            if (index == chapterIndex && seen == 1) throw error
            return docs[index]
        }

        override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    @Test
    fun `reload re-parses the active chapter without history or anchor loss`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()
        val anchor = store.state.value.anchor
        val historyCalls = repository.documentCalls.count { it == 2 }

        val reloaded = store.reloadActiveChapter()

        assertTrue(reloaded)
        assertEquals(listOf(2), repository.reloadCalls)
        assertEquals(historyCalls, repository.documentCalls.count { it == 2 })
        assertEquals(anchor, store.state.value.anchor)
        assertEquals(0, store.state.value.offsetPx)
        assertEquals(2, store.state.value.activeChapterIndex)
    }

    @Test
    fun `reload can keep a cached-only document`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()

        assertTrue(store.reloadActiveChapter(allowTranslationOnly = true))
        assertEquals(listOf(true), repository.reloadAllow)
    }

    @Test
    fun `reload keeps the old document when the content source fails`() = runTest {
        val delegate = TestRepository(chapters, docs)
        val repository = object : ReaderContentSource by delegate {
            override suspend fun documentForRulesReload(
                bookUrl: String,
                chapterIndex: Int,
                allowTranslationOnly: Boolean,
            ): ReaderDocument? = throw IllegalStateException("boom")
        }
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(delegate),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()
        val document = store.state.value.documents.getValue(2)

        val reloaded = store.reloadActiveChapter()

        assertFalse(reloaded)
        assertEquals(document, store.state.value.documents.getValue(2))
        assertEquals(ChapterLoadStatus.Ready, store.state.value.statuses[2])
    }

    @Test
    fun `reload is discarded when the active chapter changed meanwhile`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delegate = TestRepository(chapters, docs)
        val repository = object : ReaderContentSource by delegate {
            override suspend fun documentForRulesReload(
                bookUrl: String,
                chapterIndex: Int,
                allowTranslationOnly: Boolean,
            ): ReaderDocument? {
                gate.await()
                return delegate.documentForRulesReload(bookUrl, chapterIndex, allowTranslationOnly)
            }
        }
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(delegate),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()

        val reload = async { store.reloadActiveChapter() }
        runCurrent()
        store.goToChapter(1)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(reload.await())
        assertEquals(1, store.state.value.activeChapterIndex)
    }

    private class InMemoryPositionStore : ReaderPositionStore {
        private val values = mutableMapOf<String, SavedReading>()

        override suspend fun load(bookUrl: String): SavedReading? = values[bookUrl]

        override suspend fun save(bookUrl: String, saved: SavedReading) {
            val current = values[bookUrl]
            if (current == null || saved.seq >= current.seq) values[bookUrl] = saved
        }

        override suspend fun clear(bookUrl: String) {
            values.remove(bookUrl)
        }
    }

    private class FakeConfigStore(
        initial: ReaderConfig = ReaderConfig(style = ReaderStyle.Default),
    ) : ReaderConfigStore {
        private val _config = MutableStateFlow(initial)
        override val config: StateFlow<ReaderConfig> = _config.asStateFlow()

        override fun setMode(mode: ReaderMode) {
            _config.update { it.copy(mode = mode) }
        }

        override fun setStyle(style: ReaderStyle) {
            _config.update { it.copy(style = style) }
        }

        override fun setMangaStyle(style: MangaReaderStyle) {
            _config.update { it.copy(mangaStyle = style.sanitized()) }
        }
    }

    @Test
    fun `open restores a saved anchor for the requested chapter`() = runTest {
        val positions = InMemoryPositionStore()
        val anchor = ReadingAnchor("book#chapter=2", BlockId("book#chapter=2", 1), 0)
        positions.save("book", SavedReading(anchor, 0, 1, 0))
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = positions,
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )

        store.open("book", 2, restoreSaved = true)
        advanceUntilIdle()

        assertEquals(anchor, store.state.value.anchor)
        assertEquals(2, store.state.value.activeChapterIndex)
    }

    @Test
    fun `prefetch loads only the next chapter without moving the anchor`() = runTest {
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )

        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        assertTrue(store.state.value.documents.containsKey(2))
        assertFalse("prefetch must not pull the chapter after next", store.state.value.documents.containsKey(3))
        assertEquals(1, store.state.value.activeChapterIndex)
    }

    @Test
    fun `prefetch requests the next chapter translation once and only when it loads`() = runTest {
        val requested = mutableListOf<Pair<Int, String>>()
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
            prefetchTranslation = { index, document -> requested += index to document.chapterUrl },
        )

        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        // The prefetch of chapter 2 asks for its whole translation, with the
        // document that was just loaded rather than a partial page range.
        assertEquals(listOf(2 to "book#chapter=2"), requested)

        // Already loaded: the early return must not request it again.
        store.prefetch()
        advanceUntilIdle()
        assertEquals(listOf(2 to "book#chapter=2"), requested)
        assertEquals(1, repository.calls(2))

        // Last chapter: there is no next chapter to translate.
        val lastRequested = mutableListOf<Int>()
        val last = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
            prefetchTranslation = { index, _ -> lastRequested += index },
        )
        last.open("book", 4, restoreSaved = false)
        advanceUntilIdle()
        assertTrue(lastRequested.isEmpty())
    }

    @Test
    fun `ensurePrevious loads the previous chapter once without moving the reader`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gate = 1 to gate)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()
        val anchor = store.state.value.anchor
        val active = store.state.value.activeChapterIndex

        val first = launch { store.ensurePrevious(1) }
        runCurrent()
        val second = launch { store.ensurePrevious(1) }
        runCurrent()
        assertEquals(ChapterLoadStatus.Loading, store.state.value.statuses[1])

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()

        assertEquals(1, repository.calls(1))
        assertTrue(store.state.value.documents.containsKey(1))
        assertEquals(ChapterLoadStatus.Ready, store.state.value.statuses[1])
        assertEquals(active, store.state.value.activeChapterIndex)
        assertEquals(anchor, store.state.value.anchor)

        // A Ready chapter must never be requested again.
        store.ensurePrevious(1)
        assertEquals(1, repository.calls(1))
    }

    @Test
    fun `cancelled ensurePrevious does not block the chapter after reopen`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gate = 1 to gate)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()

        val cancelled = launch { store.ensurePrevious(1) }
        runCurrent()
        assertEquals(ChapterLoadStatus.Loading, store.state.value.statuses[1])
        cancelled.cancel()
        advanceUntilIdle()
        assertEquals(1, repository.calls(1))

        // The app-scoped store is reopened for the same book: the marker left
        // by the cancelled load must not survive into the new session.
        gate.complete(Unit)
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()
        assertEquals(
            ChapterLoadStatus.NotLoaded,
            store.state.value.statuses[1] ?: ChapterLoadStatus.NotLoaded,
        )

        val first = launch { store.ensurePrevious(1) }
        runCurrent()
        val second = launch { store.ensurePrevious(1) }
        runCurrent()
        advanceUntilIdle()
        first.join()
        second.join()

        assertEquals(2, repository.calls(1))
        assertTrue(store.state.value.documents.containsKey(1))
        assertEquals(ChapterLoadStatus.Ready, store.state.value.statuses[1])
    }

    @Test
    fun `cancelled load resets Loading to NotLoaded so the gap can retry`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gate = 1 to gate)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()

        val cancelled = launch { store.ensurePrevious(1) }
        runCurrent()
        assertEquals(ChapterLoadStatus.Loading, store.state.value.statuses[1])
        cancelled.cancel()
        advanceUntilIdle()

        // No spinner without a running load: the top gap must offer a retry.
        assertEquals(ChapterLoadStatus.NotLoaded, store.state.value.statuses[1])

        gate.complete(Unit)
        store.ensurePrevious(1)
        advanceUntilIdle()

        assertEquals(2, repository.calls(1))
        assertTrue(store.state.value.documents.containsKey(1))
        assertEquals(ChapterLoadStatus.Ready, store.state.value.statuses[1])
    }

    @Test
    fun `failed chapter carries the plugin title and login url`() = runTest {
        val repository = TestRepository(
            chapters,
            docs,
            failOn = 1 to dev.mirutime.feature.reader.ReaderLoadFailure(
                title = "Login required",
                message = "Session expired",
                authUrl = "https://site.example/login",
            ),
        )
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )

        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val failed = store.state.value.statuses[1] as ChapterLoadStatus.Failed
        assertEquals("Login required", failed.title)
        assertEquals("Session expired", failed.message)
        assertEquals("https://site.example/login", failed.authUrl)
        // First-open failure must publish a Failed gap (not an empty feed and
        // an endless spinner): the UI reads the status through the feed entry.
        val gap = store.state.value.feed.entries.single() as dev.mirutime.feature.reader.FeedEntry.Gap
        assertEquals(failed, gap.status)
    }

    @Test
    fun `ensurePrevious marks a real load error as Failed and does not retry it`() = runTest {        val repository = TestRepository(
            chapters,
            docs,
            failOn = 1 to IllegalStateException("boom"),
        )
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()

        store.ensurePrevious(1)
        advanceUntilIdle()

        assertTrue(store.state.value.statuses[1] is ChapterLoadStatus.Failed)
        store.ensurePrevious(1)
        assertEquals(1, repository.calls(1))
    }

    @Test
    fun `anchor save is debounced and uses the current anchor`() = runTest {
        val positions = InMemoryPositionStore()
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = positions,
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val moved = ReadingAnchor("book#chapter=1", BlockId("book#chapter=1", 1), 0)
        store.onAnchorChanged(moved, offsetPx = 12)
        advanceTimeBy(600)
        runCurrent()

        assertEquals(moved, positions.load("book")?.anchor)
    }

    @Test
    fun `adoptAnchor moves the active chapter, saves and keeps the scroll token`() = runTest {
        val positions = InMemoryPositionStore()
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = positions,
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val scrollToken = store.state.value.scrollToken

        val anchor = ReadingAnchor("book#chapter=3", BlockId("book#chapter=3", 1), 7, 2)
        store.adoptAnchor(anchor)

        assertEquals(anchor, store.state.value.anchor)
        assertEquals(3, store.state.value.activeChapterIndex)
        assertEquals(scrollToken, store.state.value.scrollToken)

        advanceTimeBy(600)
        runCurrent()

        assertEquals(anchor, positions.load("book")?.anchor)
    }

    @Test
    fun `goToChapter restores the provided anchor`() = runTest {
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val anchor = ReadingAnchor("book#chapter=2", BlockId("book#chapter=2", 1), charOffset = 3)
        store.goToChapter(2, anchor = anchor)
        advanceUntilIdle()

        assertEquals(2, store.state.value.activeChapterIndex)
        assertEquals(anchor, store.state.value.anchor)
    }

    @Test
    fun `goToChapter keeps the page read phase instead of blanking it`() = runTest {
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val scrollToken = store.state.value.scrollToken

        store.goToChapter(2)
        advanceUntilIdle()

        assertEquals(2, store.state.value.activeChapterIndex)
        // No intermediate Restoring phase and exactly one token bump.
        assertEquals(RestorePhase.Ready, store.state.value.restorePhase)
        assertEquals(scrollToken + 1, store.state.value.scrollToken)
        assertEquals(null, store.state.value.navigatingTo)
    }

    @Test
    fun `goToChapter reports the target while it loads`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gate = 2 to gate)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val navigation = launch { store.goToChapter(2) }
        runCurrent()

        assertEquals(2, store.state.value.navigatingTo)
        assertEquals(RestorePhase.Ready, store.state.value.restorePhase)

        gate.complete(Unit)
        navigation.join()

        assertEquals(null, store.state.value.navigatingTo)
        assertEquals(2, store.state.value.activeChapterIndex)
    }

    @Test
    fun `a repeated tap on the same chapter does not load it twice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gate = 2 to gate)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        // open() already prefetches chapter 2 once; only the repeated tap for
        // the chapter currently being loaded must add nothing.
        val callsBefore = repository.calls(2)

        val first = launch { store.goToChapter(2) }
        runCurrent()
        val callsWhileLoading = repository.calls(2)
        store.goToChapter(2)
        runCurrent()

        assertEquals(callsWhileLoading, repository.calls(2))
        assertTrue(callsWhileLoading >= callsBefore)

        gate.complete(Unit)
        first.join()
    }

    @Test
    fun `stale anchors of the old chapter do not move the reader back while navigating`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gate = 2 to gate)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val navigation = launch { store.goToChapter(2) }
        runCurrent()

        store.onAnchorChanged(
            ReadingAnchor("book#chapter=1", BlockId("book#chapter=1", 0), charOffset = 0),
            offsetPx = 0,
        )

        assertEquals(1, store.state.value.activeChapterIndex)

        gate.complete(Unit)
        navigation.join()
        assertEquals(2, store.state.value.activeChapterIndex)
    }

    @Test
    fun `ensureNext loads the following chapter once and never moves the reader`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        store.ensureNext(2)
        store.ensureNext(2)

        assertEquals(1, repository.prefetchCalls.count { it == 2 })
        assertEquals(1, store.state.value.activeChapterIndex)
        assertNotNull(store.state.value.documents[2])
    }

    @Test
    fun `a transient failure retries itself before it publishes Failed`() = runTest {
        val repository = FailingOnceRepository(
            chapters,
            docs,
            chapterIndex = 2,
            error = dev.mirutime.feature.reader.ReaderLoadFailure(
                title = "Timeout",
                message = "Timeout",
                retryable = true,
            ),
        )
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        store.ensureNext(2)
        advanceUntilIdle()

        assertEquals(ChapterLoadStatus.Ready, store.state.value.statuses[2])
        assertEquals(2, repository.calls(2))
    }

    @Test
    fun `a non retryable failure publishes Failed immediately`() = runTest {
        val repository = TestRepository(
            chapters,
            docs,
            failOn = 2 to dev.mirutime.feature.reader.ReaderLoadFailure(
                title = "Login required",
                message = "Session expired",
                authUrl = "https://site.example/login",
                retryable = false,
            ),
        )
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        store.ensureNext(2)
        advanceUntilIdle()

        assertTrue(store.state.value.statuses[2] is ChapterLoadStatus.Failed)
        assertEquals(1, repository.calls(2))
    }

    @Test
    fun `goToChapterForTts keeps the scroll token and ready phase`() = runTest {
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val scrollToken = store.state.value.scrollToken

        val document = store.goToChapterForTts(2)
        advanceUntilIdle()

        assertNotNull(document)
        assertEquals(2, store.state.value.activeChapterIndex)
        assertEquals(scrollToken, store.state.value.scrollToken)
        assertEquals(RestorePhase.Ready, store.state.value.restorePhase)
        assertFalse(store.state.value.endOfBook)
    }

    @Test
    fun `goToChapterForTts reports a failed load`() = runTest {
        val store = ReaderSessionStore(
            content = TestRepository(
                chapters,
                docs,
                failOn = 2 to IllegalStateException("boom"),
            ),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val before = store.state.value.activeChapterIndex

        val document = store.goToChapterForTts(2)
        advanceUntilIdle()

        assertEquals(null, document)
        assertEquals(before, store.state.value.activeChapterIndex)
        assertTrue(store.state.value.statuses[2] is ChapterLoadStatus.Failed)
    }

    @Test
    fun `goToChapterForTts leaves the saved position until the hand-off is accepted`() = runTest {
        val positions = InMemoryPositionStore()
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = positions,
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        assertEquals("book#chapter=1", positions.load("book")?.anchor?.chapterUrl)

        assertNotNull(store.goToChapterForTts(2))
        advanceUntilIdle()

        assertEquals(2, store.state.value.activeChapterIndex)
        // Not yet accepted: the reader's saved position stays in the chapter
        // speech still occupies, so a rejected hand-off needs no undo there.
        assertEquals("book#chapter=1", positions.load("book")?.anchor?.chapterUrl)

        store.persistPosition()

        val committed = positions.load("book")?.anchor
        assertEquals("book#chapter=2", committed?.chapterUrl)
        assertEquals(store.state.value.anchor, committed)
    }

    @Test
    fun `next and previous chapter stay inside the book`() = runTest {
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        assertEquals(2, store.nextChapterIndex())
        assertEquals(null, store.previousChapterIndex())

        store.goToChapter(4)
        advanceUntilIdle()
        assertEquals(null, store.nextChapterIndex())
        assertEquals(3, store.previousChapterIndex())

        store.onChapterFinished(4)
        assertTrue(store.state.value.endOfBook)
    }

    @Test
    fun `cached translation is published before the active document is ready`() = runTest {
        var store: ReaderSessionStore? = null
        val calls = mutableListOf<Int>()
        val documentsAtHook = mutableListOf<Boolean>()
        val statusesAtHook = mutableListOf<ChapterLoadStatus?>()
        store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
            preloadActiveChapter = { index, _ ->
                calls += index
                documentsAtHook += store!!.state.value.documents.containsKey(index)
                statusesAtHook += store!!.state.value.statuses[index]
            },
        )

        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        // Only the active chapter is preloaded; the prefetch of chapter 2 is not.
        assertEquals(listOf(1), calls)
        assertEquals(listOf(false), documentsAtHook)
        assertEquals(listOf<ChapterLoadStatus?>(ChapterLoadStatus.Loading), statusesAtHook)
        assertTrue(store.state.value.documents.containsKey(1))
        assertEquals(ChapterLoadStatus.Ready, store.state.value.statuses[1])
    }

    @Test
    fun `open keeps the configured paged mode`() = runTest {
        // Pagination fallback must never rewrite the user's mode. The session
        // seeds both state and config from the persisted choice and leaves it
        // alone while chapters are laid out.
        val config = FakeConfigStore(ReaderConfig(mode = ReaderMode.Paged))
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = InMemoryPositionStore(),
            config = config,
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )

        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        assertEquals(ReaderMode.Paged, store.state.value.mode)
        assertEquals(ReaderMode.Paged, config.config.value.mode)
    }

    @Test
    fun `reopen keeps the saved sequence monotonic`() = runTest {
        val positions = InMemoryPositionStore()
        val anchor = ReadingAnchor("book#chapter=1", BlockId("book#chapter=1", 0), 0)
        positions.save("book", SavedReading(anchor, 0, 7, 0))
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = positions,
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )

        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        assertTrue((positions.load("book")?.seq ?: 0) > 7)
    }

    @Test
    fun `prefetch and previous loads never use the history-touching path`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )

        store.open("book", 2, restoreSaved = false)
        advanceUntilIdle()

        // The active chapter moved history; the prefetch of chapter 3 did not.
        assertEquals(listOf(2), repository.documentCalls)
        assertEquals(listOf(3), repository.prefetchCalls)

        store.ensurePrevious(1)
        advanceUntilIdle()

        assertEquals(listOf(1, 3), repository.prefetchCalls.sorted())
        assertTrue(
            "previous load must not touch history",
            repository.documentCalls.none { it == 1 },
        )
    }

    @Test
    fun `TTS natural completion marks the finished chapter read once`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val target = store.onTtsChapterRequest(
            sessionChapter = 1,
            direction = TtsChapterDirection.Next,
            naturalCompletion = true,
        )

        assertEquals(2, target)
        assertEquals(listOf(1), repository.marked)

        // A manual skip must not mark the chapter read.
        store.onTtsChapterRequest(
            sessionChapter = 1,
            direction = TtsChapterDirection.Next,
            naturalCompletion = false,
        )
        assertEquals(listOf(1), repository.marked)
    }

    @Test
    fun `TTS completion marks the spoken chapter when the feed shows another`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        // The continuous feed adopted the next chapter while speech still plays
        // the previous one; the completion belongs to the spoken chapter.
        store.adoptVisibleChapter(2, anchor = null)
        assertEquals(2, store.state.value.activeChapterIndex)

        store.onTtsChapterRequest(
            sessionChapter = 1,
            direction = TtsChapterDirection.Next,
            naturalCompletion = true,
        )

        assertEquals(listOf(1), repository.marked)
    }

    @Test
    fun `TTS completion on the last chapter ends the book`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 4, restoreSaved = false)
        advanceUntilIdle()

        val target = store.onTtsChapterRequest(
            sessionChapter = 4,
            direction = TtsChapterDirection.Next,
            naturalCompletion = true,
        )

        assertEquals(null, target)
        assertEquals(listOf(4), repository.marked)
        assertTrue(store.state.value.endOfBook)
    }

    @Test
    fun `re-entry restores the saved chapter and offset`() = runTest {
        val positions = InMemoryPositionStore()
        val anchor = ReadingAnchor("book#chapter=3", BlockId("book#chapter=3", 1), 0)
        positions.save("book", SavedReading(anchor, offsetPx = 42, seq = 5, updatedAt = 0))
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = positions,
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )

        // The restored route still names chapter 1, but the reader had moved to
        // chapter 3, so the saved anchor wins.
        store.open("book", 1, restoreSaved = false, reopened = true)
        advanceUntilIdle()

        assertEquals(3, store.state.value.activeChapterIndex)
        assertEquals(anchor, store.state.value.anchor)
    }

    @Test
    fun `a slower load does not publish over the newer navigated chapter`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gates = mapOf(3 to slow))
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val first = launch { store.goToChapter(3) }
        runCurrent()
        val second = launch { store.goToChapter(4) }
        runCurrent()
        assertEquals(4, store.state.value.activeChapterIndex)

        // The stale load finishes last; neither its chapter nor its outcome may
        // reach the state the reader already shows.
        slow.complete(Unit)
        first.join()
        second.join()

        assertEquals(4, store.state.value.activeChapterIndex)
        assertEquals("book#chapter=4", store.state.value.anchor?.chapterUrl)
        assertTrue(3 !in store.state.value.documents)
        assertEquals(ChapterLoadStatus.NotLoaded, store.state.value.statuses[3])
    }

    @Test
    fun `a stale load of the same chapter keeps the newer load spinning`() = runTest {
        val stale = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<Unit>()
        val delegate = TestRepository(chapters, docs)
        var calls = 0
        val repository = object : ReaderContentSource by delegate {
            override suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument? {
                if (chapterIndex == 3) {
                    calls++
                    if (calls == 1) stale.await() else fresh.await()
                }
                return delegate.document(bookUrl, chapterIndex)
            }
        }
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(delegate),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        // 3 -> 4 -> 3: two loads of chapter 3 run, the older one finishes last.
        val first = launch { store.goToChapter(3) }
        runCurrent()
        val second = launch { store.goToChapter(4) }
        runCurrent()
        val third = launch { store.goToChapter(3) }
        runCurrent()

        stale.complete(Unit)
        first.join()
        runCurrent()

        assertEquals(ChapterLoadStatus.Loading, store.state.value.statuses[3])

        fresh.complete(Unit)
        second.join()
        third.join()

        assertEquals(ChapterLoadStatus.Ready, store.state.value.statuses[3])
        assertEquals(3, store.state.value.activeChapterIndex)
    }

    @Test
    fun `a load from before open never publishes`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val repository = TestRepository(chapters, docs, gates = mapOf(3 to slow))
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()

        val pending = launch { store.goToChapter(3) }
        runCurrent()
        // Re-opening the reader starts a fresh navigation: the old load is stale
        // even though the same book is opened again.
        store.open("book", 1, restoreSaved = false)
        runCurrent()

        slow.complete(Unit)
        pending.join()

        assertEquals(1, store.state.value.activeChapterIndex)
        assertTrue(3 !in store.state.value.documents)
    }

    @Test
    fun `a chapter change prunes loaded chapters and statuses to the active window`() = runTest {
        val repository = TestRepository(chapters, docs)
        val store = ReaderSessionStore(
            content = repository,
            positions = InMemoryPositionStore(),
            config = FakeConfigStore(),
            readTracker = ReadTracker(repository),
            scope = this,
        )
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        assertTrue(1 in store.state.value.documents)

        store.goToChapter(4)
        advanceUntilIdle()

        assertEquals(setOf(2, 4), store.state.value.documents.keys)
        assertEquals(setOf(2, 4), store.state.value.statuses.keys)
    }

    @Test
    fun `a fresh open keeps the requested chapter over the saved anchor`() = runTest {
        val positions = InMemoryPositionStore()
        val anchor = ReadingAnchor("book#chapter=3", BlockId("book#chapter=3", 1), 0)
        positions.save("book", SavedReading(anchor, offsetPx = 42, seq = 5, updatedAt = 0))
        val store = ReaderSessionStore(
            content = TestRepository(chapters, docs),
            positions = positions,
            config = FakeConfigStore(),
            readTracker = ReadTracker(TestRepository(chapters, docs)),
            scope = this,
        )

        store.open("book", 1, restoreSaved = false, reopened = false)
        advanceUntilIdle()

        assertEquals(1, store.state.value.activeChapterIndex)
    }
}
