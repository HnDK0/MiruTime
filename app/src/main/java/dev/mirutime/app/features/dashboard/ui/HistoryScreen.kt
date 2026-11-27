package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.design.RangeSelection
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.design.logRangeSelect
import dev.mirutime.app.features.dashboard.data.DashboardDataProvider
import dev.mirutime.app.features.dashboard.domain.HistoryEntry
import dev.mirutime.app.features.translation.data.MetadataTranslationController
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.app.features.translation.ui.TitleItem
import dev.mirutime.app.features.translation.ui.rememberTranslatedTitles
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.uikit.MiruCardTitleText
import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruCompactButton
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSelectionPlate
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.SkeletonBox
import kotlinx.coroutines.launch

// What the confirmation sheet is about to remove.
private sealed interface HistoryDeleteRequest {
    data class Selected(val count: Int) : HistoryDeleteRequest
    data object All : HistoryDeleteRequest
}

// Dedicated History screen (drill-in from the dashboard's "Show all history").
// Long-press a row to enter selection, then delete one/selected/all. Deletion
// touches only history records: books, progress, read flags and downloads stay
// (FR-063/FR-064/FR-065/FR-066).
@Composable
fun HistoryScreen(
    contentPadding: PaddingValues,
    provider: DashboardDataProvider,
    metadataTranslations: MetadataTranslationController,
    onBookOpen: (String) -> Unit = {},
) {
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    // Skeleton rows until fullHistory() returns; the empty history card must
    // not flash before the first Room read.
    var loaded by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(RangeSelection<String>()) }
    val selectionMode = selection.active
    val selected = selection.selected
    var request by remember { mutableStateOf<HistoryDeleteRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Measured, never a constant: the plate grows with the font scale, and the
    // list must still scroll its last row clear of it.
    var selectionBarHeightPx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        history = provider.fullHistory()
    }

    LaunchedEffect(provider) {
        try {
            history = provider.fullHistory()
        } finally {
            loaded = true
        }
    }

    // Selection cannot outlive the rows it points at: clear deleted urls and
    // leave selection mode when the list is empty.
    LaunchedEffect(history) {
        selection = if (history.isEmpty()) {
            selection.close()
        } else {
            selection.prune(history.map { it.book.bookKey }.toSet())
        }
    }

    // FULL-mode titles: selection keeps the untranslated bookKey; only the
    // rendered title changes.
    val titles = rememberTranslatedTitles(
        controller = metadataTranslations,
        items = remember(history) {
            history.map { TitleItem(it.book.bookKey, it.book.title) }
        },
        surface = TitleSurface.History,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MiruSize.ScreenPad,
                end = MiruSize.ScreenPad,
                top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
                // The selection plate floats over the list: reserve its height so
                // the last row stays reachable while selecting.
                bottom = contentPadding.calculateBottomPadding() +
                    if (selectionMode) {
                        // The plate's own top gap is MiruSpace.S, so the
                        // reservation must match it: measured height excludes
                        // the plate's bottom padding.
                        with(LocalDensity.current) { selectionBarHeightPx.toDp() } +
                            MiruSpace.S * 2
                    } else {
                        24.dp
                    },
            ),
        ) {
            when {
                !loaded -> items(6) {
                    HistoryRowSkeleton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    )
                }

                history.isEmpty() -> item(key = "history-empty") {
                    HistoryEmptyState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                    )
                }

                else -> {
                    itemsIndexed(
                        history,
                        key = { index, entry -> "history:${entry.book.bookKey}:$index" },
                    ) { _, entry ->
                        val url = entry.book.bookKey
                        val shown = titles[url]?.let {
                            entry.copy(book = entry.book.copy(translatedTitle = it))
                        } ?: entry
                        MiruCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            HistoryRow(
                                entry = shown,
                                selectionMode = selectionMode,
                                selected = url in selected,
                                onOpen = {
                                    if (selectionMode) {
                                        selection = selection.toggle(url)
                                    } else {
                                        onBookOpen(url)
                                    }
                                },
                                onLongPress = {
                                    val next = selection.longPress(
                                        url,
                                        history.map { it.book.bookKey },
                                    )
                                    logRangeSelect("history", selection.count, next.count)
                                    selection = next
                                },
                            )
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            MiruSelectionPlate(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = MiruSize.ScreenPad,
                        end = MiruSize.ScreenPad,
                        bottom = contentPadding.calculateBottomPadding() + MiruSpace.S,
                    )
                    .onSizeChanged { selectionBarHeightPx = it.height },
                header = {
                    MiruIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.lib_selection_close),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconSize = MiruSize.IconCompact,
                        onClick = { selection = selection.close() },
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.plural_selected_books,
                            selected.size,
                            selected.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                },
                actions = {
                    // Header keeps only the count: at 200% the buttons next to
                    // it pushed the number into the ellipsis.
                    MiruCompactButton(
                        icon = Icons.Filled.Check,
                        label = stringResource(R.string.lib_select_all),
                        enabled = selected.size < history.size,
                        onClick = {
                            selection = selection.selectAll(history.map { it.book.bookKey })
                        },
                    )
                    MiruCompactButton(
                        icon = Icons.Filled.RemoveCircleOutline,
                        label = stringResource(R.string.lib_clear_selection),
                        enabled = selected.isNotEmpty(),
                        onClick = { selection = selection.clear(keepActive = true) },
                    )
                    MiruCompactButton(
                        icon = Icons.Filled.Delete,
                        label = stringResource(R.string.lib_bulk_delete),
                        ink = MaterialTheme.colorScheme.error,
                        enabled = selected.isNotEmpty() && !busy,
                        onClick = { request = HistoryDeleteRequest.Selected(selected.size) },
                    )
                    MiruCompactButton(
                        icon = Icons.Filled.DeleteSweep,
                        label = stringResource(R.string.dash_history_clear_all),
                        enabled = !busy,
                        onClick = { request = HistoryDeleteRequest.All },
                    )
                },
            )
        }
    }

    request?.let { pending ->
        HistoryDeleteSheet(
            request = pending,
            busy = busy,
            onDismiss = { if (!busy) request = null },
            onConfirm = {
                busy = true
                scope.launch {
                    try {
                        when (pending) {
                            is HistoryDeleteRequest.All -> provider.clearHistory()
                            is HistoryDeleteRequest.Selected ->
                                provider.deleteHistory(selected.toList())
                        }
                        request = null
                        selection = selection.close()
                        reload()
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

@Composable
private fun HistoryDeleteSheet(
    request: HistoryDeleteRequest,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title: String
    val description: String
    when (request) {
        is HistoryDeleteRequest.All -> {
            title = stringResource(R.string.dash_history_clear_title)
            description = stringResource(R.string.dash_history_clear_desc)
        }
        is HistoryDeleteRequest.Selected -> {
            title = stringResource(R.string.dash_history_delete_title)
            description = stringResource(R.string.dash_history_delete_desc_fmt, request.count)
        }
    }
    AppBottomSheet(
        title = title,
        description = description,
        onClose = onDismiss,
        doneLabel = stringResource(R.string.lib_bulk_delete),
        onDone = onConfirm,
        doneEnabled = !busy,
        doneBusy = busy,
        doneDanger = true,
    ) {
        Spacer(modifier = Modifier.height(MiruSpace.Xs))
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
    MiruCard(
        modifier = modifier,
        contentPadding = PaddingValues(MiruSpace.L),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dash_history_empty),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
    }
}

@Composable
private fun HistoryRowSkeleton(modifier: Modifier = Modifier) {
    MiruCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp)
                .padding(start = CoverInset, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBox(width = 48.dp, height = 72.dp, corner = MiruRadius.Cover)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.75f), height = 12.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.5f), height = 10.dp)
                Spacer(modifier = Modifier.height(10.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.65f), height = 10.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 4.dp)
            }
        }
    }
}

