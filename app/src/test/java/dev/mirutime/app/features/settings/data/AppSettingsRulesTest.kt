package dev.mirutime.app.features.settings.data

import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsRulesTest {
    private fun rule(scope: RegexRuleScope, id: Long = 1) = RegexRule(
        id = id,
        pattern = "x",
        replacement = "",
        description = "",
        enabled = true,
        wholeWords = false,
        scope = scope,
    )

    @Test
    fun `every edit bumps the rules revision`() {
        val before = AppSettings.regexRulesRevision.value
        val previous = AppSettings.globalRegexRules.toList()
        try {
            AppSettings.replaceGlobalRules(previous + rule(RegexRuleScope.Translation, id = 99))

            assertEquals(before + 1, AppSettings.regexRulesRevision.value)
        } finally {
            AppSettings.replaceGlobalRules(previous)
        }
    }

    @Test
    fun `translation-only edit does not bump the original revision`() {
        val previous = AppSettings.globalRegexRules.toList()
        try {
            AppSettings.replaceGlobalRules(emptyList())
            val before = AppSettings.regexRulesOriginalRevision.value

            AppSettings.replaceGlobalRules(listOf(rule(RegexRuleScope.Translation)))

            assertEquals(before, AppSettings.regexRulesOriginalRevision.value)
        } finally {
            AppSettings.replaceGlobalRules(previous)
        }
    }

    @Test
    fun `original edit bumps the original revision`() {
        val previous = AppSettings.globalRegexRules.toList()
        try {
            AppSettings.replaceGlobalRules(emptyList())
            val before = AppSettings.regexRulesOriginalRevision.value

            AppSettings.replaceGlobalRules(listOf(rule(RegexRuleScope.Original)))

            assertEquals(before + 1, AppSettings.regexRulesOriginalRevision.value)
        } finally {
            AppSettings.replaceGlobalRules(previous)
        }
    }

    @Test
    fun `identical list does not bump any revision`() {
        val previous = AppSettings.globalRegexRules.toList()
        try {
            AppSettings.replaceGlobalRules(listOf(rule(RegexRuleScope.Both)))
            val rulesRevision = AppSettings.regexRulesRevision.value
            val originalRevision = AppSettings.regexRulesOriginalRevision.value

            AppSettings.replaceGlobalRules(listOf(rule(RegexRuleScope.Both)))

            assertEquals(rulesRevision, AppSettings.regexRulesRevision.value)
            assertEquals(originalRevision, AppSettings.regexRulesOriginalRevision.value)
        } finally {
            AppSettings.replaceGlobalRules(previous)
        }
    }
}
