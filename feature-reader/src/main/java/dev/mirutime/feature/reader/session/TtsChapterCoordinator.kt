package dev.mirutime.feature.reader.session

import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.reader.api.ReaderTranslationPort
import dev.mirutime.feature.reader.api.translationEnsureTargets
import dev.mirutime.feature.reader.api.ttsHandoffApplies
import dev.mirutime.feature.tts.TtsChapterDirection
import dev.mirutime.feature.tts.TtsHandoffSource
import dev.mirutime.feature.tts.TtsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the TTS chapter hand-off outside the reader composition. The controller
 * only asks for the adjacent chapter; this coordinator loads it, ensures its
 * translation and restarts speech, even when the Activity is stopped or being
 * recreated. It also owns the foreground-service lifecycle so playback does not
 * depend on a recomposition.
 */
class TtsChapterCoordinator(
    private val tts: TtsHandoffSource,
    private val session: ReaderSessionStore,
    private val translation: ReaderTranslationPort,
    private val scope: CoroutineScope,
    private val startPlayback: (
        bookTitle: String,
        chapterTitle: String,
        coverUrl: String?,
        chapterIndex: Int,
        chapterCount: Int,
    ) -> Unit,
    private val stopPlayback: () -> Unit,
) {
    sealed interface Event {
        data object EndOfBook : Event
        data class Failed(val reason: String) : Event
    }

    private val _handoffInProgress = MutableStateFlow(false)
    val handoffInProgress: StateFlow<Boolean> = _handoffInProgress.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val jobs = mutableListOf<Job>()
    private var handoffJob: Job? = null
    // A cancelled hand-off still runs its finally block; only the latest
    // generation may clear handoffInProgress or touch the session.
    private var handoffGeneration = 0

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            tts.chapterRequests.collect { direction ->
                val generation = ++handoffGeneration
                handoffJob?.cancel()
                handoffJob = scope.launch { handleHandoff(direction, generation) }
            }
        }
        jobs += scope.launch { observePlayback() }
        jobs += scope.launch { observeTranslations() }
        jobs += scope.launch { observeFinishedChapter() }
    }

    /** Stops playback without tearing down the app-scoped collectors. */
    fun stop() {
        handoffGeneration += 1
        handoffJob?.cancel()
        handoffJob = null
        _handoffInProgress.value = false
        tts.stop()
        stopPlayback()
    }

    /** Test/lifecycle teardown: cancels the app-scoped collectors too. */
    fun close() {
        handoffGeneration += 1
        handoffJob?.cancel()
        handoffJob = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        _handoffInProgress.value = false
    }

    /** Re-publishes the FGS after a late notification permission grant. */
    fun ensurePlayback() {
        val status = tts.state.value.status
        if (status == TtsStatus.Playing || status == TtsStatus.Loading) {
            publishStart(session.state.value)
        }
    }

    private suspend fun handleHandoff(direction: TtsChapterDirection, generation: Int) {
        if (generation != handoffGeneration) return
        val sessionChapter = sessionChapter()
        val target = session.onTtsChapterRequest(
            sessionChapter = sessionChapter,
            direction = direction,
            naturalCompletion = tts.chapterEndedNaturally,
        )
        if (target == null) {
            when (direction) {
                TtsChapterDirection.Previous -> tts.resume()
                TtsChapterDirection.Next -> {
                    tts.stop()
                    _events.tryEmit(Event.EndOfBook)
                }
            }
            return
        }

        val previousAnchor = session.state.value.anchor?.takeIf { anchor ->
            anchor.chapterUrl == session.state.value.documents[sessionChapter]?.chapterUrl
        }
        if (generation != handoffGeneration) return
        _handoffInProgress.value = true
        MiruLog.event(
            "tts",
            "handoff-request",
            "direction" to direction.name,
            "chapter" to sessionChapter,
            "target" to target,
        )
        try {
            val document = session.goToChapterForTts(target)
            if (generation != handoffGeneration) return
            if (document == null) {
                MiruLog.event("tts", "handoff-failed", "chapter" to target)
                _events.tryEmit(Event.Failed("chapter load"))
                tts.stop()
                return
            }
            val bookUrl = session.state.value.book?.bookUrl.orEmpty()
            if (!ttsHandoffApplies(session.state.value.activeChapterIndex, target)) {
                tts.stop()
                return
            }
            translation.ensureChapter(bookUrl, target, document)
            nextLoadedChapter(target)?.let { (index, next) ->
                translation.ensureChapter(bookUrl, index, next)
            }
            val mode = translation.activeMode(bookUrl)
            val available = if (mode?.hasPair == true) translation.state.value else emptyMap()
            if (generation != handoffGeneration) return
            val started = tts.playHandoff(
                document,
                available[document.chapterUrl]?.translations.orEmpty(),
            )
            if (!started) {
                // Timer/Stop/Pause dropped the request: return to the chapter
                // and cursor that were actually spoken, without a hard scroll.
                val revertChapter = revertChapter(sessionChapter)
                session.goToChapterForTts(revertChapter)
                val revertAnchor = tts.lastStoppedSession?.takeIf {
                    it.bookUrl == bookUrl
                }?.let {
                    ReadingAnchor(
                        chapterUrl = it.chapterUrl,
                        blockId = dev.mirutime.app.features.reader.document.BlockId(
                            it.chapterUrl,
                            it.blockIndex,
                        ),
                        charOffset = it.charOffset,
                        sentenceIndex = it.sentenceIndex,
                    )
                } ?: previousAnchor
                revertAnchor?.let(session::adoptAnchor)
                _events.tryEmit(Event.Failed("handoff rejected"))
            } else {
                MiruLog.event("tts", "handoff-start", "chapter" to target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            MiruLog.event(
                "tts",
                "handoff-error",
                "class" to (error::class.simpleName ?: "unknown"),
            )
            _events.tryEmit(Event.Failed("handoff"))
            tts.stop()
        } finally {
            if (generation == handoffGeneration) _handoffInProgress.value = false
        }
    }

    private fun sessionChapter(): Int {
        val chapterUrl = tts.state.value.blockId?.chapterUrl
        return session.state.value.chapters.firstOrNull { it.chapterUrl == chapterUrl }?.index
            ?: session.state.value.activeChapterIndex
    }

    private fun revertChapter(previous: Int): Int {
        val current = session.state.value
        return current.chapters.firstOrNull { it.index == previous }?.index
            ?: current.activeChapterIndex
    }

    private fun nextLoadedChapter(from: Int): Pair<Int, dev.mirutime.app.features.reader.document.ReaderDocument>? {
        val state = session.state.value
        val nextIndex = state.chapters.firstOrNull { it.index > from }?.index ?: return null
        val document = state.documents[nextIndex] ?: return null
        return nextIndex to document
    }

    private suspend fun observePlayback() {
        combine(
            tts.state,
            session.state,
            translation.metadata,
        ) { ttsState, sessionState, metadata ->
            val book = sessionState.book ?: return@combine PlaybackKey.empty(ttsState.status)
            val chapter = sessionState.chapters.firstOrNull {
                it.index == sessionState.activeChapterIndex
            } ?: return@combine PlaybackKey.empty(ttsState.status)
            PlaybackKey(
                status = ttsState.status,
                bookTitle = metadata[book.bookUrl]?.title ?: book.title,
                chapterTitle = metadata[book.bookUrl]?.chapterTitles?.get(chapter.index)
                    ?: chapter.title,
                coverUrl = book.coverUrl,
                chapterIndex = chapter.index,
                chapterCount = sessionState.chapters.size,
            )
        }
            .distinctUntilChanged()
            .collect { key ->
                when (key.status) {
                    TtsStatus.Playing, TtsStatus.Loading -> publish(key)
                    TtsStatus.Idle, TtsStatus.Failed -> stopPlayback()
                    TtsStatus.Paused -> Unit
                }
            }
    }

    private suspend fun observeTranslations() {
        session.state
            .map { state ->
                Triple(
                    state.restorePhase,
                    state.activeChapterIndex,
                    state.documents.keys.toSet() to state.book?.bookUrl,
                )
            }
            .distinctUntilChanged()
            .collect { (restorePhase, active, documentsAndBook) ->
                if (restorePhase != RestorePhase.Ready) return@collect
                val (documentKeys, bookUrl) = documentsAndBook
                if (bookUrl.isNullOrBlank()) return@collect
                val documents = session.state.value.documents
                translationEnsureTargets(active, documentKeys).forEach { index ->
                    documents[index]?.let { translation.ensureChapter(bookUrl, index, it) }
                }
            }
    }

    private suspend fun observeFinishedChapter() {
        tts.state
            .map { it.status to it.atEnd }
            .distinctUntilChanged()
            .collect { (status, atEnd) ->
                if (status != TtsStatus.Idle || !atEnd) return@collect
                val chapterUrl = tts.state.value.blockId?.chapterUrl ?: return@collect
                val index = session.state.value.chapters
                    .firstOrNull { it.chapterUrl == chapterUrl }
                    ?.index
                    ?: return@collect
                session.onChapterFinished(index)
            }
    }

    private fun publishStart(state: ReaderSessionState) {
        val book = state.book ?: return
        val chapter = state.chapters.firstOrNull { it.index == state.activeChapterIndex } ?: return
        publish(
            PlaybackKey(
                status = tts.state.value.status,
                bookTitle = translation.metadata.value[book.bookUrl]?.title ?: book.title,
                chapterTitle = translation.metadata.value[book.bookUrl]
                    ?.chapterTitles
                    ?.get(chapter.index)
                    ?: chapter.title,
                coverUrl = book.coverUrl,
                chapterIndex = chapter.index,
                chapterCount = state.chapters.size,
            ),
        )
    }

    private fun publish(key: PlaybackKey) {
        if (key.bookTitle.isBlank()) return
        runCatching {
            startPlayback(
                key.bookTitle,
                key.chapterTitle,
                key.coverUrl,
                key.chapterIndex,
                key.chapterCount,
            )
        }.onFailure { error ->
            MiruLog.event(
                "tts",
                "tts-service-start",
                "ok" to false,
                "class" to (error::class.simpleName ?: "unknown"),
            )
        }
    }

    private data class PlaybackKey(
        val status: TtsStatus,
        val bookTitle: String,
        val chapterTitle: String,
        val coverUrl: String?,
        val chapterIndex: Int,
        val chapterCount: Int,
    ) {
        companion object {
            fun empty(status: TtsStatus) = PlaybackKey(status, "", "", null, 0, 0)
        }
    }
}
