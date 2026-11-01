package dev.mirutime.app.features.reader.domain

import dev.mirutime.data.library.ReadingSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSessionTrackerTest {
    @Test
    fun `pause and resume record monotonic durations`() = runBlocking {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var wall = 1_000L
        var mono = 0L
        var key = 0
        val tracker = ReadingSessionTracker(
            resolveIds = { _, index -> 10L to index.toLong() },
            saveSession = { sessions += it },
            wallClock = { wall },
            monotonic = { mono },
            newKey = { "session-${key++}" },
        )

        tracker.start("https://site/book", 1)
        mono = 5_000L
        wall = 6_000L
        tracker.stop()

        assertEquals(1, sessions.size)
        assertEquals(5_000L, sessions.single().durationMs)
        assertEquals(1_000L, sessions.single().startedAt)
        assertEquals(6_000L, sessions.single().endedAt)
        assertEquals("reading", sessions.single().kind)
        assertEquals("session-0", sessions.single().sessionKey)

        tracker.resume()
        mono = 8_000L
        wall = 9_000L
        tracker.stop()

        assertEquals(2, sessions.size)
        assertEquals(3_000L, sessions.last().durationMs)
        assertEquals("session-1", sessions.last().sessionKey)
    }

    @Test
    fun `tts tracker writes the tts kind`() = runBlocking {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var mono = 0L
        val tracker = ReadingSessionTracker(
            resolveIds = { _, index -> 10L to index.toLong() },
            saveSession = { sessions += it },
            wallClock = { mono },
            monotonic = { mono },
            newKey = { "tts-key" },
            kind = ReadingSessionTracker.KIND_TTS,
        )

        tracker.start("https://site/book", 1)
        mono = 1_500L
        tracker.stop()

        assertEquals(1, sessions.size)
        assertEquals("tts", sessions.single().kind)
    }

    @Test
    fun `chapter switch closes the old session and opens a new one`() = runBlocking {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var mono = 0L
        val tracker = ReadingSessionTracker(
            resolveIds = { _, index -> 10L to index.toLong() },
            saveSession = { sessions += it },
            wallClock = { mono },
            monotonic = { mono },
            newKey = { "key-${sessions.size}" },
        )

        tracker.start("https://site/book", 1)
        mono = 2_000L
        tracker.switch("https://site/book", 2)
        mono = 4_000L
        tracker.stop()

        assertEquals(2, sessions.size)
        assertEquals(1L, sessions[0].chapterId)
        assertEquals(2_000L, sessions[0].durationMs)
        assertEquals(2L, sessions[1].chapterId)
        assertEquals(2_000L, sessions[1].durationMs)
    }

    @Test
    fun `stop is idempotent and zero intervals are not saved`() = runBlocking {
        val sessions = mutableListOf<ReadingSessionEntity>()
        val tracker = ReadingSessionTracker(
            resolveIds = { _, _ -> 10L to 20L },
            saveSession = { sessions += it },
            monotonic = { 0L },
        )

        tracker.start("https://site/book", 1)
        tracker.stop()
        tracker.stop()

        assertTrue(sessions.isEmpty())
    }
}
