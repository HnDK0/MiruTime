package dev.mirutime.feature.reader.session

import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.reader.api.ReaderContentSource
import dev.mirutime.feature.reader.api.ReaderPositionStore
import dev.mirutime.feature.reader.api.ReaderSessionTracker
import dev.mirutime.feature.reader.SavedReading
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.FeedPlan
import dev.mirutime.feature.reader.FeedPlanner
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.ReaderLoadFailure
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderOpenResolver
import dev.mirutime.feature.reader.ReaderOpenTarget
import dev.mirutime.feature.tts.TtsChapterDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RestorePhase { Restoring, Ready, Failed }

data class ReaderSessionState(
    val book: dev.mirutime.app.features.reader.document.ReaderBook? = null,
    val chapters: List<dev.mirutime.app.features.reader.document.ReaderChapter> = emptyList(),
    val documents: Map<Int, ReaderDocument> = emptyMap(),
    val statuses: Map<Int, ChapterLoadStatus> = emptyMap(),
    val anchor: ReadingAnchor? = null,
    val offsetPx: Int = 0,
    val mode: ReaderMode = ReaderMode.Feed,
    val feed: FeedPlan = FeedPlan.Empty,
    val restorePhase: RestorePhase = RestorePhase.Restoring,
    val activeChapterIndex: Int = 0,
    val endOfBook: Boolean = false,
    val scrollToken: Int = 0,
    val error: String? = null,
)

