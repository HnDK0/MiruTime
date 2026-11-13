package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaPagerDirectionTest {

    /**
     * The pager already mirrors page placement in RTL, while the manga reading
     * direction is physical, so `reverseLayout` is the XOR. Getting this wrong
     * in either direction means every page lands in the wrong order.
     */
    @Test
    fun `page order is physical in both interface directions`() {
        // LTR interface: the manga direction is the pager direction.
        assertFalse(mangaReverseLayout(mangaRtl = false, uiRtl = false))
        assertTrue(mangaReverseLayout(mangaRtl = true, uiRtl = false))

        // RTL interface: the pager is already reversed, so the flag flips.
        assertTrue(mangaReverseLayout(mangaRtl = false, uiRtl = true))
        assertFalse(mangaReverseLayout(mangaRtl = true, uiRtl = true))
    }
}
