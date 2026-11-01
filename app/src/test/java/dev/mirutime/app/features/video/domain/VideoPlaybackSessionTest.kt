package dev.mirutime.app.features.video.domain

import dev.mirutime.app.features.reader.domain.ReadingSessionTracker
import dev.mirutime.data.library.ReadingSessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackSessionTest {

    @Test
    fun `playing records reading time and stop closes the session`() = runTest {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var mono = 0L
        val session = VideoPlaybackSession(
            tracker = tracker(sessions, monoClock = { mono }),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        session.onPlaying("https://site/book", 1)
        advanceUntilIdle()
        mono = 5_000L
        session.onStopped()
        session.shutdown()
        advanceUntilIdle()

        assertEquals(1, sessions.size)
        assertEquals(5_000L, sessions.single().durationMs)
        assertEquals(1L, sessions.single().chapterId)
        assertEquals("reading", sessions.single().kind)
    }

    @Test
    fun `chapter change closes the old session before opening the next`() = runTest {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var mono = 0L
        val session = VideoPlaybackSession(
            tracker = tracker(sessions, monoClock = { mono }),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        session.onPlaying("https://site/book", 1)
        advanceUntilIdle()
        mono = 2_000L
        session.onPlaying("https://site/book", 2)
        advanceUntilIdle()
        mono = 3_500L
        session.onStopped()
        session.shutdown()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), sessions.map { it.chapterId })
        assertEquals(listOf(2_000L, 1_500L), sessions.map { it.durationMs })
    }

    @Test
    fun `a pause queued after play never leaves the session open`() = runTest {
        val sessions = mutableListOf<ReadingSessionEntity>()
        var mono = 0L
        val session = VideoPlaybackSession(
            tracker = tracker(sessions, monoClock = { mono }),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        session.onPlaying("https://site/book", 1)
        session.onStopped()
        session.shutdown()
        advanceUntilIdle()

        assertEquals(0, sessions.size)
    }

    private fun tracker(
        sessions: MutableList<ReadingSessionEntity>,
        monoClock: () -> Long,
    ) = ReadingSessionTracker(
        resolveIds = { _, index -> 10L to index.toLong() },
        saveSession = { sessions += it },
        wallClock = monoClock,
        monotonic = monoClock,
        newKey = { "key-${sessions.size}" },
    )
}