class ReaderSessionStore(
    private val content: ReaderContentSource,
    private val positions: ReaderPositionStore,
    private val config: ReaderConfigStore,
    private val readTracker: ReadTracker,
    private val scope: CoroutineScope,
    private val sessionTracker: ReaderSessionTracker? = null,
    /**
     * Publishes cached translation state before the active chapter is rendered.
     * Optional: a miss is a no-op and the normal ensureChapter path remains.
     */
    private val preloadActiveChapter: (suspend (Int, ReaderDocument) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(
        ReaderSessionState(mode = config.config.value.mode),
    )
    val state: StateFlow<ReaderSessionState> = _state.asStateFlow()

    private var bookUrl: String = ""
    private var saveJob: Job? = null
    private var prefetchJob: Job? = null
    private var seq: Long = 0
    // Value is a per-call token so a late cleanup from a cancelled load cannot
    // drop the marker of a newer load for the same chapter.
    private val inFlight = mutableMapOf<Int, Any>()
    private val inFlightMutex = Mutex()

    suspend fun open(
        bookUrl: String,
        requestedChapterIndex: Int?,
        restoreSaved: Boolean,
        reopened: Boolean = false,
    ) {
        sessionTracker?.stop()
        // The store is app-scoped: markers left by a cancelled load of the
        // previous book must not block the new session's chapters.
        inFlightMutex.withLock { inFlight.clear() }
        this.bookUrl = bookUrl
        _state.value = ReaderSessionState(
            mode = config.config.value.mode,
            restorePhase = RestorePhase.Restoring,
        )
        val book = content.book(bookUrl)
        if (book == null) {
            _state.update {
                it.copy(restorePhase = RestorePhase.Failed, error = "Book not found")
            }
            return
        }
        val saved = positions.load(bookUrl)
        seq = maxOf(seq, saved?.seq ?: 0)
        _state.update { it.copy(book = book, chapters = book.chapters) }
        val target = when {
            requestedChapterIndex == null -> ReaderOpenResolver.forContinue(book, saved)
            // Process-death re-entry: the restored route names the chapter the
            // reader first opened on, so the saved anchor must win instead.
            reopened -> ReaderOpenResolver.forReopened(book, requestedChapterIndex, saved)
            else -> ReaderOpenResolver.forRequested(book, requestedChapterIndex, restoreSaved, saved)
        }
        MiruLog.event(
            "reader",
            "open",
            "book" to MiruLog.idHash(bookUrl),
            "mode" to config.config.value.mode,
            "chapters" to book.chapters.size,
            "target" to when (target) {
                ReaderOpenTarget.Empty -> "empty"
                is ReaderOpenTarget.End -> "end:${target.chapterIndex}"
                is ReaderOpenTarget.Chapter -> "chapter:${target.chapterIndex}"
            },
            "restore" to restoreSaved,
        )
        when (target) {
            ReaderOpenTarget.Empty -> {
                _state.update {
                    it.copy(restorePhase = RestorePhase.Failed, error = "No chapters")
                }
                return
            }
            is ReaderOpenTarget.End -> {
                _state.update { it.copy(endOfBook = true) }
                loadChapter(target.chapterIndex, saved, restoreAnchor = true, makeActive = true, atEnd = true)
            }
            is ReaderOpenTarget.Chapter -> {
                loadChapter(target.chapterIndex, saved, target.restoreAnchor, makeActive = true)
            }
        }
        _state.update {
            it.copy(restorePhase = RestorePhase.Ready, scrollToken = it.scrollToken + 1)
        }
        sessionTracker?.start(bookUrl, _state.value.activeChapterIndex)
        launchPrefetch()
    }

    suspend fun goToChapter(
        index: Int,
        atEnd: Boolean = false,
        anchor: ReadingAnchor? = null,
    ) {
        if (_state.value.chapters.none { it.index == index }) return
        sessionTracker?.stop()
        _state.update { it.copy(restorePhase = RestorePhase.Restoring, endOfBook = false) }
        val saved = anchor?.let {
            SavedReading(it, offsetPx = 0, seq = seq, updatedAt = System.currentTimeMillis())
        }
        loadChapter(index, saved, restoreAnchor = saved != null, makeActive = true, atEnd = atEnd)
        _state.update {
            it.copy(restorePhase = RestorePhase.Ready, scrollToken = it.scrollToken + 1)
        }
        sessionTracker?.start(bookUrl, index)
        launchPrefetch()
    }

    /**
     * Moves the active chapter while the user scrolls the continuous manga
     * strip. Deliberately does not bump [ReaderSessionState.scrollToken] and
     * does not touch restorePhase: the strip is already on screen, so the
     * handoff must not jump or flash a loading state. Far chapters are pruned
     * (the strip only keeps the active chapter and its neighbours).
     */
    suspend fun adoptVisibleChapter(index: Int, anchor: ReadingAnchor?) {
        val current = _state.value
        if (current.activeChapterIndex == index) return
        val document = current.documents[index] ?: return
        sessionTracker?.stop()
        _state.update {
            it.copy(
                activeChapterIndex = index,
                anchor = anchor?.takeIf { value -> value.chapterUrl == document.chapterUrl }
                    ?: document.firstAnchor()
                    ?: it.anchor,
                offsetPx = 0,
                documents = FeedPlanner.prune(it.documents, index),
            )
        }
        rebuildFeed()
        persistNow()
        sessionTracker?.start(bookUrl, index)
        launchPrefetch()
    }

    private fun launchPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch { prefetch() }
    }

    suspend fun prefetch() {
        val active = _state.value.activeChapterIndex
        val next = _state.value.chapters.firstOrNull { it.index == active + 1 } ?: return
        val status = _state.value.statuses[next.index]
        if (status == ChapterLoadStatus.Ready || status is ChapterLoadStatus.Failed) return
        if (loadChapter(next.index, null, false, false) != null) {
            MiruLog.event("reader", "prefetch", "chapter" to next.index)
        }
    }

    /**
     * Loads a chapter behind the active one for the top feed gap, without
     * moving the reader. Only an untouched chapter is requested; Loading,
     * Ready and Failed chapters are left alone so composition retries cannot
     * stampede the content source.
     */
    suspend fun ensurePrevious(index: Int) {
        if (_state.value.chapters.none { it.index == index }) return
        val status = _state.value.statuses[index] ?: ChapterLoadStatus.NotLoaded
        if (status != ChapterLoadStatus.NotLoaded) return
        val token = beginLoad(index) ?: return
        try {
            loadChapter(index, null, restoreAnchor = false, makeActive = false)
        } finally {
            endLoad(index, token)
        }
    }

    private suspend fun beginLoad(index: Int): Any? =
        inFlightMutex.withLock {
            if (inFlight.containsKey(index)) null else Any().also { inFlight[index] = it }
        }

    /**
     * Cleanup must survive cancellation: a plain [withLock] in `finally` would
     * throw immediately on a cancelled coroutine and leave the marker stuck.
     */
    private suspend fun endLoad(index: Int, token: Any) {
        withContext(NonCancellable) {
            inFlightMutex.withLock {
                if (inFlight[index] === token) inFlight.remove(index)
            }
        }
    }

    suspend fun retry(index: Int) {
        loadChapter(
            index = index,
            saved = null,
            restoreAnchor = false,
            makeActive = index == _state.value.activeChapterIndex,
        )
    }

    fun onAnchorChanged(anchor: ReadingAnchor, offsetPx: Int) {
        applyAnchor(anchor, offsetPx)
    }

    /**
     * Moves the reader to a TTS cursor without touching [ReaderSessionState.scrollToken]:
     * speech follows text, it must not scroll the page underneath the highlight.
     */
    fun adoptAnchor(anchor: ReadingAnchor) {
        applyAnchor(anchor, _state.value.offsetPx)
    }

    private fun applyAnchor(anchor: ReadingAnchor, offsetPx: Int) {
        val current = _state.value
        if (current.restorePhase != RestorePhase.Ready) return
        val chapter = current.chapters.firstOrNull { it.chapterUrl == anchor.chapterUrl } ?: return
        val chapterChanged = chapter.index != current.activeChapterIndex
        _state.update {
            it.copy(anchor = anchor, offsetPx = offsetPx, activeChapterIndex = chapter.index)
        }
        if (chapterChanged) {
            MiruLog.event("reader", "chapter-changed", "chapter" to chapter.index)
            sessionTracker?.let { tracker ->
                scope.launch { tracker.switch(bookUrl, chapter.index) }
            }
            launchPrefetch()
        }
        scheduleSave()
    }

    fun setMode(mode: ReaderMode) {
        config.setMode(mode)
        _state.update { it.copy(mode = mode, scrollToken = it.scrollToken + 1) }
    }

    suspend fun onChapterFinished(index: Int) {
        readTracker.markFinished(bookUrl, index)
        if (index == _state.value.chapters.lastOrNull()?.index) {
            _state.update { it.copy(endOfBook = true) }
        }
    }

    /**
     * Resolves a TTS chapter hand-off. A natural end-of-chapter marks the
     * finished chapter read exactly once (auto-next or end of book); a manual
     * skip or a hand-off after Pause/Stop ([naturalCompletion] = false) never
     * marks read (FR-082/FR-083). Returns the target chapter index, or null at
     * the book's edge so the caller keeps the cursor where it was.
     *
     * [sessionChapter] is the chapter the SESSION speaks, not the visible one:
     * the feed may adopt the next chapter while speech still plays the
     * previous one, and marking the visible one would be wrong.
     */
    suspend fun onTtsChapterRequest(
        sessionChapter: Int,
        direction: TtsChapterDirection,
        naturalCompletion: Boolean,
    ): Int? {
        if (naturalCompletion) onChapterFinished(sessionChapter)
        return when (direction) {
            TtsChapterDirection.Next -> nextChapterIndex(sessionChapter)
            TtsChapterDirection.Previous -> previousChapterIndex(sessionChapter)
        }
    }

    /** Next/previous chapter for TTS hand-off; null keeps the last chapter finite. */
    fun nextChapterIndex(
        from: Int = _state.value.activeChapterIndex,
    ): Int? = adjacentChapterIndex(from, 1)

    fun previousChapterIndex(
        from: Int = _state.value.activeChapterIndex,
    ): Int? = adjacentChapterIndex(from, -1)

    private fun adjacentChapterIndex(from: Int, offset: Int): Int? {
        val chapters = _state.value.chapters
        val position = chapters.indexOfFirst { it.index == from }
        if (position < 0) return null
        return chapters.getOrNull(position + offset)?.index
    }

    fun flush() {
        saveJob?.cancel()
        scope.launch {
            persistNow()
            sessionTracker?.stop()
        }
    }

    fun pauseSession() {
        scope.launch { sessionTracker?.stop() }
    }

    fun resumeSession() {
        if (_state.value.restorePhase != RestorePhase.Ready) return
        scope.launch { sessionTracker?.resume() }
    }

    private suspend fun loadChapter(
        index: Int,
        saved: SavedReading?,
        restoreAnchor: Boolean,
        makeActive: Boolean,
        atEnd: Boolean = false,
    ): ReaderDocument? = try {
        loadChapterInternal(index, saved, restoreAnchor, makeActive, atEnd)
    } catch (cancelled: CancellationException) {
        // A cancelled load must not leave the chapter Loading forever: the top
        // gap would spin with no retry. Real Ready/Failed statuses are kept.
        resetCancelledLoad(index)
        throw cancelled
    }

    private suspend fun loadChapterInternal(
        index: Int,
        saved: SavedReading?,
        restoreAnchor: Boolean,
        makeActive: Boolean,
        atEnd: Boolean = false,
    ): ReaderDocument? {
        if (_state.value.chapters.none { it.index == index }) return null
        setStatus(index, ChapterLoadStatus.Loading)
        val startedAtMs = System.currentTimeMillis()
        var failure: ReaderLoadFailure? = null
        val document = try {
            // Prefetch/previous loads must not move "last read"; only the
            // chapter the user is actually reading touches history.
            if (makeActive) {
                content.document(bookUrl, index)
            } else {
                content.documentForPrefetch(bookUrl, index)
            }
        } catch (cancelled: CancellationException) {
            // Cancellation is not a chapter error; the outer guard resets the
            // Loading status so the gap can retry.
            throw cancelled
        } catch (loadFailure: ReaderLoadFailure) {
            failure = loadFailure
            MiruLog.e("reader", "chapter load failed index=$index error=ReaderLoadFailure")
            null
        } catch (throwable: Throwable) {
            // A stack trace can quote a chapter URL/query; log the class only.
            MiruLog.e(
                "reader",
                "chapter load failed index=$index error=${throwable::class.simpleName}",
            )
            null
        }
        if (document == null) {
            MiruLog.event(
                "reader",
                "chapter-load",
                "chapter" to index,
                "ok" to false,
                "ms" to (System.currentTimeMillis() - startedAtMs),
            )
            MiruLog.w("reader", "chapter failed index=$index")
            setStatus(
                index,
                ChapterLoadStatus.Failed(
                    message = failure?.message ?: "Could not load chapter",
                    title = failure?.title?.takeIf { it.isNotBlank() },
                    authUrl = failure?.authUrl?.takeIf { it.isNotBlank() },
                ),
            )
            // First open has no previous feed; without this the screen shows
            // an endless spinner instead of the Failed gap with Retry/login.
            rebuildFeed()
            return null
        }
        MiruLog.event(
            "reader",
            "chapter-load",
            "chapter" to index,
            "ok" to true,
            "blocks" to document.blocks.size,
            "chars" to document.blocks.sumOf { it.original.length },
            "ms" to (System.currentTimeMillis() - startedAtMs),
        )

        val savedAnchor = saved?.anchor
        val restored = restoreAnchor &&
            savedAnchor != null &&
            savedAnchor.chapterUrl == document.chapterUrl &&
            document.blockAt(savedAnchor.blockId) != null
        val anchor = when {
            atEnd -> document.blocks.lastOrNull()?.let {
                ReadingAnchor(document.chapterUrl, it.id, it.original.length)
            }
            restored -> savedAnchor
            else -> document.firstAnchor()
        }
        val offset = if (!atEnd && restored) saved.offsetPx else 0

        // Cached translation lands before the document/Ready emission so the
        // first rendered frame is already translated; a miss changes nothing.
        if (makeActive) preloadActiveChapter?.invoke(index, document)

        _state.update {
            it.copy(
                documents = it.documents + (index to document),
                statuses = it.statuses + (index to ChapterLoadStatus.Ready),
                activeChapterIndex = if (makeActive) index else it.activeChapterIndex,
                anchor = if (makeActive) anchor else it.anchor,
                offsetPx = if (makeActive) offset else it.offsetPx,
            )
        }
        rebuildFeed()
        if (makeActive) persistNow()
        return document
    }

    private fun setStatus(index: Int, status: ChapterLoadStatus) {
        _state.update { it.copy(statuses = it.statuses + (index to status)) }
    }

    /**
     * Runs under NonCancellable because a cancelled coroutine cannot take the
     * state mutex or publish the NotLoaded feed: the gap would stay Loading.
     */
    private suspend fun resetCancelledLoad(index: Int) {
        withContext(NonCancellable) {
            if (_state.value.statuses[index] != ChapterLoadStatus.Loading) return@withContext
            _state.update {
                it.copy(statuses = it.statuses + (index to ChapterLoadStatus.NotLoaded))
            }
            rebuildFeed()
        }
    }

    private fun rebuildFeed() {
        val current = _state.value
        val plan = FeedPlanner.plan(
            chapters = current.chapters,
            documents = current.documents,
            statuses = current.statuses,
            anchor = current.anchor,
        )
        _state.update { it.copy(feed = plan) }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        val current = _state.value
        val anchor = current.anchor ?: return
        if (bookUrl.isBlank()) return
        seq += 1
        positions.save(
            bookUrl,
            SavedReading(
                anchor = anchor,
                offsetPx = current.offsetPx,
                seq = seq,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        MiruLog.d(
            "reader",
            "position saved chapter=${anchor.chapterUrl} block=${anchor.blockId.index} seq=$seq",
        )
    }
}
