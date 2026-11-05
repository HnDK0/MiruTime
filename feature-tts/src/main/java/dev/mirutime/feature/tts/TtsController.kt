package dev.mirutime.feature.tts

import android.os.SystemClock
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.core.log.MiruLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TtsStatus { Idle, Loading, Playing, Paused, Failed }

/** Chapter hand-off the reader must resolve; the controller has no chapter list. */
enum class TtsChapterDirection { Next, Previous }

data class TtsState(
    val status: TtsStatus = TtsStatus.Idle,
    val currentChunk: Int = 0,
    val chunkCount: Int = 0,
    val blockId: BlockId? = null,
    val sentenceIndex: Int = 0,
    val rangeStart: Int = 0,
    val rangeEnd: Int = 0,
    val translated: Boolean = false,
    val atEnd: Boolean = false,
    /** NoveLA-style estimate; zero until the chunk text is loaded. */
    val estimatedTotalSeconds: Int = 0,
    val estimatedRemainingSeconds: Int = 0,
    val message: String? = null,
)

/**
 * Small in-app TTS player. It uses the platform engine, speaks stable
 * TtsChunker units, and exposes the current chunk/range for highlight.
 * The cursor is persisted so a process death restores a paused position.
 * Foreground playback/MediaSession/floating overlay are a later layer.
 */
