package dev.mirutime.app.features.library.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScrollTopTest {

    @Test
    fun `hidden while the first viewport is still on screen`() {
        assertFalse(shouldShowScrollToTop(firstVisibleItemIndex = 0, visibleItemCount = 5))
        assertFalse(shouldShowScrollToTop(firstVisibleItemIndex = 4, visibleItemCount = 5))
    }

    @Test
    fun `visible after more than one viewport of items`() {
        assertTrue(shouldShowScrollToTop(firstVisibleItemIndex = 5, visibleItemCount = 5))
        assertTrue(shouldShowScrollToTop(firstVisibleItemIndex = 20, visibleItemCount = 5))
    }

    @Test
    fun `empty list never shows the control`() {
        assertFalse(shouldShowScrollToTop(firstVisibleItemIndex = 0, visibleItemCount = 0))
    }

    @Test
    fun `pinned sticky header is not counted as a scrolling row`() {
        // Compose prepends the pinned header (index 0) to visibleItemsInfo;
        // only the rows from the first visible item on belong to the viewport.
        assertEquals(
            3,
            scrollingItemCount(
                visibleIndices = listOf(0, 5, 6, 7),
                firstVisibleItemIndex = 5,
            ),
        )
    }

    @Test
    fun `a pinned header does not freeze the threshold`() {
        val first = 5
        val count = scrollingItemCount(
            visibleIndices = listOf(0, first, first + 1, first + 2),
            firstVisibleItemIndex = first,
        )
        // 5 >= 3: the button now shows; scanning visibleItemsInfo.first()
        // would have passed 0 and hidden it forever.
        assertTrue(shouldShowScrollToTop(firstVisibleItemIndex = first, visibleItemCount = count))
    }
}
