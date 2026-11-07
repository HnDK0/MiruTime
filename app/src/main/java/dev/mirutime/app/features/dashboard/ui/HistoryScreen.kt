package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.features.dashboard.data.DashboardDataProvider
import dev.mirutime.app.features.dashboard.domain.HistoryEntry
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruRadius
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
    onBookOpen: (String) -> Unit = {},
) {
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    // Skeleton rows until fullHistory() returns; the empty history card must
    // not flash before the first Room read.
    var loaded by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var request by remember { mutableStateOf<HistoryDeleteRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
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
        if (history.isEmpty()) {
            selectionMode = false
            selected = emptySet()
        } else {
            val urls = history.map { it.book.bookKey }.toSet()
            if (selected.any { it !in urls }) selected = selected.intersect(urls)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                // The selection bar floats over the list: reserve its height so
                // the last row stays reachable while selecting.
                bottom = contentPadding.calculateBottomPadding() +
                    if (selectionMode) 96.dp else 24.dp,
            ),
        ) {
            when {
                !loaded -> items(6) {
                    HistoryRowSkeleton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
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
                    if (selectionMode) {
                        item(key = "history-selection-toolbar") {
                            HistorySelectionToolbar(
                                onSelectAll = { selected = history.map { it.book.bookKey }.toSet() },
                                onClear = { selected = emptySet() },
                                onExit = {
                                    selectionMode = false
                                    selected = emptySet()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = MiruSpace.S),
                            )
                        }
                    }
                    itemsIndexed(
                        history,
                        key = { index, entry -> "history:${entry.book.bookKey}:$index" },
                    ) { _, entry ->
                        val url = entry.book.bookKey
                        MiruCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            HistoryRow(
                                entry = entry,
                                selectionMode = selectionMode,
                                selected = url in selected,
                                onOpen = {
                                    if (selectionMode) {
                                        selected = if (url in selected) {
                                            selected - url
                                        } else {
                                            selected + url
                                        }
                                    } else {
                                        onBookOpen(url)
                                    }
                                },
                                onLongPress = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selected = selected + url
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The surface paints behind the gesture bar, the buttons
                        // sit above it: the bar must not be clipped at the edge.
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = MiruSpace.S),
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiruButton(
                        onClick = { request = HistoryDeleteRequest.Selected(selected.size) },
                        style = MiruButtonStyle.Destructive,
                        enabled = selected.isNotEmpty() && !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.lib_bulk_delete))
                    }
                    MiruButton(
                        onClick = { request = HistoryDeleteRequest.All },
                        style = MiruButtonStyle.Secondary,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dash_history_clear_all))
                    }
                }
            }
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
                        selectionMode = false
                        selected = emptySet()
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
private fun HistorySelectionToolbar(
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val toolbarColors = ButtonDefaults.textButtonColors(
            contentColor = LocalMiruExtraColors.current.accent,
        )
        val toolbarPadding = PaddingValues(horizontal = MiruSpace.S, vertical = MiruSpace.Xs)
        TextButton(onClick = onExit, colors = toolbarColors, contentPadding = toolbarPadding) {
            Text(stringResource(R.string.lib_selection_close))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onSelectAll, colors = toolbarColors, contentPadding = toolbarPadding) {
            Text(stringResource(R.string.lib_select_all))
        }
        TextButton(onClick = onClear, colors = toolbarColors, contentPadding = toolbarPadding) {
            Text(stringResource(R.string.lib_clear_selection))
        }
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
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.dash_history_empty),
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
        )
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
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .defaultMinSize(minHeight = 72.dp)
            .padding(start = CoverInset, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onOpen() },
            )
            Spacer(modifier = Modifier.width(MiruSpace.Xs))
        }
        BookCover(
            title = entry.book.translatedTitle ?: entry.book.title,
            coverUrl = entry.book.coverUrl,
            modifier = Modifier
                .width(48.dp)
                .height(72.dp)
                .padding(CoverInset),
            corner = MiruRadius.Cover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = entry.book.translatedTitle ?: entry.book.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