// Shared history row: 48x72 cover inside a 4dp frame, title (2 lines),
// source + read time, ratio + progress, accent "Open". Same family as
// UpdateRow; plate ink only. Long-press enters selection; a tap toggles.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryRow(
    entry: HistoryEntry,
    onOpen: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongPress: () -> Unit = {},
) {
    val accent = LocalMiruExtraColors.current.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiruRadius.Inner))
            .semantics(mergeDescendants = true) { if (selectionMode) semanticsSelected = selected }
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .defaultMinSize(minHeight = 72.dp)
            .padding(start = CoverInset, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
            )
            Spacer(modifier = Modifier.width(MiruSpace.Xs))
        }
        BookCover(
            title = entry.book.translatedTitle ?: entry.book.title,
            coverUrl = entry.book.coverUrl,
            modifier = Modifier
                .width(48.dp)
                .height(72.dp),
            corner = MiruRadius.Cover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            MiruCardTitleText(
                title = entry.book.translatedTitle ?: entry.book.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Text(
                text = "${entry.book.sourceLabel} · ${entry.readLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(MiruSpace.S))
            Text(
                text = "${stringResource(R.string.card_next_ch_fmt, entry.chapter)} · " +
                    stringResource(
                        R.string.card_ratio_fmt,
                        entry.book.readChapters,
                        entry.book.totalChapters,
                    ),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            CardProgressBar(
                fraction = entry.book.readChapters.toFloat() /
                    entry.book.totalChapters.coerceAtLeast(1),
            )
        }
        if (!selectionMode) {
            Text(
                text = stringResource(R.string.dash_open),
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
        }
    }
}
