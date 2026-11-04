package dev.mirutime.app.features.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateSettingsContractTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `library filters draft until Apply while appearance commits live`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")

        assertTrue(text.contains("doneLabel"))
        assertTrue(text.contains("finder_apply"))
        // Appearance settings still commit live.
        assertTrue(text.contains("onCardStyle ="))
        assertTrue(text.contains("onColumns ="))
    }

    @Test
    fun `catalog filter applies only on Apply`() {
        val text = source("src/main/java/dev/mirutime/app/features/finder/ui/CatalogFilterSheet.kt")

        // Draft + Apply: no live emission, an explicit Apply button.
        assertTrue(text.contains("doneLabel"))
        assertTrue(text.contains("finder_apply"))
        assertFalse(text.contains("LaunchedEffect(filterKey, userEdited)"))
        assertFalse(text.contains("CatalogFilterEmission.shouldApply"))
    }

    @Test
    fun `extension and migration filters apply only on Apply`() {
        val extensions = source("src/main/java/dev/mirutime/app/features/finder/ui/ExtensionFilterSheet.kt")
        val migration = source("src/main/java/dev/mirutime/app/features/migration/ui/MigrationFilterSheet.kt")

        assertTrue(extensions.contains("doneLabel"))
        assertFalse(extensions.contains("LaunchedEffect(draft)"))
        assertTrue(migration.contains("doneLabel"))
        assertFalse(migration.contains("LaunchedEffect(draftSelected, draftOptions)"))
    }

    @Test
    fun `filter sheets put Reset in the header action`() {
        val sheets = listOf(
            "src/main/java/dev/mirutime/app/features/finder/ui/CatalogFilterSheet.kt",
            "src/main/java/dev/mirutime/app/features/finder/ui/ExtensionFilterSheet.kt",
            "src/main/java/dev/mirutime/app/features/finder/ui/FinderFilterSheets.kt",
        ).map(::source)

        sheets.forEach { text ->
            assertTrue(text.contains("headerAction"))
            assertTrue(text.contains("finder_reset"))
        }
    }

    @Test
    fun `single-choice sheet stays open after selection`() {
        val text = source("ui-kit/src/main/java/dev/mirutime/uikit/SettingsRows.kt")
        val choice = text.substringAfter("fun <T> ChoiceSheet(")
            .substringBefore("}\n}")
        assertFalse(choice.contains("onClose()"))
    }
}
