package dev.mirutime.feature.reader.ui

import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.FeedEntry
import dev.mirutime.feature.reader.FeedPlan
import dev.mirutime.feature.reader.session.ReaderSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedScrollTriggerTest {
    private fun state(
        entries: List<FeedEntry>,
        scrollToken: Int = 0,
    ) = ReaderSessionState(
        feed = FeedPlan(
            entries = entries,
            anchorEntryId = null,
            chapterEntryRanges = emptyMap(),
            lastChapterIndex = entries.lastOrNull()?.chapterIndex,
        ),
        scrollToken = scrollToken,
    )

    @Test
    fun `loading a previous chapter does not retrigger the anchor scroll`() {
        val before = state(listOf(FeedEntry.Header("h:2", 2, "Chapter 2")))
        val prepended = state(
            listOf(
                FeedEntry.Gap(
                    id = "gap:top:1",
                    chapterIndex = 1,
                    title = "Chapter 1",
                    status = ChapterLoadStatus.Ready,
                    top = true,
                ),
                FeedEntry.Header("h:2", 2, "Chapter 2"),
            ),
        )

        assertEquals(
            feedScrollTrigger(before, topPaddingPx = 24f),
            feedScrollTrigger(prepended, topPaddingPx = 24f),
        )
    }

    @Test
    fun `an explicit scroll token still moves to the anchor`() {
        val state = state(emptyList())

        assertNotEquals(
            feedScrollTrigger(state, topPaddingPx = 24f),
            feedScrollTrigger(state.copy(scrollToken = 1), topPaddingPx = 24f),
        )
    }

    @Test
    fun `tts follow keeps the page still while the block is fully on screen`() {
        assertFalse(ttsFollowNeedsScroll(true, offsetPx = 0, sizePx = 100, viewportTopPx = 0, viewportBottomPx = 1000))
        assertFalse(ttsFollowNeedsScroll(true, offsetPx = 600, sizePx = 300, viewportTopPx = 0, viewportBottomPx = 1000))
        assertFalse(ttsFollowNeedsScroll(true, offsetPx = 900, sizePx = 100, viewportTopPx = 0, viewportBottomPx = 1000))
    }

    @Test
    fun `tts follow advances once the block leaves or is cut by the screen`() {
        assertTrue(ttsFollowNeedsScroll(false, offsetPx = 0, sizePx = 0, viewportTopPx = 0, viewportBottomPx = 1000))
        assertTrue(ttsFollowNeedsScroll(true, offsetPx = 950, sizePx = 200, viewportTopPx = 0, viewportBottomPx = 1000))
        assertTrue(ttsFollowNeedsScroll(true, offsetPx = -50, sizePx = 200, viewportTopPx = 0, viewportBottomPx = 1000))
    }

    @Test
    fun `tts follow leaves a block taller than the screen alone while it shows`() {
        assertFalse(ttsFollowNeedsScroll(true, offsetPx = -300, sizePx = 1500, viewportTopPx = 0, viewportBottomPx = 1000))
    }

    @Test
    fun `tts follow never yanks the screen back after the user scrolled away`() {
        assertTrue(ttsFollowAttached(hadPrevious = false, previousInFeed = false, previousVisible = false))
        assertTrue(ttsFollowAttached(hadPrevious = true, previousInFeed = true, previousVisible = true))
        assertTrue(ttsFollowAttached(hadPrevious = true, previousInFeed = false, previousVisible = false))
        assertFalse(ttsFollowAttached(hadPrevious = true, previousInFeed = true, previousVisible = false))
    }

    @Test
    fun `far tts jumps land immediately`() {
        assertTrue(ttsFollowUsesAnimation(0))
        assertTrue(ttsFollowUsesAnimation(5))
        assertFalse(ttsFollowUsesAnimation(6))
    }
}
