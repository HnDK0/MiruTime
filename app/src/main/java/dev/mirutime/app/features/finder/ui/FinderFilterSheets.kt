@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.finder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.app.features.finder.domain.BrowseFilters
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.finder.domain.SourceSort
import dev.mirutime.app.features.library.domain.ContentType

enum class BrowseSheetKind { Type, Language, Sort, Pins }

// The four Finder filter sheets (each filter is a bottom
// sheet). Type / language / sort edit a draft that Apply commits; pins act
// immediately (a pin is a per-source switch, not a filter) and Done just
// closes. Multi-selects: nothing selected = no restriction.
@Composable
fun BrowseFilterSheet(
    kind: BrowseSheetKind,
    filters: BrowseFilters,
    sources: List<CatalogSource>,
    languages: List<String>,
    onApply: (BrowseFilters) -> Unit,
    onTogglePin: (String) -> Unit,
    onClose: () -> Unit,
    // Source whose pin write is in flight: the row stays visible and keeps the
    // optimistic switch state; the other switches wait for the write.
    pinningId: String? = null,
) {
    var draft by remember { mutableStateOf(filters) }
    val extra = LocalMiruExtraColors.current

    val title = stringResource(
        when (kind) {
            BrowseSheetKind.Type -> R.string.finder_sheet_type_title
            BrowseSheetKind.Language -> R.string.finder_sheet_language_title
            BrowseSheetKind.Sort -> R.string.finder_sheet_sort_title
            BrowseSheetKind.Pins -> R.string.finder_sheet_pins_title
        },
    )
    val description = stringResource(
        when (kind) {
            BrowseSheetKind.Type -> R.string.finder_sheet_type_desc
            BrowseSheetKind.Language -> R.string.finder_sheet_language_desc
            BrowseSheetKind.Sort -> R.string.finder_sheet_sort_desc
            BrowseSheetKind.Pins -> R.string.finder_sheet_pins_desc
        },
    )

    AppBottomSheet(
        title = title,
        description = description,
        onClose = onClose,
        doneLabel = stringResource(
            if (kind == BrowseSheetKind.Pins) R.string.lib_done else R.string.finder_apply,
        ),
        onDone = {
            if (kind != BrowseSheetKind.Pins) onApply(draft)
            onClose()
        },
        // One Reset standard: the sheet header carries it (template:
        // CatalogFilterSheet), only for the kinds with something to reset.
        headerAction = when (kind) {
            BrowseSheetKind.Type -> {
                { ResetHeaderButton(draft.types.isNotEmpty()) { draft = draft.copy(types = emptySet()) } }
            }
            BrowseSheetKind.Language -> {
                { ResetHeaderButton(draft.languages.isNotEmpty()) { draft = draft.copy(languages = emptySet()) } }
            }
            else -> null
        },
    ) {
        when (kind) {
            BrowseSheetKind.Type -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    PluginContentTypes.forEach { type ->
                        MiruFilterChip(
                            selected = type in draft.types,
                            onClick = {
                                draft = draft.copy(
                                    types = if (type in draft.types) draft.types - type else draft.types + type,
                                )
                            },
                            label = typeLabel(type),
                        )
                    }
                }
            }
            BrowseSheetKind.Language -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    languages.forEach { language ->
                        MiruFilterChip(
                            selected = language in draft.languages,
                            onClick = {
                                draft = draft.copy(
                                    languages = if (language in draft.languages) {
                                        draft.languages - language
                                    } else {
                                        draft.languages + language
                                    },
                                )
                            },
                            label = language,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.finder_sheet_language_local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(top = MiruSpace.S),
                )
            }
            BrowseSheetKind.Sort -> {
                SourceSort.entries.forEach { sort ->
                    val selected = draft.sort == sort
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) {
                                draft = if (selected) {
                                    draft.copy(descending = !draft.descending)
                                } else {
                                    // Name reads A-Z, the others biggest first.
                                    draft.copy(sort = sort, descending = sort == SourceSort.Library)
                                }
                            }
                            .defaultMinSize(minHeight = MiruSize.Touch),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = stringResource(
                                when (sort) {
                                    SourceSort.Name -> R.string.finder_source_sort_name
                                    SourceSort.Library -> R.string.finder_source_sort_library
                                    SourceSort.Language -> R.string.finder_source_sort_language
                                },
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = MiruSpace.M),
                        )
                        if (selected) {
                            Icon(
                                imageVector = if (draft.descending) {
                                    Icons.Filled.ArrowDownward
                                } else {
                                    Icons.Filled.ArrowUpward
                                },
                                contentDescription = stringResource(
                                    if (draft.descending) {
                                        R.string.finder_sort_desc_cd
                                    } else {
                                        R.string.finder_sort_asc_cd
                                    },
                                ),
                                tint = extra.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            BrowseSheetKind.Pins -> {
                sources.forEach { source ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Toggleable (not clickable+R Switch) so the row's
                            // on/off state is announced; Switch itself carries
                            // no semantics when onCheckedChange is null.
                            .toggleable(
                                value = source.pinned,
                                enabled = pinningId == null,
                                role = Role.Switch,
                                onValueChange = { onTogglePin(source.id) },
                            )
                            .defaultMinSize(minHeight = MiruSize.Row),
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
                    ) {
                        SourceIconTile(source)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sourceDisplayName(source),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (source.local) {
                                Text(
                                    text = stringResource(R.string.finder_pins_local_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extra.muted,
                                    maxLines = 1,
                                )
                            }
                        }
                        Switch(
                            checked = source.pinned,
                            onCheckedChange = null,
                            enabled = pinningId == null,
                            // Same pair as SettingsSwitchRow: accent/onAccent
                            // when on, muted ink when off. No primary/accent
                            // mismatch on photo palettes.
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = extra.onAccent,
                                checkedTrackColor = extra.accent,
                                checkedBorderColor = extra.accent,
                                checkedIconColor = extra.onAccent,
                                uncheckedThumbColor = extra.muted,
                                uncheckedTrackColor = extra.muted.copy(alpha = 0.32f),
                                uncheckedBorderColor = extra.muted.copy(alpha = 0.55f),
                                uncheckedIconColor = extra.muted,
                                disabledCheckedThumbColor = extra.onAccent.copy(alpha = 0.70f),
                                disabledCheckedTrackColor = extra.accent.copy(alpha = 0.40f),
                                disabledCheckedBorderColor = extra.accent.copy(alpha = 0.40f),
                                disabledCheckedIconColor = extra.onAccent.copy(alpha = 0.50f),
                                disabledUncheckedThumbColor = extra.muted.copy(alpha = 0.60f),
                                disabledUncheckedTrackColor = extra.muted.copy(alpha = 0.22f),
                                disabledUncheckedBorderColor = extra.muted.copy(alpha = 0.40f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResetHeaderButton(enabled: Boolean, onClick: () -> Unit) {
    MiruButton(
        onClick = onClick,
        style = MiruButtonStyle.Secondary,
        enabled = enabled,
    ) {
        Text(text = stringResource(R.string.finder_reset))
    }
}
