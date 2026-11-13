package dev.mirutime.app.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every UI locale must carry the same resource keys as the default `values/`
 * folder. `MissingTranslation` is only a lint warning, so without this test a
 * new key silently ships as English to the nine translations.
 */
class StringResourceParityTest {

    private val root: File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Cannot find repository root from ${System.getProperty("user.dir")}")

    private val modules = mapOf(
        "app" to listOf(
            "strings.xml",
            "strings_book_ux.xml",
            "strings_loading_a.xml",
            "strings_loading_b.xml",
        ),
        "feature-reader" to listOf("strings.xml"),
        "feature-tts" to listOf("strings.xml"),
        "ui-kit" to listOf("strings.xml"),
    )

    private val locales = listOf("ru", "es", "pt", "in", "vi", "fr", "de", "it", "tr", "ar")

    // CLDR plural categories per locale. A missing one silently falls back to
    // another form at runtime, and lint only warns about it.
    private val pluralQuantities = mapOf(
        "ar" to setOf("zero", "one", "two", "few", "many", "other"),
        "ru" to setOf("one", "few", "many", "other"),
        "es" to setOf("one", "many", "other"),
        "pt" to setOf("one", "many", "other"),
        "it" to setOf("one", "many", "other"),
        "fr" to setOf("one", "many", "other"),
        "de" to setOf("one", "other"),
        "tr" to setOf("one", "other"),
        "in" to setOf("other"),
        "vi" to setOf("other"),
    )

    @Test
    fun `every locale has the same resource keys as the default`() {
        val problems = mutableListOf<String>()
        for ((module, files) in modules) {
            for (file in files) {
                val base = File(root, "$module/src/main/res/values/$file")
                assertTrue("Missing default resource file: $base", base.isFile)
                val expected = keys(base)
                for (locale in locales) {
                    val translated = File(root, "$module/src/main/res/values-$locale/$file")
                    if (!translated.isFile) {
                        problems += "$locale: missing $module/$file"
                        continue
                    }
                    val actual = keys(translated)
                    (expected - actual).forEach { problems += "$locale: $module/$file missing \"$it\"" }
                    (actual - expected).forEach { problems += "$locale: $module/$file extra \"$it\"" }
                    val required = pluralQuantities.getValue(locale)
                    for ((plural, have) in quantities(translated)) {
                        val missing = required - have
                        if (missing.isNotEmpty()) {
                            problems += "$locale: $module/$file $plural missing ${missing.sorted()}"
                        }
                    }
                }
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems.toList())
    }

    /** `<plurals name=…>` → the `<item quantity=…>` it declares. */
    private fun quantities(file: File): Map<String, Set<String>> =
        Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { block ->
                block.groupValues[1] to
                    Regex("""<item\s+quantity="([^"]+)"""")
                        .findAll(block.groupValues[2])
                        .map { it.groupValues[1] }
                        .toSet()
            }

    /** `<string name=…>` and `<plurals name=…>`, skipping translatable="false". */
    private fun keys(file: File): Set<String> =
        Regex("""<(string|plurals)\s+name="([^"]+)"([^>]*)>""")
            .findAll(file.readText())
            .filterNot { it.groupValues[3].contains("translatable=\"false\"") }
            .map { it.groupValues[2] }
            .toSet()
}
