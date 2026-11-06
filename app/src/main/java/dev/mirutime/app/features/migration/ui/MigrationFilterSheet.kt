@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.migration.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import dev.mirutime.app.R
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.migration.domain.MigrationSourceFilter
import dev.mirutime.app.features.migration.domain.filterMigrationSources
import dev.mirutime.app.features.migration.domain.migrationSelectAllSources
import dev.mirutime.app.features.migration.domain.migrationSelectedSourceIds
import dev.mirutime.app.features.migration.domain.migrationSourceLanguages
import dev.mirutime.data.library.migration.BookMigrationOptions
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SheetHeaderAction
import dev.mirutime.uikit.SheetSection

/**
 * The ONE migration options sheet. It owns both halves of "what will run":
 * which target sources are on (with search/language/type narrowing) and what
 * the transfer moves. The screen outside the sheet only shows a compact
 * summary, never the full source wall or the settings block.
 */
@Composable
fun MigrationFilterSheet(
    sources: List<CatalogSource>,
    selected: Set<String>,
    options: BookMigrationOptions,
    onApply: (Set<String>, BookMigrationOptions) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var languages by remember { mutableStateOf(emptySet<String>()) }
    var types by remember { mutableStateOf(emptySet<ContentType>()) }
    var draftSelected by remember { mutableStateOf(migrationSelectedSourceIds(selected, sources)) }
    var draftOptions by remember { mutableStateOf(options) }

    val availableLanguages = remember(sources) { migrationSourceLanguages(sources) }
    val availableTypes = remember(sources) { sources.map { it.type }.distinct() }
    val filtered = filterMigrationSources(
        sources = sources,
        filter = MigrationSourceFilter(
            selected = draftSelected,
            query = query,
            languages = languages,
            types = types,
        ),
    )
    val selectedSources = sources.filter { it.id in draftSelected }

    // Draft-only until Apply (FR-035): source selection + transfer options are
    // a draft; the parent only sees the committed set on Apply, Close discards.
    // The search/language/type fields filter the local source list only.
    AppBottomSheet(
        title = stringResource(R.string.migration_filter_title),
        description = stringResource(R.string.migration_filter_desc),
        onClose = onClose,
        headerAction = {
            SheetHeaderAction(
                label = stringResource(R.string.migration_filter_clear),
                enabled = draftSelected.isNotEmpty(),
                onClick = { draftSelected = emptySet() },
            )
        },
        doneLabel = stringResource(R.string.finder_apply),
        onDone = {
            onApply(migrationSelectedSourceIds(draftSelected, sources), draftOptions)
            onClose()
        },
        // 55% body keeps the visible surface (handle + margins + 200% footer)
        // at/under 70%; content still scrolls.
        maxHeightFraction = 0.55f,
    ) {
        MiruSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.migration_filter_search),
            onClear = { query = "" },
        )

        SheetSection(
            title = stringResource(R.string.migration_filter_sources_section),
        ) {
            // Count and both bulk actions get their own lines at 200%: a
            // single Row clipped "Clear" against the sheet edge.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.Xs),
            ) {
                Text(
                    text = stringResource(
                        R.string.migration_filter_selected_fmt,
                        draftSelected.size,
                        sources.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    TextButton(
                        onClick = {
                            draftSelected = draftSelected + migrationSelectAllSources(filtered)
                        },
                        enabled = filtered.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.migration_filter_select_all))
                    }
                }
            }

            if (selectedSources.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    modifier = Modifier.padding(bottom = MiruSpace.S),
                ) {
                    selectedSources.forEach { source ->
                        MiruFilterChip(
                            selected = true,
                            onClick = { draftSelected = draftSelected - source.id },
                            label = source.name,
                        )
                    }
                }
            }

            MigrationFilterLabel(stringResource(R.string.migration_filter_language))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
            ) {
                MiruFilterChip(
                    selected = languages.isEmpty(),
                    onClick = { languages = emptySet() },
                    label = stringResource(R.string.lib_all),
                )
                availableLanguages.forEach { language ->
                    MiruFilterChip(
                        selected = language in languages,
                        onClick = {
                            languages = if (language in languages) {
                                languages - language
                            } else {
                                languages + language
                            }
                        },
                        label = language,
                    )
                }
            }

            if (availableTypes.size > 1) {
                MigrationFilterLabel(stringResource(R.string.migration_filter_type))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    MiruFilterChip(
                        selected = types.isEmpty(),
                        onClick = { types = emptySet() },
                        label = stringResource(R.string.lib_all),
                    )
                    availableTypes.forEach { type ->
                        MiruFilterChip(
                            selected = type in types,
                            onClick = {
                                types = if (type in types) types - type else types + type
                            },
                            label = contentTypeLabel(type),
                        )
                    }
                }
            }

            when {
                sources.isEmpty() -> MigrationEmptyState(
                    stringResource(R.string.migration_no_sources),
                )
                filtered.isEmpty() -> MigrationEmptyState(
                    stringResource(R.string.migration_filter_empty),
                )
                else -> filtered.forEachIndexed { index, source ->
                    if (index > 0) MiruRowDivider()
                    MigrationSourceRow(
                        source = source,
                        selected = source.id in draftSelected,
                        onToggle = {
                            draftSelected = if (source.id in draftSelected) {
                                draftSelected - source.id
                            } else {
                                draftSelected + source.id
                            }
                        },
                    )
                }
            }
        }

        SheetSection(
            title = stringResource(R.string.migration_options_title),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.migration_option_progress),
                subtitle = stringResource(R.string.migration_option_progress_desc),
                checked = draftOptions.transferProgress,
                onChange = { draftOptions = draftOptions.copy(transferProgress = it) },
                horizontalPadding = MiruSpace.Xs,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.migration_option_body),
                subtitle = stringResource(R.string.migration_option_body_desc),
                checked = draftOptions.transferBodies,
                onChange = { draftOptions = draftOptions.copy(transferBodies = it) },
                horizontalPadding = MiruSpace.Xs,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.migration_option_translations),
                subtitle = stringResource(R.string.migration_option_translations_desc),
                checked = draftOptions.transferTranslations,
                onChange = { draftOptions = draftOptions.copy(transferTranslations = it) },
                horizontalPadding = MiruSpace.Xs,
            )
            MiruRowDivider()
            SettingsSwitchRow(
                title = stringResource(R.string.migration_option_delete_old),
                subtitle = stringResource(
                    if (draftOptions.deleteOldBook) {
                        R.string.migration_delete_old_hint
                    } else {
                        R.string.migration_keep_old_hint
                    },
                ),
                checked = draftOptions.deleteOldBook,
                onChange = { draftOptions = draftOptions.copy(deleteOldBook = it) },
                horizontalPadding = MiruSpace.Xs,
            )
        }
    }
}

@Composable
private fun MigrationFilterLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalMiruExtraColors.current.muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.M, bottom = MiruSpace.Xs),
    )
}
