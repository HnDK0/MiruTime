package dev.mirutime.app.features.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CardGapContractTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `book grids use the one 4dp card gap`() {
        listOf(
            "src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt",
            "src/main/java/dev/mirutime/app/features/finder/ui/SourceCatalogScreen.kt",
            "src/main/java/dev/mirutime/app/features/finder/ui/GlobalSearchScreen.kt",
            "src/main/java/dev/mirutime/app/features/migration/ui/MigrationComponents.kt",
        ).forEach { path ->
            assertTrue(path, source(path).contains("MiruSpace.CardGap"))
        }
    }
}
