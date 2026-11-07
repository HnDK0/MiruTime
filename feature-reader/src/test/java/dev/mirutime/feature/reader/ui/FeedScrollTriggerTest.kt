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
    fun `tts follow skips a comfortable visible block`() {
        assertFalse(ttsFollowNeedsScroll(visible = true, offsetPx = 0, comfortOffsetPx = 200))
        assertFalse(ttsFollowNeedsScroll(visible = true, offsetPx = 200, comfortOffsetPx = 200))
        assertTrue(ttsFollowNeedsScroll(visible = true, offsetPx = 201, comfortOffsetPx = 200))
        assertTrue(ttsFollowNeedsScroll(visible = false, offsetPx = 0, comfortOffsetPx = 200))
    }

    @Test
    fun `far tts jumps land immediately`() {
        assertTrue(ttsFollowUsesAnimation(0))
        assertTrue(ttsFollowUsesAnimation(5))
        assertFalse(ttsFollowUsesAnimation(6))
    }
}
