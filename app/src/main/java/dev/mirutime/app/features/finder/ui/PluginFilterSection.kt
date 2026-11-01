@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.finder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import dev.mirutime.app.R
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterType
import dev.mirutime.app.features.finder.domain.PluginFilterValue
import dev.mirutime.app.features.finder.domain.TriState
import dev.mirutime.app.features.finder.domain.cycleTristate
import dev.mirutime.app.features.finder.domain.toggleCheckbox
import dev.mirutime.app.features.finder.domain.toggleSortDirection
import dev.mirutime.app.features.finder.domain.toggleTag
import dev.mirutime.app.features.finder.domain.withSort
import dev.mirutime.uikit.ChipOption
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SettingsRadioRow
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.uikit.SheetSection

// One plugin-declared catalogue filter, rendered by its declared type. The
// section title is the plugin's own label verbatim; the sheet never branches
// on a plugin id.
@Composable
internal fun PluginFilterSection(
    filter: PluginFilter,
    value: PluginFilterValue,
    textHistory: List<String>,
    onChange: (PluginFilterValue) -> Unit,
) {
    when (filter.type) {
        PluginFilterType.Sort -> SortFilter(filter, value, onChange)
        PluginFilterType.Select -> SelectFilter(filter, value, onChange)
        PluginFilterType.Checkbox -> CheckboxFilter(filter, value, onChange)
        PluginFilterType.Tristate -> TristateFilter(filter, value, onChange)
        PluginFilterType.Switch -> SwitchFilter(filter, value, onChange)
        PluginFilterType.Text -> TextFilter(filter, value, textHistory, onChange)
        PluginFilterType.TagInput -> TagInputFilter(filter, value, onChange)
    }
}

