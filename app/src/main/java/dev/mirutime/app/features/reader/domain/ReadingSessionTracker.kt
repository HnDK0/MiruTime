package dev.mirutime.app.features.reader.domain

import dev.mirutime.data.library.ReadingSessionEntity
import dev.mirutime.feature.reader.api.ReaderSessionTracker
import java.util.UUID

/**
 * Records active reading intervals. The tracker only knows stable ids; URL /
 * chapter lookup and persistence are injected, which keeps the timing logic
 * testable without Room or Android.
 */
class ReadingSessionTracker(
    private val resolveIds: suspend (bookUrl: String, chapterIndex: Int) -> Pair<Long, Long?>?,
    private val saveSession: suspend (ReadingSessionEntity) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000L },
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val kind: String = KIND_READING,
) : ReaderSessionTracker {
    private var currentBookUrl: String = ""
    private var currentChapterIndex: Int = 0
    private var activeBookUrl: String = ""
    private var activeChapterIndex: Int = 0
    private var active = false
    private var bookId: Long = 0
    private var chapterId: Long? = null
    private var startedAt: Long = 0
    private var startedMono: Long = 0
    private var sessionKey: String? = null

    override suspend fun start(bookUrl: String, chapterIndex: Int) {
        currentBookUrl = bookUrl
        currentChapterIndex = chapterIndex
        if (active && activeBookUrl == bookUrl && activeChapterIndex == chapterIndex) return
        stop()
        val ids = resolveIds(bookUrl, chapterIndex) ?: return
        bookId = ids.first
        chapterId = ids.second
        activeBookUrl = bookUrl
        activeChapterIndex = chapterIndex
        startedAt = wallClock()
        startedMono = monotonic()
        sessionKey = newKey()
        active = true
    }

    override suspend fun switch(bookUrl: String, chapterIndex: Int) {
        stop()
        start(bookUrl, chapterIndex)
    }

    override suspend fun stop() {
        if (!active) return
        active = false
        val duration = (monotonic() - startedMono).coerceAtLeast(0L)
        val key = sessionKey
        val ended = wallClock()
        // A zero-length interval is noise; keep the key semantics for real
        // intervals only.
        if (duration > 0L && key != null) {
            saveSession(
                ReadingSessionEntity(
                    bookId = bookId,
                    chapterId = chapterId,
                    startedAt = startedAt,
                    endedAt = ended,
                    durationMs = duration,
                    kind = kind,
                    sessionKey = key,
                ),
            )
        }
        sessionKey = null
    }

    override suspend fun resume() {
        if (currentBookUrl.isBlank()) return
        start(currentBookUrl, currentChapterIndex)
    }

    companion object {
        const val KIND_READING = "reading"
        const val KIND_TTS = "tts"
    }
}
