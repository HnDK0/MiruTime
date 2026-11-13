package dev.mirutime.feature.reader.api

import dev.mirutime.feature.tts.TtsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTtsHandoffTest {
    private val book = listOf("book#chapter=1", "book#chapter=2")

    @Test
    fun `handoff applies only to its target chapter`() {
        assertTrue(ttsHandoffApplies(activeChapterIndex = 3, target = 3))
        assertFalse(ttsHandoffApplies(activeChapterIndex = 3, target = 4))
    }

    @Test
    fun `a paused session follows the visible chapter of its own book`() {
        assertEquals(
            PausedFollow.Seek,
            pausedFollowAction(
                status = TtsStatus.Paused,
                spokenChapterUrl = "book#chapter=1",
                visibleChapterUrl = "book#chapter=2",
                bookChapterUrls = book,
            ),
        )
    }

    @Test
    fun `the same chapter never moves the session`() {
        assertEquals(
            PausedFollow.None,
            pausedFollowAction(
                status = TtsStatus.Paused,
                spokenChapterUrl = "book#chapter=1",
                visibleChapterUrl = "book#chapter=1",
                bookChapterUrls = book,
            ),
        )
    }

    @Test
    fun `a paused session of another book is left alone`() {
        // The controller is app-scoped and survives Back: opening another book
        // must not drag the foreign cursor into it.
        assertEquals(
            PausedFollow.None,
            pausedFollowAction(
                status = TtsStatus.Paused,
                spokenChapterUrl = "other#chapter=7",
                visibleChapterUrl = "book#chapter=1",
                bookChapterUrls = book,
            ),
        )
    }

    @Test
    fun `running speech stops on a chapter change`() {
        for (status in listOf(TtsStatus.Playing, TtsStatus.Loading)) {
            assertEquals(
                status.name,
                PausedFollow.Stop,
                pausedFollowAction(
                    status = status,
                    spokenChapterUrl = "book#chapter=1",
                    visibleChapterUrl = "book#chapter=2",
                    bookChapterUrls = book,
                ),
            )
        }
    }

    @Test
    fun `a missing spoken chapter is no action`() {
        assertEquals(
            PausedFollow.None,
            pausedFollowAction(
                status = TtsStatus.Playing,
                spokenChapterUrl = null,
                visibleChapterUrl = "book#chapter=1",
                bookChapterUrls = book,
            ),
        )
    }

    @Test
    fun `leaving the reader keeps a paused session and stops the rest`() {
        assertFalse(shouldStopTtsOnExit(TtsStatus.Paused))
        assertTrue(shouldStopTtsOnExit(TtsStatus.Playing))
        assertTrue(shouldStopTtsOnExit(TtsStatus.Loading))
        assertTrue(shouldStopTtsOnExit(TtsStatus.Idle))
    }
}
