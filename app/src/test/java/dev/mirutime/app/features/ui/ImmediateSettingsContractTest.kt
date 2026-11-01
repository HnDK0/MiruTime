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
    fun `library filter has no Apply and commits each control`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")

        assertFalse(text.contains("onApply ="))
        assertFalse(text.contains("lib_apply"))
        assertTrue(text.contains("onSort ="))
        assertTrue(text.contains("onCardStyle ="))
    }

    @Test
    fun `catalog filter commits live and has no Apply`() {
        val text = source("src/main/java/dev/mirutime/app/features/finder/ui/CatalogFilterSheet.kt")
        val screen = source("src/main/java/dev/mirutime/app/features/finder/ui/SourceCatalogScreen.kt")

        assertFalse(text.contains("doneLabel"))
        assertFalse(text.contains("finder_apply"))
        // Filters still commit live, but the first composition and a schema
        // seed are skipped by the pure emission rule.
        assertTrue(text.contains("LaunchedEffect(filterKey, userEdited)"))
        assertTrue(text.contains("CatalogFilterEmission.shouldApply"))
        assertFalse(screen.contains("onApply = { draft ->\n                sheetOpen = false"))
    }

    @Test
    fun `extension and migration filters commit live`() {
        val extensions = source("src/main/java/dev/mirutime/app/features/finder/ui/ExtensionFilterSheet.kt")
        val migration = source("src/main/java/dev/mirutime/app/features/migration/ui/MigrationFilterSheet.kt")

        assertFalse(extensions.contains("doneLabel"))
        assertTrue(extensions.contains("LaunchedEffect(draft)"))
        assertFalse(migration.contains("doneLabel"))
        assertTrue(migration.contains("LaunchedEffect(draftSelected, draftOptions)"))
    }

    @Test
    fun `single-choice sheet stays open after selection`() {
        val text = source("ui-kit/src/main/java/dev/mirutime/uikit/SettingsRows.kt")
        val choice = text.substringAfter("fun <T> ChoiceSheet(")
            .substringBefore("}\n}")
        assertFalse(choice.contains("onClose()"))
    }
}
