package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A webtoon strip that could not stitch the next chapter must end with the
// explicit "Next chapter" card, never with a dead strip (regression guard).
class MangaStripEndCardTest {

    @Test
    fun `a stitched next chapter keeps the strip seamless`() {
        assertFalse(mangaStripNeedsEndCard(nextChapterStitched = true, canNextChapter = true))
    }

    @Test
    fun `a next chapter that failed to stitch still shows the card`() {
        assertTrue(mangaStripNeedsEndCard(nextChapterStitched = false, canNextChapter = true))
    }

    @Test
    fun `the last chapter always shows the card`() {
        assertTrue(mangaStripNeedsEndCard(nextChapterStitched = false, canNextChapter = false))
        // A stale stitched flag cannot hide the last-chapter label either.
        assertTrue(mangaStripNeedsEndCard(nextChapterStitched = true, canNextChapter = false))
    }
}
