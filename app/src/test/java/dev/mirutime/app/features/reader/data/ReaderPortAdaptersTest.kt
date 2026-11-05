package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPortAdaptersTest {
    private fun rule(scope: RegexRuleScope, pattern: String = "AD") = RegexRule(
        id = 1,
        pattern = pattern,
        replacement = "",
        description = "",
        enabled = true,
        wholeWords = false,
        scope = scope,
    )

    private fun withRules(vararg rules: RegexRule, block: () -> Unit) {
        val previousGlobal = AppSettings.globalRegexRules.toList()
        val previousNovel = AppSettings.novelRegexRules.toMap()
        try {
            AppSettings.replaceGlobalRules(rules.toList())
            AppSettings.novelRegexRules.clear()
            block()
        } finally {
            AppSettings.replaceGlobalRules(previousGlobal)
            AppSettings.novelRegexRules.clear()
            AppSettings.novelRegexRules.putAll(previousNovel)
        }
    }

    @Test
    fun `translation rules clean only translated text`() = withRules(rule(RegexRuleScope.Translation)) {
        val cleaned = cleanTranslatedBlocks("book", mapOf(0 to "AD text"))

        assertEquals(" text", cleaned[0])
    }

    @Test
    fun `original rules do not touch translated text`() = withRules(rule(RegexRuleScope.Original)) {
        val cleaned = cleanTranslatedBlocks("book", mapOf(0 to "AD text"))

        assertEquals("AD text", cleaned[0])
    }

    @Test
    fun `both rules clean translated text`() = withRules(rule(RegexRuleScope.Both)) {
        val cleaned = cleanTranslatedBlocks("book", mapOf(0 to "AD text"))

        assertEquals(" text", cleaned[0])
    }

    @Test
    fun `per-novel rules win for their book`() {
        withRules(rule(RegexRuleScope.Translation, pattern = "GLOBAL")) {
            AppSettings.replaceNovelRules(
                "my-book",
                listOf(rule(RegexRuleScope.Translation, pattern = "NOVEL")),
            )
            val cleaned = cleanTranslatedBlocks(
                "my-book",
                mapOf(0 to "GLOBAL NOVEL text", 1 to "GLOBAL only"),
            )

            assertEquals("  text", cleaned[0])
            assertEquals(" only", cleaned[1])
        }
    }

    @Test
    fun `unknown book falls back to global rules`() = withRules(
        rule(RegexRuleScope.Translation),
    ) {
        val cleaned = cleanTranslatedBlocks("", mapOf(0 to "AD text"))

        assertEquals(" text", cleaned[0])
    }

    @Test
    fun `empty translations stay empty`() = withRules(rule(RegexRuleScope.Translation)) {
        assertEquals(emptyMap<Int, String>(), cleanTranslatedBlocks("book", emptyMap()))
    }
}
