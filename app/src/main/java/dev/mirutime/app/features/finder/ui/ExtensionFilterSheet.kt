@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.finder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.finder.domain.ExtensionFilters
import dev.mirutime.app.features.finder.domain.ExtensionStatus
import dev.mirutime.app.features.library.domain.ContentType

// Manage-extensions filters in ONE bottom sheet. The query is edited inline
// on the search bar, so the sheet only carries type, language and — on the
// Installed tab only — status. Reset clears the filters but keeps the query.
@Composable
fun ExtensionFilterSheet(
    applied: ExtensionFilters,
    languages: List<String>,
    showStatus: Boolean,
    onApply: (ExtensionFilters) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(applied) }

    // Immediate filters: every chip applies to the list behind the sheet,
    // the sheet stays open, Close is the only footer action. The inline
    // search owns the query; never write a stale copy back.
    LaunchedEffect(draft) {
        onApply(draft.copy(query = applied.query))
    }

    AppBottomSheet(
        title = stringResource(R.string.finder_ext_sheet_title),
        description = stringResource(R.string.finder_ext_sheet_desc),
        onClose = onClose,
    ) {
        SheetSection(title = stringResource(R.string.finder_btn_type)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            ) {
                ContentType.entries.forEach { type ->
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
        SheetSection(title = stringResource(R.string.finder_btn_language)) {
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
        }
        if (showStatus) {
            SheetSection(title = stringResource(R.string.finder_status_title)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    ExtensionStatus.entries.forEach { status ->
                        MiruFilterChip(
                            selected = draft.status == status,
                            onClick = { draft = draft.copy(status = status) },
                            label = stringResource(
                                when (status) {
                                    ExtensionStatus.Any -> R.string.finder_filter_all
                                    ExtensionStatus.Enabled -> R.string.finder_ext_status_enabled
                                    ExtensionStatus.Disabled -> R.string.finder_ext_status_disabled
                                    ExtensionStatus.Updates -> R.string.finder_ext_status_updates
                                },
                            ),
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = {
                draft = draft.copy(
                    types = emptySet(),
                    languages = emptySet(),
                    status = ExtensionStatus.Any,
                )
            },
            enabled = draft.types.isNotEmpty() ||
                draft.languages.isNotEmpty() ||
                draft.status != ExtensionStatus.Any,
        ) {
            Text(
                text = stringResource(R.string.finder_reset),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
