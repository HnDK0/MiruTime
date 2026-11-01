package dev.mirutime.app.features.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSurfaceContractTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `library grid uses the approved gap`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")
        assertTrue(text.contains("MiruSpace.CardGap"))
    }

    @Test
    fun `old card paint and selection scrim are gone`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryCard.kt")
        assertFalse(text.contains("RoundedCornerShape(14.dp)"))
        assertFalse(text.contains("scrim.copy(alpha = 0.5f)"))
        assertFalse(text.contains("private fun VariantA("))
        assertFalse(text.contains("private fun VariantB("))
        assertFalse(text.contains("private fun VariantD("))
    }

    @Test
    fun `filter sheet keeps the stored columns at 200 percent`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")
        assertTrue(text.contains("columns = AppSettings.gridColumns.coerceIn(2, 6)"))
    }
}
