package dev.mirutime.app.features.library.ui

import dev.mirutime.app.design.components.toPreset
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.uikit.MiruCardPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class CardStyleMappingTest {
    @Test
    fun `old styles map to the approved presets`() {
        assertEquals(MiruCardPreset.OnCover, CardStyle.CoverTitle.toPreset())
        assertEquals(MiruCardPreset.Mat, CardStyle.BelowCover.toPreset())
        assertEquals(MiruCardPreset.ProgressAbove, CardStyle.Stats.toPreset())
        assertEquals(MiruCardPreset.List, CardStyle.Horizontal.toPreset())
    }

    @Test
    fun `new styles map to themselves`() {
        assertEquals(MiruCardPreset.Mat, CardStyle.Mat.toPreset())
        assertEquals(MiruCardPreset.Above, CardStyle.Above.toPreset())
        assertEquals(MiruCardPreset.Mat, CardStyle.Share.toPreset())
        assertEquals(MiruCardPreset.OnCover, CardStyle.OnCover.toPreset())
        assertEquals(MiruCardPreset.OnCoverAbove, CardStyle.OnCoverAbove.toPreset())
        assertEquals(MiruCardPreset.ProgressAbove, CardStyle.ProgressAbove.toPreset())
    }
}
