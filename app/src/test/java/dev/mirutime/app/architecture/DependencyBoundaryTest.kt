package dev.mirutime.app.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Keeps the dependency set deliberate: Paging 3, DataStore and DI frameworks
 * are not used. The project follows existing patterns instead.
 */
class DependencyBoundaryTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("Cannot find repository root from ${System.getProperty("user.dir")}")

    private val catalog = File(root, "gradle/libs.versions.toml")

    private val forbidden = listOf(
        "androidx.paging",
        "androidx.datastore",
        "dagger",
        "hilt",
        "koin",
    )

    @Test
    fun `forbidden dependencies stay out of the build`() {
        assertTrue("Missing dependency catalog: $catalog", catalog.isFile)

        val buildFiles = root.walkTopDown()
            .filter { file ->
                file.isFile && (file.name.endsWith(".gradle.kts") || file.name.endsWith(".gradle"))
            }
            .filterNot { file ->
                file.invariantSeparatorsPath.contains("/build/") ||
                    file.invariantSeparatorsPath.contains("/.gradle/")
            }
            .toList()

        assertTrue("No Gradle build files found under $root", buildFiles.isNotEmpty())

        val violations = (buildFiles + catalog).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").substringBefore("#")
                val lower = code.lowercase()
                val hit = forbidden.firstOrNull { lower.contains(it) } ?: return@mapIndexedNotNull null
                "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: $hit -> ${line.trim()}"
            }
        }

        if (violations.isNotEmpty()) {
            fail("Forbidden dependencies found:\n${violations.joinToString("\n")}")
        }
    }
}
