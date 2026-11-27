package dev.mirutime.uikit

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTokensTest {
    @Test
    fun `card geometry is the approved compact set`() {
        assertEquals(8.dp, MiruRadius.Card)
        assertEquals(6.dp, MiruRadius.Control)
        assertEquals(6.dp, MiruRadius.Cover)
        assertEquals(8.dp, MiruRadius.Chip)
        assertEquals(8.dp, MiruRadius.Sheet)
        assertEquals(4.dp, MiruRadius.Split)
        assertEquals(3.dp, CoverInset)
        assertEquals(3.dp, MiruSpace.CardGap)
        // Resume cover is taller than the list row thumb, so the cover sets
        // the card height (76 * 1.45 ≈ 110dp).
        assertEquals(76.dp, MiruSize.ContinueCover)
        assertTrue(MiruSize.ContinueCover > 64.dp)
        assertTrue(MiruSize.ContinueCover < MiruSize.DetailCoverWidth)
    }

    @Test
    fun `card type is smaller than the body scale`() {
        assertEquals(13.5f, MiruCardType.Title.fontSize.value)
        assertEquals(18f, MiruCardType.Title.lineHeight.value)
        assertEquals(11f, MiruCardType.Meta.fontSize.value)
        assertEquals(11f, MiruCardType.Rating.fontSize.value)
        assertEquals(11f, MiruCardType.Num.fontSize.value)
    }
}
