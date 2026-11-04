@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.finder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterValue
import dev.mirutime.app.features.finder.domain.pluginActiveFilterCount
import dev.mirutime.app.features.finder.domain.pluginFilterDefaults
import dev.mirutime.app.features.finder.domain.shouldShowSearchLanguageHint
import dev.mirutime.app.features.finder.domain.toPluginFilterDraft
import dev.mirutime.app.features.finder.domain.withPluginDraft

// Pure decision table of the filter sheet, kept out of the composable so the
// "no request on open/schema" contract is unit-testable.
internal object CatalogFilterEmission {
    // Reset is reachable while the sheet differs from the reset state, and
    // while the applied snapshot still carries plugin values the current
    // schema no longer declares (they have to be cleared somehow).
    fun canReset(
        applied: CatalogFilters,
        draft: CatalogFilters,
        local: Boolean,
        pluginDraft: Map<String, PluginFilterValue>,
        pluginDefaults: Map<String, PluginFilterValue>,
    ): Boolean {
        val draftChanged = draft != CatalogFilters()
        if (local) return draftChanged
        return draftChanged ||
            pluginDraft != pluginDefaults ||
            applied.pluginValues.isNotEmpty()
    }
}


// The ONE search + filters sheet: query
// field on top, recent searches under it (only while the field is empty),
// then sections. A local catalogue keeps the fixed sort + file-format chips;
// a plugin source renders ONLY the filters its getFilterList declares (sort,
// select, checkbox, tristate, switch, text, tag_input) with loading / error /
// empty states. Filters apply immediately; the query applies on IME Search,
// not on each keystroke. Close only dismisses; there is no Apply.
@Composable
fun CatalogFilterSheet(
    applied: CatalogFilters,
    genres: List<String>,
    history: List<String>,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    presets: List<FilterPreset>,
    onSavePreset: (FilterPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onApply: (CatalogFilters) -> Unit,
    onClose: () -> Unit,
    // Local catalogues have no plugin schema: the same chips area offers file
    // formats instead, and the status section is hidden.
    local: Boolean = false,
    // Plugin-declared `search_filters` capability. false = query+filters is
    // the plain search path, and the sheet says so (neutral hint).
    searchFiltersSupported: Boolean = false,
    // Plugin-declared source language: `zh` gets a "search in Chinese" hint.
    language: String? = null,
    // Plugin-declared catalogue filters for THIS source (empty until loaded).
    schema: List<PluginFilter> = emptyList(),
    schemaLoading: Boolean = false,
    schemaError: String? = null,
    onRetrySchema: () -> Unit = {},
    // Optional per-filter text history (NoveLA text_history); empty = no hints.
    textHistory: Map<String, List<String>> = emptyMap(),
) {
    var draft by remember { mutableStateOf(applied) }
    // Plugin filters live in their own typed draft; re-seeded when the schema
    // arrives or changes, never on every query keystroke.
    var pluginDraft by remember(schema) { mutableStateOf(applied.toPluginFilterDraft(schema)) }
    val pluginDefaults = remember(schema) { pluginFilterDefaults(schema) }
    val currentFilters = remember(local, draft, pluginDraft, schema) {
        if (local) draft else draft.withPluginDraft(schema, pluginDraft)
    }
    val extra = LocalMiruExtraColors.current
    val presetSnapshot = currentFilters.copy(query = "")
    var savingPreset by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    val activePreset = remember(presets, presetSnapshot) {
        presets.firstOrNull { it.filters == presetSnapshot }
    }
    val canReset = CatalogFilterEmission.canReset(
        applied = applied,
        draft = draft,
        local = local,
        pluginDraft = pluginDraft,
        pluginDefaults = pluginDefaults,
    )
    // Draft-only until Apply: opening the sheet or seeding the schema never
    // fires a catalogue request, and every control change stays local (FR-035).
    AppBottomSheet(
        title = stringResource(R.string.finder_sheet_title),
        description = stringResource(
            if (local) R.string.finder_sheet_desc_local else R.string.finder_sheet_desc_plugin,
        ),
        onClose = onClose,
        doneLabel = stringResource(R.string.finder_apply),
        onDone = {
            onApply(currentFilters)
            onClose()
        },
        headerAction = {
            // Reset only edits the draft; it does not apply until Apply.
            MiruButton(
                onClick = {
                    draft = CatalogFilters()
                    if (!local) pluginDraft = pluginDefaults
                },
                style = MiruButtonStyle.Secondary,
                enabled = canReset,
            ) {
                Text(text = stringResource(R.string.finder_reset))
            }
        },
    ) {
        OutlinedTextField(
            value = draft.query,
            onValueChange = { draft = draft.copy(query = it) },
            placeholder = {
                Text(
                    text = stringResource(R.string.finder_query_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingIcon = {
                if (draft.query.isNotEmpty()) {
                    IconButton(onClick = { draft = draft.copy(query = "") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.finder_cd_clear_search),
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    // Search is an explicit apply of the current draft.
                    onApply(currentFilters)
                    onClose()
                },
            ),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )

        // Neutral hint: the source can only search, so the visible filters are
        // not part of the search request. Never "filters do not work"; shown
        // for filters-only too, so the fallback is not a surprise.
        if (!local && !searchFiltersSupported && schema.isNotEmpty()) {
            Text(
                text = stringResource(R.string.finder_filters_search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(top = MiruSpace.Xs),
            )
        }
        // 69shuba searches GBK: tell the user to type Chinese. No auto-translate.
        if (shouldShowSearchLanguageHint(language, draft.query)) {
            Text(
                text = stringResource(R.string.finder_search_language_hint_zh),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(top = MiruSpace.Xs),
            )
        }
        if (presets.isNotEmpty()) {
            PresetRail(
                presets = presets,
                activePreset = activePreset,
                onApplyPreset = { preset ->
                    draft = preset.filters.copy(query = draft.query)
                    if (!local) {
                        pluginDraft = preset.filters.toPluginFilterDraft(schema)
                    }
                },
                onDeletePreset = { name -> onDeletePreset(name) },
                onStartSave = { savingPreset = true },
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.M),
            ) {
                Text(
                    text = stringResource(R.string.finder_presets_title).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { savingPreset = true }) {
                    Text(
                        text = stringResource(R.string.finder_preset_save),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        if (savingPreset) {
            val clean = presetName.trim()
            val exists = presets.any { it.name.equals(clean, ignoreCase = true) }
            val full = presets.size >= FilterPreset.Max && !exists
            val nothingToSave = presetSnapshot == CatalogFilters()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.S),
            ) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(32) },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.finder_preset_name_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (clean.isNotEmpty() && !full && !nothingToSave) {
                            onSavePreset(FilterPreset(clean, presetSnapshot))
                            presetName = ""
                            savingPreset = false
                        }
                    },
                    enabled = clean.isNotEmpty() && !full && !nothingToSave,
                ) {
                    Text(
                        text = stringResource(
                            if (exists) R.string.finder_preset_overwrite else R.string.finder_preset_save,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                TextButton(onClick = { savingPreset = false }) {
                    Text(
                        text = stringResource(R.string.lib_cancel),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (full) {
                Text(
                    text = stringResource(R.string.finder_preset_limit_fmt, FilterPreset.Max),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(top = MiruSpace.Xs),
                )
            }
        }

        if (draft.query.isEmpty() && history.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.M),
            ) {
                Text(
                    text = stringResource(R.string.finder_history_title).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearHistory) {
                    Text(
                        text = stringResource(R.string.finder_history_clear),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            history.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { draft = draft.copy(query = entry) }
                        .defaultMinSize(minHeight = MiruSize.Touch),
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = extra.muted,
                        modifier = Modifier.size(MiruSpace.L),
                    )
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onRemoveHistory(entry) },
                        modifier = Modifier.size(MiruSize.Touch),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.finder_history_remove_cd),
                            tint = extra.muted,
                            modifier = Modifier.size(MiruSpace.L),
                        )
                    }
                }
            }
        }

        if (local) {
            LocalFilterSections(
                draft = draft,
                genres = genres,
                onDraftChange = {
                    draft = it
                },
            )
        } else {
            when {
                schemaLoading -> FilterSchemaLoading()
                schemaError != null -> FilterSchemaError(
                    message = schemaError,
                    onRetry = onRetrySchema,
                )
                schema.isEmpty() -> Text(
                    text = stringResource(R.string.finder_filter_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    modifier = Modifier.padding(top = MiruSpace.L),
                )
                else -> schema.forEach { filter ->
                    val value = pluginDraft[filter.key] ?: return@forEach
                    PluginFilterSection(
                        filter = filter,
                        value = value,
                        textHistory = textHistory[filter.key].orEmpty(),
                        onChange = { updated ->
                            pluginDraft = pluginDraft + (filter.key to updated)
                        },
                    )
                }
            }
        }

    }
}


@Composable
private fun PresetRail(
    presets: List<FilterPreset>,
    activePreset: FilterPreset?,
    onApplyPreset: (FilterPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onStartSave: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.M),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.finder_presets_title).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = extra.muted,
                modifier = Modifier.weight(1f),
            )
            if (activePreset != null) {
                IconButton(
                    onClick = { onDeletePreset(activePreset.name) },
                    modifier = Modifier.size(MiruSize.Touch),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.finder_preset_delete_cd),
                        tint = extra.muted,
                        modifier = Modifier.size(MiruSpace.L),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            presets.forEach { preset ->
                MiruFilterChip(
                    selected = activePreset?.name == preset.name,
                    onClick = { onApplyPreset(preset) },
                    label = preset.name,
                )
            }
            MiruFilterChip(
                selected = false,
                onClick = onStartSave,
                label = stringResource(R.string.finder_preset_save),
                leadingIcon = Icons.Filled.Add,
            )
        }
    }
}

// Local catalogues keep the pre-schema behaviour: fixed sort plus real file
// formats, no library genres and no synthetic status.
@Composable
private fun LocalFilterSections(
    draft: CatalogFilters,
    genres: List<String>,
    onDraftChange: (CatalogFilters) -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    // Sort is ALWAYS the first section: radio rows, the selected one carries
    // the direction arrow, a second tap flips the direction.
    SheetSection(title = stringResource(R.string.finder_sort_title)) {
        CatalogSort.entries.forEach { sort ->
            val selected = draft.sort == sort
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) {
                        onDraftChange(
                            if (selected) {
                                draft.copy(descending = !draft.descending)
                            } else {
                                // Title reads A-Z by default, the rest newest/best first.
                                draft.copy(sort = sort, descending = sort != CatalogSort.Title)
                            },
                        )
                    }
                    .defaultMinSize(minHeight = MiruSize.Touch),
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    text = sortLabel(sort),
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
                        modifier = Modifier.size(MiruSpace.L),
                    )
                }
            }
        }
    }

    // Options are always alphabetical; long lists get their own search.
    SheetSection(title = stringResource(R.string.finder_local_format_title)) {
        FilterChipGroup(
            options = genres,
            selected = draft.genres,
            onToggle = { genre ->
                onDraftChange(
                    draft.copy(
                        genres = if (genre in draft.genres) {
                            draft.genres - genre
                        } else {
                            draft.genres + genre
                        },
                    ),
                )
            },
        )
        Text(
            text = stringResource(R.string.finder_local_filter_note),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.S),
        )
    }
}

@Composable
private fun FilterSchemaLoading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.L)
            .defaultMinSize(minHeight = MiruSize.Touch),
    ) {
        CircularProgressIndicator(
            strokeWidth = MiruSpace.Xxs,
            color = LocalMiruExtraColors.current.accent,
            modifier = Modifier.size(MiruSpace.Xl),
        )
        Text(
            text = stringResource(R.string.finder_filter_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMiruExtraColors.current.muted,
        )
    }
}

@Composable
private fun FilterSchemaError(message: String, onRetry: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.L)
            .defaultMinSize(minHeight = MiruSize.Touch),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.finder_filter_error),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MiruSpace.Xs),
            )
        }
        Spacer(modifier = Modifier.width(MiruSpace.S))
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.finder_filter_retry),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun sortLabel(sort: CatalogSort): String = stringResource(
    when (sort) {
        CatalogSort.Popular -> R.string.finder_sort_popular
        CatalogSort.Latest -> R.string.finder_sort_latest
        CatalogSort.Rating -> R.string.finder_sort_rating
        CatalogSort.Title -> R.string.finder_sort_title_option
    },
)
