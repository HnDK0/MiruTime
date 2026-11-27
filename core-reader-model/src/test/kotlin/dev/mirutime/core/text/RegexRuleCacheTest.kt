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

    // Word edges are lookarounds, not \b: \b is ASCII-only on desktop JVM and
    // refuses patterns that start or end with punctuation.
    @Test
    fun `wholeWords matches non-latin words`() {
        val cyrillic = rule("Иван", replacement = "X", wholeWords = true)

        assertEquals("Привет X и Иванка", applyRules(listOf(cyrillic), "Привет Иван и Иванка"))
    }

    @Test
    fun `wholeWords matches a term with trailing punctuation`() {
        val rule = rule("""ad\.""", replacement = "X", wholeWords = true)

        // "bad." contains "ad." but not as a whole word.
        assertEquals("X bad.", applyRules(listOf(rule), "ad. bad."))
    }

    @Test
    fun `wholeWords matches a term with leading punctuation`() {
        val rule = rule("""\.\.\.""", replacement = "", wholeWords = true)

        // '...' bounded by spaces: the old \b form could not match here.
        assertEquals("wait  now", applyRules(listOf(rule), "wait ... now"))
    }

    @Test
    fun `wholeWords never matches inside a longer word digit or underscore`() {
        val rule = rule("ad", replacement = "X", wholeWords = true)

        assertEquals("bad ads X ad_1 ad1", applyRules(listOf(rule), "bad ads ad ad_1 ad1"))
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
