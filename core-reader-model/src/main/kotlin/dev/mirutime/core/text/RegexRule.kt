package dev.mirutime.core.text

import kotlinx.serialization.Serializable

// One user regex cleanup rule, global or per-novel.
// `wholeWords` wraps the pattern in \b…\b.
@Serializable
data class RegexRule(
    val id: Long,
    val pattern: String,
    val replacement: String,
    val description: String,
    val enabled: Boolean,
    val wholeWords: Boolean,
)

// Pure regex helpers shared by the rules screen and its edit sheet.

// Compile error text or null when the pattern is valid.
fun regexError(pattern: String): String? {
    if (pattern.isBlank()) return null
    return try {
        Regex(pattern)
        null
    } catch (e: Exception) {
        e.message?.lineSequence()?.firstOrNull()?.take(120) ?: e.toString()
    }
}

// Whole-words wraps the pattern so it only matches between word boundaries.
fun RegexRule.compiled(): Regex? = try {
    Regex(if (wholeWords) "\\b(?:$pattern)\\b" else pattern)
} catch (e: Exception) {
    null
}

// Applies the ENABLED rules in order. A rule that fails to compile is skipped
// (it never breaks the text).
fun applyRules(rules: List<RegexRule>, text: String): String =
    rules.filter { it.enabled && it.pattern.isNotBlank() }
        .fold(text) { acc, rule ->
            val regex = rule.compiled()
            if (regex == null) acc else try {
                regex.replace(acc, rule.replacement)
            } catch (e: Exception) {
                acc
            }
        }
