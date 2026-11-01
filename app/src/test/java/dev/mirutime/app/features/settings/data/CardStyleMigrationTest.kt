package dev.mirutime.app.features.settings.data

import dev.mirutime.app.features.library.domain.CardStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class CardStyleMigrationTest {
    @Test
    fun `old persisted names migrate to the approved presets`() {
        assertEquals(CardStyle.OnCover, migrateCardStyle("CoverTitle"))
        assertEquals(CardStyle.Mat, migrateCardStyle("BelowCover"))
        assertEquals(CardStyle.ProgressAbove, migrateCardStyle("Stats"))
        assertEquals(CardStyle.Horizontal, migrateCardStyle("Horizontal"))
    }

    @Test
    fun `new names and unknown values are safe`() {
        assertEquals(CardStyle.Above, migrateCardStyle("Above"))
        assertEquals(CardStyle.ProgressAbove, migrateCardStyle("ProgressAbove"))
        assertEquals(CardStyle.Mat, migrateCardStyle("???"))
        assertEquals(CardStyle.Mat, migrateCardStyle(null))
    }
}
