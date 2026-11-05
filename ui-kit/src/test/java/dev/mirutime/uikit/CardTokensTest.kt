package dev.mirutime.uikit

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CardTokensTest {
    @Test
    fun `card geometry is the approved compact set`() {
        assertEquals(12.dp, MiruRadius.Card)
        assertEquals(6.dp, MiruRadius.Cover)
        assertEquals(8.dp, MiruRadius.Chip)
        assertEquals(4.dp, MiruRadius.Split)
        assertEquals(4.dp, CoverInset)
        assertEquals(4.dp, MiruSpace.CardGap)
    }

    @Test
    fun `card type is smaller than the body scale`() {
        assertEquals(12.5f, MiruCardType.Title.fontSize.value)
        assertEquals(16f, MiruCardType.Title.lineHeight.value)
        assertEquals(10f, MiruCardType.Meta.fontSize.value)
        assertEquals(10.5f, MiruCardType.Rating.fontSize.value)
        assertEquals(10f, MiruCardType.Num.fontSize.value)
    }
}
