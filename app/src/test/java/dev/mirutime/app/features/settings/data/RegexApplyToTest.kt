package dev.mirutime.app.features.settings.data

import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexApplyToTest {
    private val novelUrl = "fake://lib/one"

    @After
    fun cleanup() {
        AppSettings.replaceGlobalRules(emptyList())
        AppSettings.replaceNovelRules(novelUrl, null)
        AppSettings.setGlobalRegexApplyTo(RegexRuleScope.Both)
        AppSettings.novelRegexApplyTo.clear()
    }

    private fun rule(id: Long, scope: RegexRuleScope = RegexRuleScope.Both) = RegexRule(
        id = id,
        pattern = "p$id",
        replacement = "",
        description = "",
        enabled = true,
        wholeWords = false,
        scope = scope,
    )

    @Test
    fun `list-level apply-to overrides the stored rule scope`() {
        AppSettings.replaceGlobalRules(listOf(rule(1, RegexRuleScope.Original)))
        AppSettings.setGlobalRegexApplyTo(RegexRuleScope.Translation)

        assertEquals(
            listOf(RegexRuleScope.Translation),
            AppSettings.effectiveRules(null).map { it.scope },
        )
    }

    @Test
    fun `global and novel lists use their own apply-to`() {
        AppSettings.replaceGlobalRules(listOf(rule(1)))
        AppSettings.replaceNovelRules(novelUrl, listOf(rule(2)))
        AppSettings.setGlobalRegexApplyTo(RegexRuleScope.Original)
        AppSettings.setNovelRegexApplyTo(novelUrl, RegexRuleScope.Translation)

        assertEquals(
            listOf(RegexRuleScope.Original, RegexRuleScope.Translation),
            AppSettings.effectiveRules(novelUrl).map { it.scope },
        )
    }

    @Test
    fun `changing apply-to on an empty list only bumps the rules revision`() {
        val before = AppSettings.regexRulesRevision.value to
            AppSettings.regexRulesOriginalRevision.value

        AppSettings.setGlobalRegexApplyTo(RegexRuleScope.Translation)

        assertTrue(AppSettings.regexRulesRevision.value > before.first)
        assertEquals(before.second, AppSettings.regexRulesOriginalRevision.value)
    }

    @Test
    fun `changing apply-to changes the original projection when rules exist`() {
        AppSettings.replaceGlobalRules(listOf(rule(1)))
        val before = AppSettings.regexRulesOriginalRevision.value

        AppSettings.setGlobalRegexApplyTo(RegexRuleScope.Translation)

        assertTrue(AppSettings.regexRulesOriginalRevision.value > before)
    }

    @Test
    fun `a book without its own apply-to inherits the global one`() {
        AppSettings.replaceNovelRules(novelUrl, listOf(rule(2)))
        AppSettings.setGlobalRegexApplyTo(RegexRuleScope.Translation)

        assertEquals(RegexRuleScope.Translation, AppSettings.regexApplyTo(novelUrl))
        assertEquals(
            listOf(RegexRuleScope.Translation),
            AppSettings.effectiveRules(novelUrl).map { it.scope },
        )
    }
}
