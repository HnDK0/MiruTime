package dev.mirutime.app.features.book.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterScrollbarTest {

    private val track = 480f
    private val thumbMin = 40f
    private val thumb = 40f

    @Test
    fun `empty list has no thumb`() {
        assertEquals(
            0f,
            chapterScrollbarThumbHeight(
                trackHeight = track,
                visibleItemCount = 0,
                itemCount = 0,
                minThumbHeight = thumbMin,
            ),
            0.001f,
        )
        assertEquals(0f, chapterScrollbarThumbTop(track, 0f, 0, 0), 0.001f)
        assertEquals(0, chapterScrollbarIndexForThumbTop(0f, track, 0f, 0))
        assertEquals(0, chapterScrollbarIndexForTap(120f, track, 0f, 0))
    }

    @Test
    fun `a short list fills the track and never scrolls`() {
        assertEquals(
            track,
            chapterScrollbarThumbHeight(
                trackHeight = track,
                visibleItemCount = 5,
                itemCount = 5,
                minThumbHeight = thumbMin,
            ),
            0.001f,
        )
        assertEquals(0f, chapterScrollbarThumbTop(track, track, 0, 5), 0.001f)
    }

    @Test
    fun `thumb never drops below its minimum height`() {
        assertEquals(
            thumbMin,
            chapterScrollbarThumbHeight(
                trackHeight = track,
                visibleItemCount = 7,
                itemCount = 3226,
                minThumbHeight = thumbMin,
            ),
            0.001f,
        )
    }

    @Test
    fun `thumb grows with the visible share of the list`() {
        assertEquals(
            48f,
            chapterScrollbarThumbHeight(
                trackHeight = track,
                visibleItemCount = 100,
                itemCount = 1000,
                minThumbHeight = thumbMin,
            ),
            0.001f,
        )
    }

    @Test
    fun `first and last visible items map to the track ends`() {
        assertEquals(0f, chapterScrollbarThumbTop(track, thumb, 0, 100), 0.001f)
        assertEquals(track - thumb, chapterScrollbarThumbTop(track, thumb, 99, 100), 0.001f)
        assertEquals(0, chapterScrollbarIndexForThumbTop(0f, track, thumb, 100))
        assertEquals(99, chapterScrollbarIndexForThumbTop(track - thumb, track, thumb, 100))
    }

    @Test
    fun `drag positions outside the track are clamped`() {
        assertEquals(0, chapterScrollbarIndexForThumbTop(-50f, track, thumb, 100))
        assertEquals(99, chapterScrollbarIndexForThumbTop(999f, track, thumb, 100))
        assertEquals(0f, chapterScrollbarThumbTop(track, thumb, -10, 100), 0.001f)
        assertEquals(track - thumb, chapterScrollbarThumbTop(track, thumb, 200, 100), 0.001f)
    }

    @Test
    fun `tapping the track maps top, middle and bottom to indices`() {
        assertEquals(0, chapterScrollbarIndexForTap(0f, track, thumb, 100))
        assertEquals(50, chapterScrollbarIndexForTap(track / 2f, track, thumb, 100))
        assertEquals(99, chapterScrollbarIndexForTap(track, track, thumb, 100))
    }

    @Test
    fun `one item is always index zero`() {
        assertEquals(0, chapterScrollbarIndexForThumbTop(track, track, thumb, 1))
        assertEquals(0, chapterScrollbarIndexForTap(track, track, thumb, 1))
    }

    @Test
    fun `visual line is an 8dp line inside the 48dp touch lane`() {
        assertEquals(8f, ChapterScrollbarVisualWidth.value, 0.001f)
        assertEquals(48f, ChapterScrollbarLaneWidth.value, 0.001f)
    }

    @Test
    fun `row icons end at the 16dp gutter and the rail starts exactly there`() {
        val canvas = 480f
        val gutter = ChapterRowEndGutter.value
        assertEquals(16f, gutter, 0.001f)

        val line = chapterScrollbarLine(
            canvasWidthPx = canvas,
            edgeInsetPx = ChapterScrollbarEdgeInset.value,
            visualWidthPx = ChapterScrollbarVisualWidth.value,
        )
        val iconRight = canvas - gutter
        // Icons stop at the rail's left edge: no glyph sits on top of the line.
        assertEquals(iconRight, line.left, 0.001f)
        assertEquals(canvas - ChapterScrollbarEdgeInset.value, line.left + line.width, 0.001f)
        assertEquals(8f, line.width, 0.001f)

        val lane = ChapterScrollbarLaneWidth.value
        val visual = ChapterScrollbarVisualWidth.value
        val inset = ChapterScrollbarEdgeInset.value
        // The rail claims only the visible 8dp track; the icon strip inside
        // the lane is left to the row.
        assertEquals(
            ChapterScrollbarTouchZone.TRACK,
            chapterScrollbarTouchZone(canvas - inset - 1f, canvas, lane, visual, inset),
        )
        assertEquals(
            ChapterScrollbarTouchZone.LANE,
            chapterScrollbarTouchZone(iconRight - 1f, canvas, lane, visual, inset),
        )
        assertEquals(
            ChapterScrollbarTouchZone.LANE,
            chapterScrollbarTouchZone(canvas - lane + 1f, canvas, lane, visual, inset),
        )
        assertEquals(
            ChapterScrollbarTouchZone.NONE,
            chapterScrollbarTouchZone(canvas - lane - 1f, canvas, lane, visual, inset),
        )
    }

    @Test
    fun `line hugs the right edge with round caps and no top or bottom inset`() {
        val line = chapterScrollbarLine(
            canvasWidthPx = 480f,
            edgeInsetPx = 0f,
            visualWidthPx = 8f,
        )
        assertEquals(472f, line.left, 0.001f)
        assertEquals(8f, line.width, 0.001f)
        assertEquals(4f, line.radius, 0.001f)
        assertEquals(480f, line.left + line.width, 0.001f)

        // Rail and thumb cover the whole list height: the first index sits at
        // the top edge, the last index at the bottom edge.
        assertEquals(0f, chapterScrollbarThumbTop(track, thumb, 0, 100), 0.001f)
        assertEquals(
            track - thumb,
            chapterScrollbarThumbTop(track, thumb, 99, 100),
            0.001f,
        )
    }
}
