package dev.mirutime.app.features.settings.data

import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexRulesTransferTest {

    private fun rule(id: Long, pattern: String, replacement: String = "") = RegexRule(
        id = id,
        pattern = pattern,
        replacement = replacement,
        description = "",
        enabled = true,
        wholeWords = false,
        scope = RegexRuleScope.Both,
    )

    @Test
    fun `export then import keeps the rules and replaces ids`() {
        val text = exportRegexRules(listOf(rule(1, "foo", "bar"), rule(2, "baz")), RegexRuleScope.Both)
        var next = 100L

        val result = importRegexRules(text, emptySet()) { next++ }.getOrThrow()

        assertEquals(2, result.added.size)
        assertEquals(RegexRuleScope.Both, result.applyTo)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.invalid)
        assertEquals(listOf(100L, 101L), result.added.map { it.id })
        assertEquals(listOf("foo", "baz"), result.added.map { it.pattern })
    }

    @Test
    fun `existing and duplicate patterns are skipped`() {
        val text = exportRegexRules(listOf(rule(1, "foo"), rule(2, "foo"), rule(3, "bar")), RegexRuleScope.Both)

        val result = importRegexRules(text, setOf("bar")) { 1L }.getOrThrow()

        assertEquals(listOf("foo"), result.added.map { it.pattern })
        assertEquals(2, result.duplicates)
        assertEquals(0, result.invalid)
    }

    @Test
    fun `invalid patterns are skipped without failing the file`() {
        val text = exportRegexRules(listOf(rule(1, "("), rule(2, "ok")), RegexRuleScope.Both)

        val result = importRegexRules(text, emptySet()) { 1L }.getOrThrow()

        assertEquals(listOf("ok"), result.added.map { it.pattern })
        assertEquals(1, result.invalid)
    }

    @Test
    fun `apply-to round-trips and a legacy file defaults to both`() {
        val translated = exportRegexRules(listOf(rule(1, "foo")), RegexRuleScope.Translation)
        assertEquals(
            RegexRuleScope.Translation,
            importRegexRules(translated, emptySet()) { 1L }.getOrThrow().applyTo,
        )

        val legacy = """{"version":1,"rules":[]}"""
        assertEquals(
            RegexRuleScope.Both,
            importRegexRules(legacy, emptySet()) { 1L }.getOrThrow().applyTo,
        )
    }

    @Test
    fun `broken json is an error, not an empty import`() {
        assertTrue(importRegexRules("not json", emptySet()) { 1L }.isFailure)
    }

    @Test
    fun `rules beyond the cap are counted as invalid`() {
        val rules = List(MaxImportedRegexRules + 1) { index -> rule(index.toLong(), "r$index") }

        val result = importRegexRules(exportRegexRules(rules, RegexRuleScope.Both), emptySet()) { 1L }.getOrThrow()

        assertEquals(MaxImportedRegexRules, result.added.size)
        assertEquals(1, result.invalid)
    }
}
