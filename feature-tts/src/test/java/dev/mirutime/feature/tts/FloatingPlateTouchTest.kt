package dev.mirutime.feature.tts

import androidx.compose.ui.unit.dp
import dev.mirutime.feature.tts.ui.FloatingCompactTouch
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.compactTouchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating plate used to give every control a full 48dp slot, which doubled
 * the panel height for nothing. AGENTS: the hit box is the surface plus 8dp,
 * floored at 32 and capped at 48.
 */
class FloatingPlateTouchTest {
    @Test
    fun `plate controls use the compact hit box, not the 48dp floor`() {
        assertEquals(32.dp, FloatingCompactTouch)
        assertEquals(compactTouchTarget(MiruSize.Chip), FloatingCompactTouch)
        assertTrue(
            "a 48dp slot made the panel twice as tall as the 24dp face needs",
            FloatingCompactTouch < MiruSize.Touch,
        )
    }

    @Test
    fun `the actions row fits one line at the minimum panel width`() {
        // Six fixed slots (stop, start here, focus, show text, tune, settings).
        // The transport row is not modelled here: its progress bar is weight(1f)
        // and absorbs the remainder, so it has no fixed slot count.
        val controls = 6
        val gaps = 5
        val used = FloatingCompactTouch * controls + 4.dp * gaps
        val inner = TtsFloatingMinWidth.dp - 16.dp
        assertTrue(
            "six actions must stay on one line at $TtsFloatingMinWidth dp " +
                "(needs $used, has $inner), or the panel grows by a whole row",
            used < inner,
        )
    }
}
