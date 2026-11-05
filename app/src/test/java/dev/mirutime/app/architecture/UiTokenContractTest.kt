package dev.mirutime.app.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards the UI token scale: feature code must not hardcode numeric corner
 * radii. Shape literals are only allowed through `MiruRadius.*` (or a variable
 * derived from it). The baseline is empty: any numeric radius in scope is a
 * violation.
 *
 * Deliberately not enforced here:
 * - `CircleShape` / `RoundedCornerShape(50)`: dots, counters, nav plates.
 * - raw `dp`/`sp`: reader typography is user-configurable (guide 2.1).
 * - `ui-kit`: that module owns the tokens and their literals.
 */
class UiTokenContractTest {

    private val startDir = System.getProperty("user.dir") ?: "."
    private val root: File = generateSequence(File(startDir)) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Cannot find repository root from ${System.getProperty("user.dir")}")

    private val scopes = listOf(
        "app/src/main/java/dev/mirutime/app/features",
        "app/src/main/java/dev/mirutime/app/design",
        "feature-reader/src/main/java/dev/mirutime/feature/reader/ui",
        "feature-tts/src/main/java/dev/mirutime/feature/tts/ui",
    )

    // Any `RoundedCornerShape(...)` call, then look for a numeric argument
    // (positional or named) with a `dp`/`f` suffix. `MiruRadius.*`, variables
    // and bare `RoundedCornerShape(50)` are not numeric dp/f arguments.
    private val shapeCall = Regex("""RoundedCornerShape\(([^)]*)\)""")
    private val numericArg = Regex("""(?:^|,)\s*(?:\w+\s*=\s*)?\d+(?:\.\d+)?\s*(?:\.\s*dp|f)\b""")

    private fun blankOut(match: MatchResult): String =
        match.value.replace(Regex("[^\\n]"), " ")

    // Blank out (don't delete) comments and strings so reported lines and
    // columns stay aligned. Strings go first: a `"/*"` literal must not be
    // mistaken for the start of a block comment.
    private fun codeOnly(source: String): String = source
        .replace(Regex("\"\"\"[\\s\\S]*?\"\"\"")) { blankOut(it) }
        .replace(Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"")) { blankOut(it) }
        .replace(Regex("/\\*[\\s\\S]*?\\*/")) { blankOut(it) }
        .replace(Regex("//[^\\n]*")) { blankOut(it) }

    // path relative to repo root -> number of numeric RoundedCornerShape literals.
    private val baseline = emptyMap<String, Int>()

    private fun scan(): Map<String, List<Int>> {
        val found = linkedMapOf<String, MutableList<Int>>()
        scopes.forEach { scope ->
            val dir = File(root, scope)
            if (!dir.isDirectory) fail("Missing UI scope: $scope")
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val code = codeOnly(file.readText())
                    shapeCall.findAll(code).forEach { call ->
                        val hits = numericArg.findAll(call.groupValues[1]).count()
                        if (hits > 0) {
                            val line = code.take(call.range.first).count { it == '\n' } + 1
                            val lines = found.getOrPut(file.relativeTo(root).invariantSeparatorsPath) {
                                mutableListOf()
                            }
                            repeat(hits) { lines += line }
                        }
                    }
                }
        }
        return found
    }

    @Test
    fun `feature ui does not add numeric corner radii`() {
        val found = scan()
        val actual = found.mapValues { it.value.size }
        val paths = (actual.keys + baseline.keys).distinct()
        val increased = paths.filter { (actual[it] ?: 0) > (baseline[it] ?: 0) }
        val decreased = paths.filter { (actual[it] ?: 0) < (baseline[it] ?: 0) }

        assertTrue(
            buildString {
                appendLine("Numeric RoundedCornerShape literals changed.")
                appendLine("Use MiruRadius.* instead of RoundedCornerShape(<n>.dp).")
                if (increased.isNotEmpty()) {
                    appendLine("New/increased violations:")
                    increased.forEach { path ->
                        appendLine(
                            "  $path: ${actual[path] ?: 0} (baseline ${baseline[path] ?: 0}) lines ${found[path]}",
                        )
                    }
                }
                if (decreased.isNotEmpty()) {
                    appendLine("Baseline must shrink with the fix:")
                    decreased.forEach { path ->
                        appendLine("  $path: ${actual[path] ?: 0} (baseline ${baseline[path] ?: 0})")
                    }
                }
            },
            increased.isEmpty() && decreased.isEmpty(),
        )
    }
}
