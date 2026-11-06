package dev.mirutime.app.features.settings.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChipOption
import dev.mirutime.uikit.ChipRow
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsHint
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.app.features.book.data.PluginBookIndex
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import dev.mirutime.core.text.RegexRuleTarget
import dev.mirutime.core.text.applyRules
import dev.mirutime.core.text.applyRulesFor
import dev.mirutime.core.text.regexError

private const val SampleText = "Chapter 1 [ad] continues  here"

// Regex text cleanup (ported from NoveLA's RegexCleanupSettingsScreen).
// One screen for two scopes:
//   novelUrl == null  — the GLOBAL rules (Settings)
//   novelUrl != null  — the rules of ONE novel (opened from the book's tools),
//                       applied on top of the global ones; "Move to global"
//                       and "Delete novel rules" live here.
// Rules run in order on every chapter text; each has pattern, replacement,
// description, an enabled switch and "whole words only". The edit sheet checks
// the pattern live (invalid / duplicate) and previews the single rule on a
// sample; the page previews the whole effective chain.
@Composable
fun RegexRulesScreen(
    novelUrl: String?,
    contentPadding: PaddingValues,
) {
    val s = AppSettings
    val context = LocalContext.current
    val resources = LocalResources.current
    var query by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf(SampleText) }
    var editing by remember { mutableStateOf<RegexRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleteRule by remember { mutableStateOf<RegexRule?>(null) }
    var deleteAll by remember { mutableStateOf(false) }

    val rules: List<RegexRule> =
        if (novelUrl == null) s.globalRegexRules.toList() else s.novelRegexRules[novelUrl].orEmpty()
    fun store(list: List<RegexRule>) {
        if (novelUrl == null) {
            s.replaceGlobalRules(list)
        } else {
            s.replaceNovelRules(novelUrl, list)
        }
    }
    val shown = rules.filter {
        query.isBlank() || it.pattern.contains(query.trim(), ignoreCase = true) ||
            it.description.contains(query.trim(), ignoreCase = true)
    }
    val effective = s.effectiveRules(novelUrl)

    SettingsPage(contentPadding) {
        if (novelUrl != null) {
            item(key = "novel") {
                MiruSectionCard(
                    title = stringResource(R.string.st_rx_novel_title_fmt, PluginBookIndex.displayTitle(novelUrl)),
                    description = stringResource(
                        R.string.st_rx_novel_desc_fmt,
                        s.globalRegexRules.count { it.enabled },
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    MiruActionRow(
                        icon = Icons.Filled.SwapVert,
                        title = stringResource(R.string.st_rx_move_global),
                        subtitle = stringResource(R.string.st_rx_move_global_desc),
                        onClick = {
                            if (rules.isEmpty()) {
                                Toast.makeText(context, resources.getString(R.string.st_rx_nothing_to_move), Toast.LENGTH_SHORT).show()
                            } else {
                                val known = s.globalRegexRules.map { it.pattern }.toSet()
                                s.replaceGlobalRules(
                                    s.globalRegexRules + rules.filter { it.pattern !in known },
                                )
                                store(emptyList())
                                Toast.makeText(context, resources.getString(R.string.st_rx_moved), Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    MiruRowDivider()
                    MiruActionRow(
                        icon = Icons.Filled.Delete,
                        title = stringResource(R.string.st_rx_delete_novel),
                        subtitle = stringResource(R.string.st_rx_delete_novel_desc),
                        onClick = { if (rules.isNotEmpty()) deleteAll = true },
                    )
                }
            }
        }
        item(key = "add") {
            MiruCard(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.st_rx_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.st_clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(MiruSpace.M))
                Button(
                    onClick = { creating = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MiruSize.Button),
                ) {
                    Text(text = stringResource(R.string.st_rx_add), maxLines = 1)
                }
            }
        }
        item(key = "rules") {
            MiruSectionCard(
                title = stringResource(
                    if (novelUrl == null) R.string.st_rx_rules_global else R.string.st_rx_rules_novel,
                ),
                description = stringResource(
                    R.string.st_rx_rules_count_fmt,
                    rules.count { it.enabled },
                    rules.size,
                ),
                contentPadding = PaddingValues(0.dp),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.st_rx_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMiruExtraColors.current.muted,
                        modifier = Modifier.padding(MiruSpace.L),
                    )
                }
                shown.forEachIndexed { index, rule ->
                    if (index > 0) MiruRowDivider()
                    RuleRow(
                        rule = rule,
                        onClick = { editing = rule },
                        onToggle = { enabled ->
                            store(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                        },
                    )
                }
            }
        }
        item(key = "preview") {
            MiruSectionCard(
                title = stringResource(R.string.st_rx_preview),
                description = stringResource(R.string.st_rx_preview_desc),
            ) {
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    label = { Text(stringResource(R.string.st_rx_sample)) },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.st_rx_result),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.M),
                )
                Text(
                    text = stringResource(R.string.st_rx_scope_original),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.Xs),
                )
                Text(
                    text = applyRulesFor(effective, sample, RegexRuleTarget.Original).ifEmpty { "∅" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.st_rx_scope_translation),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.Xs),
                )
                Text(
                    text = applyRulesFor(effective, sample, RegexRuleTarget.Translation).ifEmpty { "∅" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SettingsHint(stringResource(R.string.st_rx_order_hint))
            }
        }
    }

    if (creating || editing != null) {
        val current = editing
        RuleEditSheet(
            rule = current,
            takenPatterns = rules.filter { it.id != current?.id }.map { it.pattern }.toSet(),
            onSave = { saved ->
                store(
                    if (current == null) {
                        rules + saved.copy(id = s.newRuleId())
                    } else {
                        rules.map { if (it.id == current.id) saved.copy(id = current.id) else it }
                    },
                )
                creating = false
                editing = null
            },
            onDelete = current?.let { target ->
                {
                    editing = null
                    deleteRule = target
                }
            },
            onClose = {
                creating = false
                editing = null
            },
        )
    }
    deleteRule?.let { rule ->
        AppBottomSheet(
            title = stringResource(R.string.st_rx_delete_rule),
            description = stringResource(R.string.st_rx_delete_rule_desc_fmt, rule.pattern),
            onClose = { deleteRule = null },
            doneLabel = stringResource(R.string.st_delete),
            doneDanger = true,
            onDone = {
                store(rules.filter { it.id != rule.id })
                deleteRule = null
            },
        ) {
            Text(
                text = rule.pattern,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (deleteAll) {
        AppBottomSheet(
            title = stringResource(R.string.st_rx_delete_novel),
            description = stringResource(R.string.st_rx_delete_novel_confirm),
            onClose = { deleteAll = false },
            doneLabel = stringResource(R.string.st_delete),
            doneDanger = true,
            onDone = {
                store(emptyList())
                deleteAll = false
            },
        ) {
            Text(
                text = stringResource(R.string.st_rx_rules_count_fmt, rules.count { it.enabled }, rules.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RuleRow(rule: RegexRule, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(start = MiruSpace.L, end = MiruSpace.L, top = MiruSpace.S, bottom = MiruSpace.S),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.pattern,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (rule.replacement.isEmpty()) {
                    stringResource(R.string.st_rx_replace_empty)
                } else {
                    "→ \"${rule.replacement}\""
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = buildList {
                if (rule.description.isNotBlank()) add(rule.description)
                if (rule.wholeWords) add(stringResource(R.string.st_rx_whole_short))
                when (rule.scope) {
                    RegexRuleScope.Original -> add(stringResource(R.string.st_rx_scope_original))
                    RegexRuleScope.Translation -> add(stringResource(R.string.st_rx_scope_translation))
                    RegexRuleScope.Both -> Unit
                }
            }.joinToString(" · ")
            if (note.isNotEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

// New / edit rule. Save is disabled while the pattern is blank, invalid or a
// duplicate; the single-rule preview uses the draft (enabled or not).
@Composable
private fun RuleEditSheet(
    rule: RegexRule?,
    takenPatterns: Set<String>,
    onSave: (RegexRule) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var pattern by remember { mutableStateOf(rule?.pattern.orEmpty()) }
    var replacement by remember { mutableStateOf(rule?.replacement.orEmpty()) }
    var description by remember { mutableStateOf(rule?.description.orEmpty()) }
    var wholeWords by remember { mutableStateOf(rule?.wholeWords ?: false) }
    var enabled by remember { mutableStateOf(rule?.enabled ?: true) }
    var scope by remember { mutableStateOf(rule?.scope ?: RegexRuleScope.Both) }
    val extra = LocalMiruExtraColors.current

    val compileError = regexError(pattern)
    val duplicate = pattern.isNotBlank() && pattern in takenPatterns
    val error = when {
        compileError != null -> stringResource(R.string.st_rx_invalid_fmt, compileError)
        duplicate -> stringResource(R.string.st_rx_duplicate)
        else -> null
    }
    val draft = RegexRule(rule?.id ?: 0L, pattern, replacement, description, true, wholeWords, scope)

    AppBottomSheet(
        title = stringResource(if (rule == null) R.string.st_rx_new else R.string.st_rx_edit),
        description = stringResource(R.string.st_rx_sheet_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.st_save),
        doneEnabled = pattern.isNotBlank() && error == null,
        onDone = {
            onSave(RegexRule(rule?.id ?: 0L, pattern, replacement, description.trim(), enabled, wholeWords, scope))
        },
    ) {
        OutlinedTextField(
            value = pattern,
            onValueChange = { pattern = it },
            label = { Text(stringResource(R.string.st_rx_pattern)) },
            placeholder = { Text(stringResource(R.string.st_rx_pattern_hint)) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            label = { Text(stringResource(R.string.st_rx_replacement)) },
            placeholder = { Text(stringResource(R.string.st_rx_replacement_hint)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MiruSpace.S),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(stringResource(R.string.st_rx_description)) },
            placeholder = { Text(stringResource(R.string.st_rx_description_hint)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MiruSpace.S),
        )
        Text(
            text = stringResource(R.string.st_rx_scope),
            style = MaterialTheme.typography.labelSmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.S),
        )
        ChipRow(
            options = listOf(
                ChipOption(RegexRuleScope.Original.name, stringResource(R.string.st_rx_scope_original)),
                ChipOption(RegexRuleScope.Translation.name, stringResource(R.string.st_rx_scope_translation)),
                ChipOption(RegexRuleScope.Both.name, stringResource(R.string.st_rx_scope_both)),
            ),
            selectedIds = setOf(scope.name),
            onToggle = { id -> scope = RegexRuleScope.valueOf(id) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsHint(stringResource(R.string.st_rx_scope_hint))
        SettingsSwitchRow(
            title = stringResource(R.string.st_rx_whole_words),
            checked = wholeWords,
            onChange = { wholeWords = it },
        )
        SettingsSwitchRow(
            title = stringResource(R.string.st_rx_enabled),
            checked = enabled,
            onChange = { enabled = it },
        )
        Text(
            text = stringResource(R.string.st_rx_result),
            style = MaterialTheme.typography.labelSmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.S),
        )
        Text(
            text = if (error != null || pattern.isBlank()) {
                stringResource(R.string.st_rx_sample_text)
            } else {
                applyRules(listOf(draft), stringResource(R.string.st_rx_sample_text)).ifEmpty { "∅" }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.st_delete),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
