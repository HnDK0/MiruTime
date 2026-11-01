package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCardPresetTest {
    @Test
    fun `six grid presets plus list`() {
        assertEquals(
            listOf("Mat", "Above", "Share", "OnCover", "OnCoverAbove", "ProgressAbove", "List"),
            MiruCardPreset.entries.map { it.name },
        )
    }

    @Test
    fun `layout spec matches the approved placements`() {
        assertTrue(layoutSpec(MiruCardPreset.OnCover).titleOnCover)
        assertTrue(layoutSpec(MiruCardPreset.ProgressAbove).progressFullWidth)
        assertTrue(layoutSpec(MiruCardPreset.Above).statusAbove)
        assertTrue(layoutSpec(MiruCardPreset.Mat).surface)
        assertTrue(layoutSpec(MiruCardPreset.Above).surface)
        assertTrue(layoutSpec(MiruCardPreset.OnCover).surface)
        assertTrue(layoutSpec(MiruCardPreset.OnCoverAbove).surface)
        assertTrue(layoutSpec(MiruCardPreset.ProgressAbove).surface)
        assertFalse(layoutSpec(MiruCardPreset.Share).surface)
        assertFalse(layoutSpec(MiruCardPreset.Mat).titleOnCover)
    }
}