@Composable
private fun SortFilter(
    filter: PluginFilter,
    value: PluginFilterValue,
    onChange: (PluginFilterValue) -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val current = value as? PluginFilterValue.Sort ?: PluginFilterValue.Sort(
        filter.defaultValue,
        filter.defaultAscending,
    )
    val draft = remember(filter.key, current) {
        mapOf<String, PluginFilterValue>(filter.key to current)
    }
    SheetSection(title = filter.label, preserveCase = true) {
        filter.options.forEach { option ->
            val selected = current.value == option.value
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) {
                        if (selected) {
                            onChange(draft.toggleSortDirection(filter)[filter.key] ?: current)
                        } else {
                            onChange(draft.withSort(filter, option.value)[filter.key] ?: current)
                        }
                    }
                    .defaultMinSize(minHeight = MiruSize.Touch),
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = MiruSpace.M),
                )
                if (selected) {
                    Icon(
                        imageVector = if (current.ascending) {
                            Icons.Filled.ArrowUpward
                        } else {
                            Icons.Filled.ArrowDownward
                        },
                        contentDescription = stringResource(
                            if (current.ascending) {
                                R.string.finder_sort_asc_cd
                            } else {
                                R.string.finder_sort_desc_cd
                            },
                        ),
                        tint = extra.accent,
                        modifier = Modifier.size(MiruSpace.L),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectFilter(
    filter: PluginFilter,
    value: PluginFilterValue,
    onChange: (PluginFilterValue) -> Unit,
) {
    val current = (value as? PluginFilterValue.Select)?.value ?: filter.defaultValue
    // <= 3 options: chips right in the section. More: a value row that
    // expands the one-of-N list inline; never a second
    // ModalBottomSheet on top of the filter sheet.
    if (filter.options.size <= 3) {
        SheetSection(title = filter.label, preserveCase = true) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            ) {
                filter.options.forEach { option ->
                    MiruFilterChip(
                        selected = option.value == current,
                        onClick = { onChange(PluginFilterValue.Select(option.value)) },
                        label = option.label,
                    )
                }
            }
        }
    } else {
        var open by remember(filter.key) { mutableStateOf(false) }
        val currentLabel = filter.options.firstOrNull { it.value == current }?.label ?: current
        SheetSection(title = filter.label, preserveCase = true) {
            SettingsValueRow(
                title = currentLabel,
                value = "",
                onClick = { open = !open },
                horizontalPadding = Dp(0f),
                trailing = {
                    Icon(
                        imageVector = if (open) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = null,
                        tint = LocalMiruExtraColors.current.muted,
                    )
                },
            )
            // Inline expansion, not a second ChoiceSheet: a ModalBottomSheet
            // stacked on this one breaks back/dismiss ordering.
            if (open) {
                filter.options.forEach { option ->
                    SettingsRadioRow(
                        title = option.label,
                        selected = option.value == current,
                        onClick = {
                            onChange(PluginFilterValue.Select(option.value))
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckboxFilter(
    filter: PluginFilter,
    value: PluginFilterValue,
    onChange: (PluginFilterValue) -> Unit,
) {
    val current = (value as? PluginFilterValue.Checkbox)?.included.orEmpty()
    val draft = remember(filter.key, current) {
        mapOf<String, PluginFilterValue>(filter.key to PluginFilterValue.Checkbox(current))
    }
    val onToggle: (String) -> Unit = { option ->
        val next = draft.toggleCheckbox(filter, option)[filter.key]
        if (next != null) onChange(next)
    }
    SheetSection(title = filter.label, preserveCase = true) {
        FilterChipGroup(
            options = filter.options.map { ChipOption(it.value, it.label) },
            selected = current,
            onToggle = onToggle,
            sortAlphabetically = false,
        )
    }
}

@Composable
private fun TristateFilter(
    filter: PluginFilter,
    value: PluginFilterValue,
    onChange: (PluginFilterValue) -> Unit,
) {
    val states = (value as? PluginFilterValue.Tristate)?.states.orEmpty()
    val draft = remember(filter.key, states) {
        mapOf<String, PluginFilterValue>(filter.key to PluginFilterValue.Tristate(states))
    }
    SheetSection(title = filter.label, preserveCase = true) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        ) {
            filter.options.forEach { option ->
                val state = states[option.value] ?: TriState.Neutral
                MiruFilterChip(
                    selected = state != TriState.Neutral,
                    onClick = {
                        val next = draft.cycleTristate(filter, option.value)[filter.key]
                        if (next != null) onChange(next)
                    },
                    label = option.label,
                    leadingIcon = when (state) {
                        TriState.Include -> Icons.Filled.Check
                        TriState.Exclude -> Icons.Filled.Close
                        TriState.Neutral -> null
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchFilter(
    filter: PluginFilter,
    value: PluginFilterValue,
    onChange: (PluginFilterValue) -> Unit,
) {
    val checked = (value as? PluginFilterValue.Toggle)?.enabled ?: false
    SheetSection(title = filter.label, preserveCase = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MiruSize.Touch),
        ) {
            // The section header is the label; the row only carries the
            // switch, so the sheet never prints the same label twice.
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = { onChange(PluginFilterValue.Toggle(it)) },
                modifier = Modifier.semantics {
                    contentDescription = filter.label
                },
            )
        }
    }
}

@Composable
private fun TextFilter(
    filter: PluginFilter,
    value: PluginFilterValue,
    history: List<String>,
    onChange: (PluginFilterValue) -> Unit,
) {
    val text = (value as? PluginFilterValue.TextInput)?.value.orEmpty()
    SheetSection(title = filter.label, preserveCase = true) {
        OutlinedTextField(
            value = text,
            onValueChange = { onChange(PluginFilterValue.TextInput(it)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {}),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { onChange(PluginFilterValue.TextInput("")) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.finder_cd_clear_search),
                        )
                    }
                }
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        // Optional inline hints (NoveLA text_history): only while the field is
        // empty, so a suggestion never fights what the user is typing.
        if (text.isBlank() && history.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                modifier = Modifier.padding(top = MiruSpace.S),
            ) {
                history.take(5).forEach { entry ->
                    MiruFilterChip(
                        selected = false,
                        onClick = { onChange(PluginFilterValue.TextInput(entry)) },
                        label = entry,
                    )
                }
            }
        }
    }
}

@Composable
private fun TagInputFilter(
    filter: PluginFilter,
    value: PluginFilterValue,
    onChange: (PluginFilterValue) -> Unit,
) {
    val selected = (value as? PluginFilterValue.Tags)?.values.orEmpty()
    var input by remember(filter.key) { mutableStateOf("") }
    val draft = remember(filter.key, selected) {
        mapOf<String, PluginFilterValue>(filter.key to PluginFilterValue.Tags(selected))
    }
    val add: (String) -> Unit = { tag ->
        val next = draft.toggleTag(filter, tag)[filter.key]
        if (next != null) onChange(next)
        input = ""
    }
    val clean = input.trim()
    val suggestions = filter.options
        .filter { it.value !in selected }
        .filter { clean.isBlank() || it.label.contains(clean, ignoreCase = true) }
        .take(10)
    val customAllowed = filter.allowCustom && clean.isNotEmpty() &&
        clean !in selected && filter.options.none { it.value.equals(clean, ignoreCase = true) }

    SheetSection(title = filter.label, preserveCase = true) {
        if (selected.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            ) {
                selected.forEach { tag ->
                    val label = filter.options.firstOrNull { it.value == tag }?.label ?: tag
                    MiruFilterChip(
                        selected = true,
                        onClick = { add(tag) },
                        label = label,
                        leadingIcon = Icons.Filled.Close,
                    )
                }
            }
            Spacer(modifier = Modifier.size(MiruSpace.S))
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = {
                Text(
                    text = stringResource(R.string.finder_tag_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (clean.isNotEmpty()) add(clean) },
            ),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty() || customAllowed) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                modifier = Modifier.padding(top = MiruSpace.S),
            ) {
                suggestions.forEach { option ->
                    MiruFilterChip(
                        selected = false,
                        onClick = { add(option.value) },
                        label = option.label,
                    )
                }
                if (customAllowed) {
                    MiruFilterChip(
                        selected = false,
                        onClick = { add(clean) },
                        label = stringResource(R.string.finder_tag_add_fmt, clean),
                        leadingIcon = Icons.Filled.Add,
                    )
                }
            }
        }
    }
}
