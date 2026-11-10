package dev.mirutime.uikit

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactTokensTest {

    @Test
    fun `compact scale is smaller than the comfortable ladder`() {
        assertEquals(32.dp, MiruSize.RowCompact)
        assertEquals(28.dp, MiruSize.ControlCompact)
        assertEquals(32.dp, MiruSize.ControlCompactLarge)
        assertEquals(24.dp, MiruSize.ChipCompact)

        assertTrue(MiruSize.RowCompact < MiruSize.Row)
        assertTrue(MiruSize.RowCompact < MiruSize.RowDense)
        assertTrue(MiruSize.ControlCompact < MiruSize.Control)
        assertTrue(MiruSize.ControlCompactLarge < MiruSize.Button)
    }

    @Test
    fun `compact touch target adds eight dp with floor and cap`() {
        assertEquals(32.dp, compactTouchTarget(0.dp))
        assertEquals(32.dp, compactTouchTarget(20.dp))
        assertEquals(32.dp, compactTouchTarget(24.dp))
        assertEquals(36.dp, compactTouchTarget(28.dp))
        assertEquals(40.dp, compactTouchTarget(32.dp))
        assertEquals(48.dp, compactTouchTarget(40.dp))
        assertEquals(48.dp, compactTouchTarget(44.dp))
        assertEquals(48.dp, compactTouchTarget(48.dp))
        assertEquals(56.dp, compactTouchTarget(56.dp))
    }

    @Test
    fun `touch target is monotonic and keeps large surfaces intact`() {
        var previous = compactTouchTarget(0.dp)
        for (size in 0..80) {
            val current = compactTouchTarget(size.dp)
            assertTrue(
                "touch target must not shrink as the surface grows",
                current >= previous,
            )
            previous = current
        }
        listOf(48, 56, 80).forEach { size ->
            assertEquals(size.dp, compactTouchTarget(size.dp))
        }
    }
}
