package dev.mirutime.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards the module graph from accidental cross-feature imports. The app shell
 * and legacy core-reader-model package are intentionally allowed where noted.
 */
class ArchitectureBoundaryTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Cannot find repository root from ${System.getProperty("user.dir")}")

    private val legacyCoreReader = "dev.mirutime.app.features.reader.document."

    @Test
    fun `feature modules do not import app feature internals`() {
        assertNoImports(
            module = "feature-reader",
            forbidden = listOf("dev.mirutime.app."),
            allowedPrefixes = listOf(legacyCoreReader),
        )
        assertNoImports(
            module = "feature-tts",
            forbidden = listOf("dev.mirutime.app.", "dev.mirutime.feature.reader."),
            allowedPrefixes = listOf(legacyCoreReader),
        )
    }

    @Test
    fun `shared modules stay below features`() {
        listOf("ui-kit", "core-log", "image-loader").forEach { module ->
            assertNoImports(
                module = module,
                forbidden = listOf("dev.mirutime.app.", "dev.mirutime.feature."),
            )
        }
        listOf("mirutime-data", "translator", "mirutime-core").forEach { module ->
            assertNoImports(
                module = module,
                forbidden = listOf("dev.mirutime.app.", "dev.mirutime.feature."),
            )
        }
    }

    @Test
    fun `core reader model stays JVM only`() {
        assertNoImports(
            module = "core-reader-model",
            forbidden = listOf("android.", "androidx."),
        )
    }

    @Test
    fun `library feature does not import book feature`() {
        assertNoImportsIn(
            source = File(root, "app/src/main/java/dev/mirutime/app/features/library"),
            label = "features/library",
            forbidden = listOf("dev.mirutime.app.features.book."),
        )
    }

    @Test
    fun `filter and migration surfaces never stack bottom sheets`() {
        val pluginFilters = File(
            root,
            "app/src/main/java/dev/mirutime/app/features/finder/ui/PluginFilterSection.kt",
        ).readText()
        assertFalse(
            "PluginFilterSection must expand inline, not open a nested ChoiceSheet",
            "ChoiceSheet(" in pluginFilters,
        )

        val bookMigration = File(
            root,
            "app/src/main/java/dev/mirutime/app/features/migration/ui/MigrationBookSheet.kt",
        ).readText()
        assertEquals(
            "MigrationBookSheet must render options in its single AppBottomSheet",
            1,
            Regex("AppBottomSheet\\(").findAll(bookMigration).count(),
        )
    }

    private fun assertNoImports(
        module: String,
        forbidden: List<String>,
        allowedPrefixes: List<String> = emptyList(),
    ) {
        val source = File(root, "$module/src")
        if (!source.isDirectory) fail("Missing module source: ${source.path}")
        assertNoImportsIn(source, module, forbidden, allowedPrefixes)
    }

    private fun assertNoImportsIn(
        source: File,
        label: String,
        forbidden: List<String>,
        allowedPrefixes: List<String> = emptyList(),
    ) {
        if (!source.isDirectory) fail("Missing source: ${source.path}")
        val violations = source.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val value = line.trim()
                    if (!value.startsWith("import ")) return@mapIndexedNotNull null
                    val imported = value.removePrefix("import ").substringBefore(" as ").trim()
                    if (allowedPrefixes.any { imported.startsWith(it) }) return@mapIndexedNotNull null
                    if (forbidden.any { imported.startsWith(it) }) {
                        "${file.relativeTo(root)}:${index + 1}: $imported"
                    } else {
                        null
                    }
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            fail("$label imports forbidden modules:\n${violations.joinToString("\n")}")
        }
    }
}