class TtsController(
    private val engine: TtsEngine,
    private val scope: CoroutineScope,
    private val sessions: TtsSessionStore = InMemoryTtsSessionStore(),
    private val settingsStore: TtsSettingsStore = InMemoryTtsSettingsStore(),
    /** Monotonic clock; tests inject the virtual scheduler time. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load().sanitized())
    val settings: StateFlow<TtsSettings> = _settings.asStateFlow()

    private val _capabilities = MutableStateFlow(TtsCapabilities())
    val capabilities: StateFlow<TtsCapabilities> = _capabilities.asStateFlow()

    private val _chapterRequests = MutableSharedFlow<TtsChapterDirection>(extraBufferCapacity = 1)

    /**
     * Emitted when playback needs the reader to open another chapter (manual
     * skip or auto-next). The reader owns the chapter list, loads the document
     * and calls [play] so the controller never guesses what comes next.
     */
    val chapterRequests: SharedFlow<TtsChapterDirection> = _chapterRequests.asSharedFlow()

    private val _sleepTimer = MutableStateFlow<TtsSleepTimerState?>(null)

    /** Running sleep timer, or null. A tick updates only [TtsSleepTimerState.remainingSeconds]. */
    val sleepTimer: StateFlow<TtsSleepTimerState?> = _sleepTimer.asStateFlow()

    /** Stops playback after [minutes]. Setting a new timer replaces the old one. */
    fun setSleepTimer(minutes: Int) {
        if (!sleepTimerAvailable()) return
        clearSleepTimer()
        val seconds = clampSleepTimerMinutes(minutes) * 60
        sleepDeadline = now() + seconds * 1000L
        _sleepTimer.value = TtsSleepTimerState(
            mode = TtsSleepTimerMode.Minutes,
            remainingSeconds = seconds,
            totalSeconds = seconds,
            endsAtEpochMillis = System.currentTimeMillis() + seconds * 1000L,
        )
        startSleepTicker()
    }

    /** Stops at the end of the [chapters]th chapter counted from now. */
    fun setSleepTimerChapters(chapters: Int) {
        if (!sleepTimerAvailable()) return
        clearSleepTimer()
        val count = clampSleepTimerChapters(chapters)
        _sleepTimer.value = TtsSleepTimerState(
            mode = TtsSleepTimerMode.Chapters,
            chaptersRemaining = count,
            chaptersTotal = count,
        )
    }

    fun cancelSleepTimer() {
        clearSleepTimer()
    }

    /** A timer makes sense only while a TTS session exists and can still stop. */
    private fun sleepTimerAvailable(): Boolean =
        chunks.isNotEmpty() &&
            _state.value.status != TtsStatus.Idle &&
            _state.value.status != TtsStatus.Failed

    private fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepDeadline = 0L
        _sleepTimer.value = null
    }

    private fun startSleepTicker() {
        sleepTimerJob = scope.launch {
            while (true) {
                val left = ((sleepDeadline - now()) / 1000L).coerceAtLeast(0L).toInt()
                val current = _sleepTimer.value ?: return@launch
                if (current.mode == TtsSleepTimerMode.Minutes &&
                    current.remainingSeconds != left
                ) {
                    _sleepTimer.value = current.copy(remainingSeconds = left)
                }
                if (left <= 0) {
                    // resetPlayback drops a hand-off that was requested just
                    // before the deadline, so waiting for Loading is unnecessary.
                    _sleepTimer.value = null
                    resetPlayback(clearSession = false)
                    return@launch
                }
                delay(250)
            }
        }
    }

    /**
     * True only while the pending chapter request came from the queue finishing
     * the chapter (auto-next). A manual skip clears it first, so the reader
     * never marks a chapter read the user skipped through (FR-082/FR-083).
     */
    @Volatile
    var chapterEndedNaturally: Boolean = false
        private set

    private var ready = false
    private var pending: (() -> Unit)? = null
    private var chunks: List<TtsChunk> = emptyList()
    // Suffix sums of chunk text lengths: suffix[i] is "characters from chunk i
    // to the end". Rebuilt only when chunks change, so the timer estimate is
    // O(1) on every chunk/range update.
    private var chunkCharSuffix: IntArray = IntArray(0)
    private var current = 0
    private var active = false
    // Platform utterance ids are unique per queue (not chunk indexes), so a
    // late callback from a stopped queue cannot be mistaken for the current
    // one. Cleared whenever the queue is paused/skipped/replaced.
    private val utteranceChunks = HashMap<Int, Int>()
    private var nextUtteranceId = 0
    private var bookUrl = ""
    private var chapterIndex = 0
    private var voiceId: String? = _settings.value.voiceId
    private var speed = _settings.value.speed
    private var pitch = _settings.value.pitch
    private var sleepTimerJob: Job? = null
    private var sleepDeadline = 0L
    // Set when a chapter hand-off is requested and not yet applied. Stop, pause,
    // timer, close and explicit starts clear it, so a stale request can never
    // resurrect playback.
    @Volatile
    private var handoffPending = false
    // Snapshot for the reader's revert: stop() clears the persisted session, so
    // the last spoken cursor would otherwise be lost.
    private var _lastStoppedSession: TtsSession? = null

    /** Last session captured by a user Stop; null after a new start/resume. */
    val lastStoppedSession: TtsSession?
        get() = _lastStoppedSession

    fun play(document: ReaderDocument, translations: Map<Int, String> = emptyMap()) {
        playFrom(document, translations, blockIndex = null)
    }

    /**
     * Starts at the first spoken chunk at or after [blockIndex]. The reader
     * passes its visible anchor, so "Start here" never jumps back to the
     * chapter top. A null blockIndex keeps the ordinary chapter start.
     */
    fun playFrom(
        document: ReaderDocument,
        translations: Map<Int, String> = emptyMap(),
        blockIndex: Int?,
        charOffset: Int = 0,
    ) {
        handoffPending = false
        _lastStoppedSession = null
        val next = TtsChunker.chunks(document, translations)
        if (next.isEmpty()) {
            setState(TtsState(status = TtsStatus.Failed, message = "Nothing to read"))
            return
        }
        // A session speaks one language: pick the matching original/translated
        // voice slot before the engine is prepared (FR-041).
        applySavedSettings(next.firstOrNull()?.translated == true)
        stopInternal(keepStatus = true)
        chunks = next
        rebuildChunkCharStats()
        bookUrl = document.bookUrl
        chapterIndex = document.chapterIndex
        current = if (blockIndex == null) 0 else ttsStartIndex(next, blockIndex, charOffset)
        active = true
        MiruLog.event(
            "tts",
            "play",
            "chunks" to next.size,
            "chapter" to document.chapterIndex,
            "block" to blockIndex,
            "startChunk" to current,
        )
        persist(wasPlaying = true)
        if (!ready) {
            setState(chunkState(current, TtsStatus.Loading))
            pending = { speakFrom(current) }
        } else {
            speakFrom(current)
        }
    }

    /**
     * Moves the cursor to [blockIndex] while keeping the transport state: a
     * paused session only moves its cursor/highlight and stays paused, while a
     * playing one re-queues from the new block. The mini player's paragraph
     * arrows use this so a pause is never silently undone (FR-051).
     */
    fun seekToBlock(
        document: ReaderDocument,
        translations: Map<Int, String> = emptyMap(),
        blockIndex: Int,
        charOffset: Int = 0,
    ) {
        if (_state.value.status != TtsStatus.Paused) {
            playFrom(document, translations, blockIndex, charOffset)
            return
        }
        val next = TtsChunker.chunks(document, translations)
        if (next.isEmpty()) {
            setState(TtsState(status = TtsStatus.Failed, message = "Nothing to read"))
            return
        }
        applySavedSettings(next.firstOrNull()?.translated == true)
        stopInternal(keepStatus = true)
        pending = null
        chunks = next
        rebuildChunkCharStats()
        bookUrl = document.bookUrl
        chapterIndex = document.chapterIndex
        current = ttsStartIndex(next, blockIndex, charOffset)
        active = false
        MiruLog.event(
            "tts",
            "seek",
            "chunks" to next.size,
            "chapter" to document.chapterIndex,
            "block" to blockIndex,
            "startChunk" to current,
        )
        // Never starts speech: resume() is what moves a paused session.
        setState(chunkState(current, TtsStatus.Paused))
        persist(wasPlaying = false, immediate = true)
    }

    /**
     * Rebuilds chunks from the current document/translation state and restores
     * a paused cursor. Never starts speech, even if the session was playing.
     */
    fun restore(
        document: ReaderDocument,
        translations: Map<Int, String> = emptyMap(),
        session: TtsSession,
    ): Boolean {
        if (document.chapterUrl != session.chapterUrl) return false
        if (document.chapterIndex != session.chapterIndex) return false
        val next = TtsChunker.chunks(document, translations)
        val cursor = resolveTtsCursor(next, session) ?: return false
        stopInternal(keepStatus = true)
        pending = null
        chunks = next
        rebuildChunkCharStats()
        bookUrl = document.bookUrl.ifBlank { session.bookUrl }
        chapterIndex = document.chapterIndex
        current = cursor
        active = false
        applyEngine(_settings.value.engineId)
        voiceId = session.voiceId ?: _settings.value.voiceId
        speed = clampTtsSpeed(session.speed)
        pitch = clampTtsPitch(session.pitch)
        applyPlaybackSettings()
        setState(chunkState(cursor, TtsStatus.Paused))
        persist(wasPlaying = false, immediate = true)
        return true
    }

    /**
     * True only while the pause came from audio-focus loss (call, navigation
     * prompt). Every explicit pause path — notification, headset media button,
     * becoming-noisy, user — clears it, so a later focus gain can never undo a
     * user pause (FR-045/FR-046).
     */
    @Volatile
    var pausedByFocusLoss: Boolean = false
        private set

    fun pause() {
        pausedByFocusLoss = false
        pauseInternal(cancelSleepTimer = true)
    }

    /** Pause caused by a transient audio-focus loss; a focus gain may resume it. */
    fun pauseForFocusLoss() {
        if (!active && _state.value.status != TtsStatus.Loading) return
        pausedByFocusLoss = true
        pauseInternal(cancelSleepTimer = false)
    }

    /**
     * Pause not initiated by the user (headphone unplugged, service teardown).
     * The sleep timer keeps running: the user did not ask to cancel it.
     */
    fun pauseFromSystem() = pauseInternal(cancelSleepTimer = false)

    private fun pauseInternal(cancelSleepTimer: Boolean) {
        handoffPending = false
        if (cancelSleepTimer) clearSleepTimer()
        if (!active && _state.value.status != TtsStatus.Loading) return
        pending = null
        invalidateUtterances()
        engine.stop()
        active = false
        setState(_state.value.copy(status = TtsStatus.Paused))
        persist(wasPlaying = false, immediate = true)
    }

    fun resume() {
        handoffPending = false
        _lastStoppedSession = null
        pausedByFocusLoss = false
        if (chunks.isEmpty()) return
        active = true
        applyPlaybackSettings()
        persist(wasPlaying = true)
        if (!ready) {
            setState(chunkState(current, TtsStatus.Loading))
            pending = { speakFrom(current) }
            return
        }
        speakFrom(current)
    }

    /**
     * Stops the platform queue and resumes at the neighbouring chunk. While
     * paused the cursor still moves (and persists) without speaking; at an
     * edge the current chunk is restarted only when speech is already active.
     */
    fun skipNextChunk() = skipChunkBy(1)

    fun skipPreviousChunk() = skipChunkBy(-1)

    /**
     * Stops the current utterance immediately and asks the reader to load the
     * next/previous chapter. The reader calls [play] on the new document, so a
     * failed navigation can never leave the old chapter speaking in parallel.
     */
    fun skipNextChapter() = requestChapter(TtsChapterDirection.Next)

    fun skipPreviousChapter() = requestChapter(TtsChapterDirection.Previous)

    private fun skipChunkBy(delta: Int) {
        if (chunks.isEmpty()) return
        invalidateUtterances()
        val target = (current + delta).coerceIn(0, chunks.lastIndex)
        if (target == current) {
            if (active && ready) speakFrom(current)
            return
        }
        current = target
        if (active) {
            if (ready) {
                speakFrom(target)
            } else {
                setState(chunkState(target, TtsStatus.Loading))
                pending = { speakFrom(target) }
            }
        } else {
            setState(chunkState(target, TtsStatus.Paused))
            persist(wasPlaying = false, immediate = true)
        }
    }

    private fun requestChapter(direction: TtsChapterDirection) {
        if (chunks.isEmpty()) return
        // Manual skip: never a natural end, even when the cursor sits on the
        // last chunk (atEnd would be true there anyway).
        chapterEndedNaturally = false
        active = false
        pending = null
        invalidateUtterances()
        engine.stop()
        // Keep the old cursor paused so a rejected request can resume it.
        setState(chunkState(current, TtsStatus.Paused))
        persist(wasPlaying = false, immediate = true)
        requestHandoff(direction)
    }

    private fun requestHandoff(direction: TtsChapterDirection) {
        handoffPending = true
        _chapterRequests.tryEmit(direction)
    }

    fun toggle(document: ReaderDocument, translations: Map<Int, String> = emptyMap()) {
        when (_state.value.status) {
            TtsStatus.Playing, TtsStatus.Loading -> pause()
            TtsStatus.Paused -> resume()
            else -> play(document, translations)
        }
    }

    /**
     * Starts speech on a chapter the reader loaded for a [chapterRequests]
     * hand-off. A request is consumed only once: Stop, Pause, a new start or
     * the sleep timer clears it, so a stale hand-off cannot resurrect playback.
     */
    fun playHandoff(
        document: ReaderDocument,
        translations: Map<Int, String> = emptyMap(),
    ): Boolean {
        if (!handoffPending) return false
        handoffPending = false
        play(document, translations)
        return true
    }

    fun stop() {
        _lastStoppedSession = sessions.load()
        clearSleepTimer()
        resetPlayback(clearSession = true)
    }

    private fun resetPlayback(clearSession: Boolean) {
        handoffPending = false
        pausedByFocusLoss = false
        active = false
        pending = null
        invalidateUtterances()
        engine.stop()
        // Persist before chunks/bookUrl are cleared, or the cursor is lost.
        if (!clearSession) persist(wasPlaying = false, immediate = true)
        chunks = emptyList()
        chunkCharSuffix = IntArray(0)
        current = 0
        bookUrl = ""
        chapterIndex = 0
        if (clearSession) sessions.clear(immediate = true)
        setState(TtsState(status = TtsStatus.Idle))
    }

    fun close() {
        handoffPending = false
        clearSleepTimer()
        active = false
        pending = null
        invalidateUtterances()
        engine.stop()
        engine.shutdown()
        ready = false
        publishCapabilities()
    }

    /**
     * Persists and applies immediately; a restored session still wins later.
     * The UI calls this on release, so voice/rate/pitch changes stop and
     * re-queue the current chunk while appearance-only fields never interrupt
     * speech. An engine switch re-initializes and resumes the same chunk.
     */
    fun updateSettings(settings: TtsSettings) {
        val previous = _settings.value
        val sanitized = settings.sanitized()
        // The session speaks one language: edits apply to that slot.
        val sessionTranslated = chunks.firstOrNull()?.translated == true
        val previousSelection = previous.sanitized().voiceForSession(sessionTranslated)
        val nextSelection = sanitized.voiceForSession(sessionTranslated)
        // Only the slot this session speaks can affect playback: editing the
        // other language's voice must not restart the current chunk.
        val playbackChanged = ttsPlaybackChanged(previous, sanitized, sessionTranslated)
        val engineChanged = previousSelection.engineId != nextSelection.engineId
        _settings.value = sanitized
        settingsStore.save(sanitized)
        voiceId = nextSelection.voiceId
        speed = sanitized.speed
        pitch = sanitized.pitch

        if (engineChanged) {
            val wasActive = active
            invalidateUtterances()
            engine.stop()
            if (wasActive) {
                setState(chunkState(current, TtsStatus.Loading))
                pending = { speakFrom(current) }
            }
            val reinitializing = applyEngine(nextSelection.engineId)
            if (!reinitializing) {
                // Already on the requested engine (e.g. explicit default id);
                // no readiness callback will arrive, so resume now.
                pending = null
                applyPlaybackSettings()
                if (wasActive) speakFrom(current)
            }
        } else if (playbackChanged) {
            applyPlaybackSettings()
            if (active && ready && _state.value.status == TtsStatus.Playing) {
                speakFrom(current)
            }
        }
        refreshTimeEstimate()
        publishCapabilities()
    }

    fun resetSettings() {
        updateSettings(TtsSettings())
    }

    /**
     * Re-discovers installed engines (including ones installed while the app
     * is running). The voice picker calls this on open, so the catalog never
     * needs an app restart or a hardcoded engine list.
     */
    fun refreshEngines() {
        engine.refreshEngines()
        publishCapabilities()
    }

    /** Short sample with the current voice/rate/pitch; pauses a live reading. */
    fun testUtterance(text: String) {
        if (!ready) {
            setState(
                _state.value.copy(
                    status = TtsStatus.Failed,
                    message = "TTS unavailable",
                ),
            )
            return
        }
        if (active) pauseFromSystem()
        applyPlaybackSettings()
        engine.preview(text)
    }

    /** Retries after a failure; re-creates the engine when it never came up. */
    fun retry() {
        if (chunks.isEmpty()) {
            setState(TtsState())
            return
        }
        applySavedSettings(chunks.firstOrNull()?.translated == true)
        invalidateUtterances()
        setState(chunkState(current, TtsStatus.Loading))
        if (ready) {
            speakFrom(current)
        } else {
            pending = { speakFrom(current) }
            engine.restart()
        }
    }

    private fun speakFrom(index: Int) {
        if (!ready || chunks.isEmpty()) return
        invalidateUtterances()
        current = index.coerceIn(0, chunks.lastIndex)
        active = true
        setState(chunkState(current, TtsStatus.Playing))
        // Persist before the platform callback so skipping cannot lose the
        // cursor if speech never starts (engine race, process death).
        persist(wasPlaying = true)
        engine.stop()
        for (i in current until chunks.size) {
            val utteranceId = ++nextUtteranceId
            utteranceChunks[utteranceId] = i
            if (!engine.speak(chunks[i].text, utteranceId)) {
                setState(
                    _state.value.copy(
                        status = TtsStatus.Failed,
                        message = "TTS speak failed",
                    ),
                )
                active = false
                return
            }
        }
    }

    // Status/chunk transitions only; never the spoken text.
    private fun setState(next: TtsState) {
        val previous = _state.value
        _state.value = next
        if (previous.status != next.status || previous.currentChunk != next.currentChunk) {
            MiruLog.event("tts", "state", "status" to next.status, "chunk" to next.currentChunk)
        }
    }

    private fun chunkState(index: Int, status: TtsStatus): TtsState {
        val chunk = chunks.getOrNull(index) ?: return TtsState(status = status)
        val totalSeconds = ttsEstimatedSeconds(chunkCharSuffix.getOrElse(0) { 0 }, speed)
        val remainingSeconds = ttsEstimatedSeconds(
            chunkCharSuffix.getOrElse(index) { 0 },
            speed,
        )
        return TtsState(
            status = status,
            currentChunk = index,
            chunkCount = chunks.size,
            blockId = chunk.blockId,
            sentenceIndex = chunk.sentenceIndex,
            // Do not paint the whole sentence before the platform reports its
            // first word range; that looked like a full-sentence flash.
            rangeStart = chunk.startOffset,
            rangeEnd = if (status == TtsStatus.Playing || status == TtsStatus.Loading) {
                chunk.startOffset
            } else {
                chunk.endOffset
            },
            translated = chunk.translated,
            atEnd = index >= chunks.lastIndex,
            estimatedTotalSeconds = totalSeconds,
            estimatedRemainingSeconds = remainingSeconds,
        )
    }

    private fun rebuildChunkCharStats() {
        chunkCharSuffix = IntArray(chunks.size + 1)
        for (index in chunks.indices.reversed()) {
            chunkCharSuffix[index] = chunkCharSuffix[index + 1] + chunks[index].text.length
        }
    }

    /** Speed is live; a changed rate must not leave the old estimate behind. */
    private fun refreshTimeEstimate() {
        if (chunks.isEmpty()) return
        val total = ttsEstimatedSeconds(chunkCharSuffix.getOrElse(0) { 0 }, speed)
        val remaining = ttsEstimatedSeconds(chunkCharSuffix.getOrElse(current) { 0 }, speed)
        val state = _state.value
        if (state.estimatedTotalSeconds == total &&
            state.estimatedRemainingSeconds == remaining
        ) {
            return
        }
        setState(
            state.copy(
                estimatedTotalSeconds = total,
                estimatedRemainingSeconds = remaining,
            ),
        )
    }

    private fun stopInternal(keepStatus: Boolean) {
        pausedByFocusLoss = false
        active = false
        invalidateUtterances()
        engine.stop()
        if (!keepStatus) setState(TtsState())
    }

    private fun invalidateUtterances() {
        utteranceChunks.clear()
    }

    private fun onEngineReady() {
        ready = true
        applyPlaybackSettings()
        publishCapabilities()
        pending?.invoke()
        pending = null
    }

    private fun applySavedSettings(translated: Boolean = false) {
        val saved = settingsStore.load().sanitized()
        _settings.value = saved
        val selection = saved.voiceForSession(translated)
        voiceId = selection.voiceId
        speed = saved.speed
        pitch = saved.pitch
        applyEngine(selection.engineId)
        applyPlaybackSettings()
        publishCapabilities()
    }

    /** Returns true when the platform engine is being re-initialized. */
    private fun applyEngine(id: String?): Boolean {
        val target = id ?: engine.defaultEngineId
        if (target != null && target != engine.currentEngineId) {
            ready = false
            engine.selectEngine(target)
            return true
        }
        return false
    }

    private fun applyPlaybackSettings() {
        if (!ready) return
        speed = clampTtsSpeed(speed)
        pitch = clampTtsPitch(pitch)
        engine.setRate(speed)
        engine.setPitch(pitch)
        if (!engine.setVoice(voiceId)) {
            // A saved engine voice may be gone after an engine switch.
            voiceId = null
            engine.setVoice(null)
        }
    }

    private fun publishCapabilities() {
        _capabilities.value = TtsCapabilities(
            ready = ready,
            // Auxiliary engine scans can still provide a usable catalog while
            // the selected engine is down; the picker must offer another
            // engine instead of a dead "unavailable" wall.
            voices = engine.voices,
            engines = engine.engines,
            currentEngineId = engine.currentEngineId,
            defaultEngineId = engine.defaultEngineId,
        )
    }

    private fun persist(wasPlaying: Boolean, immediate: Boolean = false) {
        val chunk = chunks.getOrNull(current) ?: return
        if (bookUrl.isBlank()) return
        sessions.save(
            TtsSession(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                chapterUrl = chunk.blockId.chapterUrl,
                blockIndex = chunk.blockId.index,
                sentenceIndex = chunk.sentenceIndex,
                charOffset = chunk.startOffset,
                voiceId = voiceId,
                speed = speed,
                pitch = pitch,
                wasPlaying = wasPlaying,
                translated = chunk.translated,
                textHash = "${chunk.text.length}:${chunk.text.hashCode()}",
                updatedAt = System.currentTimeMillis(),
            ),
            immediate,
        )
    }

    private val listener = object : TtsEngineListener {
        override fun onReady(ok: Boolean) {
            if (ok) {
                onEngineReady()
            } else {
                active = false
                setState(TtsState(status = TtsStatus.Failed, message = "TTS unavailable"))
                pending = null
            }
        }

        override fun onVoicesChanged() {
            publishCapabilities()
        }

        override fun onStart(utteranceId: Int) {
            // Only utterances queued by the current speakFrom call are known;
            // a stopped/replaced queue no longer has any valid ids.
            val index = utteranceChunks[utteranceId] ?: return
            if (!active || index < current) return
            current = index
            setState(chunkState(index, TtsStatus.Playing))
            persist(wasPlaying = true)
        }

        override fun onDone(utteranceId: Int) {
            val index = utteranceChunks[utteranceId] ?: return
            if (index < chunks.lastIndex || !active) return
            active = false
            persist(wasPlaying = false, immediate = true)
            val timer = _sleepTimer.value
            if (timer?.mode == TtsSleepTimerMode.Chapters) {
                val left = timer.chaptersRemaining - 1
                if (left <= 0) {
                    clearSleepTimer()
                    invalidateUtterances()
                    setState(
                        _state.value.copy(
                            status = TtsStatus.Idle,
                            atEnd = true,
                            estimatedRemainingSeconds = 0,
                        ),
                    )
                    return
                }
                // The chapter timer continues chapters on its own, even when
                // auto-next is off: that is what "stop after N chapters" means.
                _sleepTimer.value = timer.copy(chaptersRemaining = left)
                chapterEndedNaturally = true
                setState(
                    _state.value.copy(
                        status = TtsStatus.Loading,
                        atEnd = true,
                        estimatedRemainingSeconds = 0,
                    ),
                )
                requestHandoff(TtsChapterDirection.Next)
                return
            }
            if (_settings.value.autoNextChapter) {
                // Keep the status active across the chapter load so the
                // foreground service is not torn down between chapters.
                chapterEndedNaturally = true
                setState(
                    _state.value.copy(
                        status = TtsStatus.Loading,
                        atEnd = true,
                        estimatedRemainingSeconds = 0,
                    ),
                )
                requestHandoff(TtsChapterDirection.Next)
            } else {
                // Playback ends here; a running minutes timer has nothing left
                // to stop and would only fire into an idle session.
                clearSleepTimer()
                setState(
                    _state.value.copy(
                        status = TtsStatus.Idle,
                        atEnd = true,
                        estimatedRemainingSeconds = 0,
                    ),
                )
            }
        }

        override fun onError() {
            handoffPending = false
            clearSleepTimer()
            setState(_state.value.copy(status = TtsStatus.Failed, message = "TTS error"))
            active = false
            persist(wasPlaying = false, immediate = true)
        }

        override fun onRangeStart(utteranceId: Int, start: Int, end: Int) {
            val index = utteranceChunks[utteranceId] ?: return
            val state = _state.value
            // Late callbacks from a paused/skipped/replaced queue are not in
            // the map (or no longer match the live chunk); never move the
            // highlight for them.
            if (!active || index != current || state.currentChunk != index) return
            val chunk = chunks.getOrNull(index) ?: return
            if (state.blockId != chunk.blockId) return
            val base = chunk.startOffset
            val consumed = start.coerceIn(0, chunk.text.length)
            val remainingChars = (chunkCharSuffix.getOrElse(index) { 0 } - consumed)
                .coerceAtLeast(0)
            setState(
                state.copy(
                    rangeStart = base + start,
                    rangeEnd = base + end,
                    estimatedRemainingSeconds = ttsEstimatedSeconds(remainingChars, speed),
                ),
            )
        }
    }

    init {
        engine.listener = listener
        publishCapabilities()
        if (engine.isReady) onEngineReady()
    }
}

/**
 * First chunk at or after the reader's visible block. Chunks are ordered by
 * block, so a heading/image without speech is skipped rather than failing.
 * [charOffset] only matters when the anchor points inside a spoken block.
 */
internal fun ttsStartIndex(
    chunks: List<TtsChunk>,
    blockIndex: Int,
    charOffset: Int,
): Int {
    if (chunks.isEmpty()) return 0
    if (charOffset > 0) {
        chunks.indexOfFirst { chunk ->
            chunk.blockId.index == blockIndex &&
                charOffset >= chunk.startOffset &&
                charOffset < chunk.endOffset
        }.takeIf { it >= 0 }?.let { return it }
    }
    return chunks.indexOfFirst { it.blockId.index >= blockIndex }
        .takeIf { it >= 0 }
        ?: chunks.lastIndex
}
