package dev.mirutime.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class RegexRuleScopeTest {
    private fun rule(
        scope: RegexRuleScope,
        pattern: String = "ad",
        replacement: String = "",
        enabled: Boolean = true,
    ) = RegexRule(
        id = 1,
        pattern = pattern,
        replacement = replacement,
        description = "",
        enabled = enabled,
        wholeWords = false,
        scope = scope,
    )

    @Test
    fun `original rules run only on the original`() {
        val rules = listOf(rule(RegexRuleScope.Original))
        assertEquals(" text", applyRulesFor(rules, "ad text", RegexRuleTarget.Original))
        assertEquals("ad text", applyRulesFor(rules, "ad text", RegexRuleTarget.Translation))
    }

    @Test
    fun `translation rules run only on the translation`() {
        val rules = listOf(rule(RegexRuleScope.Translation))
        assertEquals("ad text", applyRulesFor(rules, "ad text", RegexRuleTarget.Original))
        assertEquals(" text", applyRulesFor(rules, "ad text", RegexRuleTarget.Translation))
    }

    @Test
    fun `both rules run on both stages`() {
        val rules = listOf(rule(RegexRuleScope.Both))
        assertEquals(" text", applyRulesFor(rules, "ad text", RegexRuleTarget.Original))
        assertEquals(" text", applyRulesFor(rules, "ad text", RegexRuleTarget.Translation))
    }

    @Test
    fun `disabled and broken rules never change the text`() {
        val rules = listOf(
            rule(RegexRuleScope.Both, enabled = false),
            rule(RegexRuleScope.Both, pattern = "(unclosed"),
        )
        assertEquals("ad text", applyRulesFor(rules, "ad text", RegexRuleTarget.Original))
        assertEquals("ad text", applyRulesFor(rules, "ad text", RegexRuleTarget.Translation))
    }

    @Test
    fun `applyRules without a target keeps every enabled rule`() {
        val rules = listOf(
            rule(RegexRuleScope.Original, pattern = "a", replacement = "1"),
            rule(RegexRuleScope.Translation, pattern = "b", replacement = "2"),
        )
        assertEquals("1 2", applyRules(rules, "a b"))
    }
}
