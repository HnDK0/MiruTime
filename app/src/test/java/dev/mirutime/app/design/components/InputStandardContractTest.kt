package dev.mirutime.app.design.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStandardContractTest {
    private val roots: List<File> = listOf(
        "src/main/java",
        "app/src/main/java",
        "../feature-tts/src/main/java",
        "feature-tts/src/main/java",
        "../feature-reader/src/main/java",
        "feature-reader/src/main/java",
    ).map(::File).filter { it.isDirectory }

    private fun source(relative: String): String =
        roots.map { File(it, relative) }.first { it.isFile }.readText()

    @Test
    fun `modules never build a raw text field`() {
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val text = file.readText()
                    text.contains("OutlinedTextField(") || text.contains("BasicTextField(")
                }
                .map { "${root.path}/${it.relativeTo(root).path}" }
                .toList()
        }
        assertTrue("Raw fields: $offenders", offenders.isEmpty())
    }

    @Test
    fun `local searches share the one debounce window`() {
        assertTrue(
            source("dev/mirutime/app/features/library/ui/LibraryScreen.kt")
                .contains("MiruSearchDebounceMillis"),
        )
        assertTrue(
            source("dev/mirutime/app/features/finder/ui/FilterChipGroup.kt")
                .contains("MiruSearchDebounceMillis"),
        )
        assertFalse(
            source("dev/mirutime/app/features/library/ui/LibraryScreen.kt")
                .contains("SearchDebounceMs = 300"),
        )
    }
}
