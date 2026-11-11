package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun withRules(
        vararg rules: RegexRule,
        applyTo: RegexRuleScope = RegexRuleScope.Both,
        block: () -> Unit,
    ) {
        val previousGlobal = AppSettings.globalRegexRules.toList()
        val previousNovel = AppSettings.novelRegexRules.toMap()
        val previousApplyTo = AppSettings.globalRegexApplyTo
        try {
            AppSettings.setGlobalRegexApplyTo(applyTo)
            AppSettings.replaceGlobalRules(rules.toList())
            AppSettings.novelRegexRules.clear()
            block()
        } finally {
            AppSettings.replaceGlobalRules(previousGlobal)
            AppSettings.setGlobalRegexApplyTo(previousApplyTo)
            AppSettings.novelRegexRules.clear()
            AppSettings.novelRegexRules.putAll(previousNovel)
        }
    }

    @Test
    fun `translation rules clean only translated text`() = withRules(rule(RegexRuleScope.Translation), applyTo = RegexRuleScope.Translation) {
        val cleaned = cleanTranslatedBlocks("book", mapOf(0 to "AD text"))

        assertEquals(" text", cleaned[0])
    }

    @Test
    fun `original rules do not touch translated text`() = withRules(rule(RegexRuleScope.Original), applyTo = RegexRuleScope.Original) {
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
        withRules(
            rule(RegexRuleScope.Translation, pattern = "GLOBAL"),
            applyTo = RegexRuleScope.Translation,
        ) {
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
        applyTo = RegexRuleScope.Translation,
    ) {
        val cleaned = cleanTranslatedBlocks("", mapOf(0 to "AD text"))

        assertEquals(" text", cleaned[0])
    }

    @Test
    fun `translation-only documents are never translated`() {
        val synthetic = ReaderDocument(
            bookUrl = "book",
            chapterUrl = "book#chapter=1",
            chapterIndex = 1,
            title = "One",
            blocks = emptyList(),
            translationOnly = true,
        )
        val original = synthetic.copy(translationOnly = false)

        assertFalse(isTranslatable(synthetic))
        assertTrue(isTranslatable(original))
    }

    @Test
    fun `empty translations stay empty`() = withRules(rule(RegexRuleScope.Translation), applyTo = RegexRuleScope.Translation) {
        assertEquals(emptyMap<Int, String>(), cleanTranslatedBlocks("book", emptyMap()))
    }
}
