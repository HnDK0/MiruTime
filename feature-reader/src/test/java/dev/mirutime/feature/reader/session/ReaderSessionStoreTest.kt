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
    ) : ReaderContentSource {
        val marked = mutableListOf<Int>()
        // The active chapter uses the history-touching path; prefetch and the
        // previous-chapter load use the non-history path. Counted separately so
        // a regression that moves history on prefetch is visible.
        val documentCalls = mutableListOf<Int>()
        val prefetchCalls = mutableListOf<Int>()

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

        private suspend fun load(chapterIndex: Int): ReaderDocument? {
            if (failOn?.first == chapterIndex) throw failOn.second
            if (gate?.first == chapterIndex) gate.second.await()
            return docs[chapterIndex]
        }

        override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
            marked += chapterIndex
        }
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
