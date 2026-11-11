package dev.mirutime.app.features.settings.data

import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import dev.mirutime.core.text.regexError
import kotlinx.serialization.Serializable

/** Portable regex-rule file: one screen's rules, no device-specific ids. */
@Serializable
data class RegexRulesFile(
    val version: Int = RegexRulesFormatVersion,
    val applyTo: RegexRuleScope = RegexRuleScope.Both,
    val rules: List<RegexRule> = emptyList(),
)

data class RegexImportResult(
    val applyTo: RegexRuleScope,
    val added: List<RegexRule>,
    val duplicates: Int,
    val invalid: Int,
)

const val RegexRulesFormatVersion = 1

/** A rules file is a settings document; anything bigger is not ours. */
const val MaxRegexRulesFileChars = 1_000_000
const val MaxImportedRegexRules = 500
private const val MaxImportedPatternChars = 2_000

fun exportRegexRules(rules: List<RegexRule>, applyTo: RegexRuleScope): String =
    BackupJson.encodeToString(
        RegexRulesFile.serializer(),
        RegexRulesFile(applyTo = applyTo, rules = rules),
    )

/**
 * Reads a rules file and returns the rules this screen can add. Import is
 * additive: an existing pattern wins, an invalid pattern is skipped, and every
 * incoming id is replaced so two devices' files never collide.
 */
fun importRegexRules(
    text: String,
    existingPatterns: Set<String>,
    newId: () -> Long,
): Result<RegexImportResult> = runCatching {
    require(text.length <= MaxRegexRulesFileChars) { "Rules file is too large" }
    val file = BackupJson.decodeFromString(RegexRulesFile.serializer(), text)
    require(file.version <= RegexRulesFormatVersion) { "Unsupported rules version" }

    val seen = existingPatterns.toMutableSet()
    val added = ArrayList<RegexRule>()
    var duplicates = 0
    var invalid = (file.rules.size - MaxImportedRegexRules).coerceAtLeast(0)
    file.rules.take(MaxImportedRegexRules).forEach { rule ->
        val pattern = rule.pattern.trim()
        when {
            pattern.isBlank() || pattern.length > MaxImportedPatternChars -> invalid++
            regexError(pattern) != null -> invalid++
            !seen.add(pattern) -> duplicates++
            else -> added += rule.copy(id = newId(), pattern = pattern)
        }
    }
    RegexImportResult(
        applyTo = file.applyTo,
        added = added,
        duplicates = duplicates,
        invalid = invalid,
    )
}
