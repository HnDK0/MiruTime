package dev.mirutime.core.text

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

// Where a cleanup rule is applied. `Both` is NoveLA's behaviour: the rule runs
// on the parsed original (also the translator's input) and on the translation.
@Serializable
enum class RegexRuleScope { Original, Translation, Both }

enum class RegexRuleTarget { Original, Translation }

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
    val scope: RegexRuleScope = RegexRuleScope.Both,
)

fun RegexRule.appliesTo(target: RegexRuleTarget): Boolean = when (scope) {
    RegexRuleScope.Both -> true
    RegexRuleScope.Original -> target == RegexRuleTarget.Original
    RegexRuleScope.Translation -> target == RegexRuleTarget.Translation
}

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

// Editing a rule on a loaded chapter recompiles the same pattern for every
// block and every translation emission. Cache the compiled Regex process-wide;
// `Regex`/`Pattern` are safe to share, a Matcher is created per replace call.
private const val COMPILED_CACHE_LIMIT = 512
private val NEVER_MATCH = Regex("(?!)")
private val plainCache = ConcurrentHashMap<String, Regex>()
private val wholeWordsCache = ConcurrentHashMap<String, Regex>()

// Whole-words wraps the pattern so it only matches between word boundaries.
fun RegexRule.compiled(): Regex? {
    val cache = if (wholeWords) wholeWordsCache else plainCache
    val compiled = cache[pattern] ?: run {
        val regex = try {
            Regex(if (wholeWords) "\\b(?:$pattern)\\b" else pattern)
        } catch (_: Exception) {
            NEVER_MATCH
        }
        if (cache.size >= COMPILED_CACHE_LIMIT) cache.clear()
        cache[pattern] = regex
        regex
    }
    return compiled.takeUnless { it === NEVER_MATCH }
}

internal fun clearCompiledRuleCache() {
    plainCache.clear()
    wholeWordsCache.clear()
}

// Applies the ENABLED rules in order. A rule that fails to compile is skipped
// (it never breaks the text).
fun applyRules(rules: List<RegexRule>, text: String): String = applyFiltered(rules, text)

// Applies only the ENABLED rules that target this stage (original / translation).
fun applyRulesFor(rules: List<RegexRule>, text: String, target: RegexRuleTarget): String =
    applyFiltered(rules, text) { it.appliesTo(target) }

private inline fun applyFiltered(
    rules: List<RegexRule>,
    text: String,
    predicate: (RegexRule) -> Boolean = { true },
): String =
    rules.filter { it.enabled && it.pattern.isNotBlank() && predicate(it) }
        .fold(text) { acc, rule ->
            val regex = rule.compiled()
            if (regex == null) acc else try {
                regex.replace(acc, rule.replacement)
            } catch (e: Exception) {
                acc
            }
        }
