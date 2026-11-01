package dev.mirutime.feature.reader.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaPreloadTest {
    @Test
    fun `paged window keeps four to six pages around the current one`() {
        val middle = mangaPagedPreloadIndices(currentPage = 5, pageCount = 20)
        assertEquals(listOf(3, 4, 6, 7, 8), middle)

        val first = mangaPagedPreloadIndices(currentPage = 0, pageCount = 20)
        assertEquals(listOf(1, 2, 3, 4, 5), first)

        val last = mangaPagedPreloadIndices(currentPage = 19, pageCount = 20)
        assertEquals(listOf(14, 15, 16, 17, 18), last)
        assertTrue(last.size in 4..6)
    }

    @Test
    fun `paged window never returns the current page or out of range pages`() {
        val short = mangaPagedPreloadIndices(currentPage = 1, pageCount = 3)
        assertEquals(listOf(0, 2), short)
        assertTrue(short.none { it == 1 || it !in 0..2 })
        assertTrue(mangaPagedPreloadIndices(currentPage = 0, pageCount = 0).isEmpty())
    }

    @Test
    fun `webtoon window spends a screen-height budget on real page ratios`() {
        val shortPages = mangaWebtoonPreloadIndices(
            currentPage = 3,
            pageCount = 10,
            pageRatios = List(10) { 2f },
            viewportHeightPx = 2000f,
            pageWidthPx = 500f,
        )
        assertEquals(listOf(4, 5, 6, 7, 2, 1), shortPages)

        val tallPages = mangaWebtoonPreloadIndices(
            currentPage = 3,
            pageCount = 10,
            pageRatios = List(10) { 8f },
            viewportHeightPx = 2000f,
            pageWidthPx = 500f,
        )
        // One 4000px page covers the 2-screen look-ahead; the 3-page minimum
        // still keeps the window useful without decoding six huge strips.
        assertEquals(listOf(4, 5, 6, 2, 1), tallPages)
    }

    @Test
    fun `webtoon window clamps at the chapter edges and unknown ratios`() {
        val atStart = mangaWebtoonPreloadIndices(
            currentPage = 0,
            pageCount = 4,
            pageRatios = listOf(null, null, null, null),
            viewportHeightPx = 2000f,
            pageWidthPx = 500f,
        )
        assertEquals(listOf(1, 2, 3), atStart)

        val atEnd = mangaWebtoonPreloadIndices(
            currentPage = 3,
            pageCount = 4,
            pageRatios = listOf(null, null, null, null),
            viewportHeightPx = 2000f,
            pageWidthPx = 500f,
        )
        assertEquals(listOf(2, 1), atEnd)

        assertTrue(
            mangaWebtoonPreloadIndices(0, 4, emptyList(), 0f, 500f).isEmpty(),
        )
        assertTrue(
            mangaWebtoonPreloadIndices(0, 4, emptyList(), 2000f, 0f).isEmpty(),
        )
    }

    @Test
    fun `paged request size is shared by the visible page and its prefetch`() {
        assertEquals(IntSize(1080, 1920), mangaPagedRequestSize(1080, 1920))
        assertNull(mangaPagedRequestSize(0, 1920))
        assertNull(mangaPagedRequestSize(1080, 0))
    }

    @Test
    fun `webtoon request size stays width-based so a measured ratio cannot change the key`() {
        val firstDecode = mangaWebtoonRequestSize(1080)
        val afterSuccess = mangaWebtoonRequestSize(1080)
        assertEquals(firstDecode, afterSuccess)
        assertEquals(IntSize(1080, 3240), firstDecode)
        // The reserved height may use the measured ratio; the decode size keeps
        // the shared fallback, so prefetch and visible page share one key.
        assertEquals(IntSize(1080, 3240), mangaWebtoonRequestSize(1080))
        assertNull(mangaWebtoonRequestSize(0))
    }

    @Test
    fun `paged request size is the viewport for every page, not a later measurement`() {
        val viewport = IntSize(1080, 1920)
        assertEquals(viewport, mangaPagedRequestSize(viewport.width, viewport.height))
        assertEquals(viewport, mangaPagedRequestSize(viewport.width, viewport.height))
    }

    @Test
    fun `scrubber values map to zero-based pages inside the range`() {
        assertEquals(0, mangaScrubberPage(0f, pageCount = 10))
        assertEquals(1, mangaScrubberPage(1.4f, pageCount = 10))
        assertEquals(9, mangaScrubberPage(99f, pageCount = 10))
        assertEquals(0, mangaScrubberPage(5f, pageCount = 0))
    }

    @Test
    fun `local page files never get remote headers`() {
        assertTrue(mangaImageNeedsHeaders("https://cdn.example/page.jpg"))
        assertTrue(mangaImageNeedsHeaders("http://cdn.example/page.jpg"))
        assertEquals(false, mangaImageNeedsHeaders("file:///data/page.jpg"))
        assertEquals(false, mangaImageNeedsHeaders("content://media/page.jpg"))
    }
}
