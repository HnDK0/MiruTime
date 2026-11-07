package dev.mirutime.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class RegexRuleCacheTest {

    @Before
    fun resetCache() {
        clearCompiledRuleCache()
    }

    private fun rule(
        pattern: String,
        id: Long = 1,
        replacement: String = "",
        enabled: Boolean = true,
        wholeWords: Boolean = false,
    ) = RegexRule(
        id = id,
        pattern = pattern,
        replacement = replacement,
        description = "",
        enabled = enabled,
        wholeWords = wholeWords,
    )

    @Test
    fun `same pattern and wholeWords share one compiled regex`() {
        assertSame(rule("foo").compiled(), rule("foo", id = 2).compiled())
    }

    @Test
    fun `wholeWords has its own compiled regex`() {
        assertNotSame(rule("foo").compiled(), rule("foo", wholeWords = true).compiled())
    }

    @Test
    fun `invalid pattern stays null and is skipped`() {
        val invalid = rule("[")

        assertNull(invalid.compiled())
        assertNull(invalid.compiled())
        assertEquals("text", applyRules(listOf(invalid), "text"))
        assertEquals("text", applyRulesFor(listOf(invalid), "text", RegexRuleTarget.Original))
    }

    @Test
    fun `applyRules keeps order and enabled semantics`() {
        val rules = listOf(
            rule("a", id = 1, replacement = "b"),
            rule("b", id = 2, replacement = "c"),
            rule("c", id = 3, replacement = "NO", enabled = false),
        )

        assertEquals("cc", applyRules(rules, "aa"))
    }
}
