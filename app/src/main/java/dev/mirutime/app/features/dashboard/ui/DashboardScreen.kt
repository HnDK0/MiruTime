package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.app.design.components.BookTileWidth
import dev.mirutime.app.design.components.ReplayIcon
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.SectionHeader
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.SkeletonBox
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.app.features.dashboard.data.DashboardDataProvider
import dev.mirutime.app.features.dashboard.domain.DashboardBook
import dev.mirutime.app.features.dashboard.domain.HistoryEntry
import dev.mirutime.app.features.dashboard.domain.ReadingStats
import dev.mirutime.app.features.dashboard.domain.UpdateItem
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.ui.LibraryCard
import kotlinx.coroutines.async

// Dashboard books are library books without the shelf/ranking metadata; the
// shared library card consumes this mapped shape.
private fun DashboardBook.toLibraryBook(): LibraryBook = LibraryBook(
    bookUrl = bookUrl,
    title = title,
    translatedTitle = translatedTitle,
    source = source,
    sourceLabel = sourceLabel,
    author = author,
    rating = rating,
    contentType = contentType,
    totalChapters = totalChapters,
    readChapters = readChapters,
    hasUpdate = hasNewChapters,
    shelfId = "reading",
    genres = emptyList(),
    lastReadRank = 0,
    lastUpdateRank = 0,
    addedRank = 0,
    lastReadLabel = updatedLabel,
    continueLabel = "",
    coverUrl = coverUrl,
)

private data class DashboardState(
    val stats: ReadingStats? = null,
    val updates: List<UpdateItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
)

// The floating MiruShell tab panel (photo themes) overlays the scroll —
// content must end above its icons, so the bottom padding carries the
// panel height on top of the shell padding.
private val BottomFloatClearance = 48.dp

// Dashboard order: time stats, history, updates.
@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    provider: DashboardDataProvider,
    refreshKey: Int = 0,
    onBookOpen: (String) -> Unit = {},
    onUpdatesOpen: () -> Unit = {},
    onHistoryOpen: () -> Unit = {},
) {
    var state by remember { mutableStateOf(DashboardState()) }

    LaunchedEffect(provider, refreshKey) {
        state = DashboardState()
        // Concurrent, one commit: sequential loads popped the screen in parts.
        val statsDeferred = async { provider.readingStats() }
        val updatesDeferred = async { provider.updates() }
        val historyDeferred = async { provider.history(6) }
        state = DashboardState(
            statsDeferred.await(),
            updatesDeferred.await(),
            historyDeferred.await(),
        )
    }

    if (state.stats == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MiruSize.ScreenPad,
                end = MiruSize.ScreenPad,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + BottomFloatClearance,
            ),
        ) {
            item { DashboardSkeleton() }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
        contentPadding = PaddingValues(
            start = MiruSize.ScreenPad,
            end = MiruSize.ScreenPad,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + BottomFloatClearance,
        ),
    ) {
        item {
            SectionHeader(
                title = stringResource(R.string.dash_today),
                description = stringResource(R.string.dash_today_desc),
            )
        }
        item {
            MiruCard {
                TimeStatsPanel(stats = state.stats!!)
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.dash_history),
                description = stringResource(R.string.dash_history_desc),
                actionLabel = stringResource(R.string.dash_show_history),
                onAction = onHistoryOpen,
            )
        }
        item {
            if (state.history.isEmpty()) {
                MiruCard {
                    Text(
                        text = stringResource(R.string.dash_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
            } else {
                HistoryRail(
                    history = state.history,
                    onOpenBook = onBookOpen,
                )
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.dash_updates),
                description = stringResource(R.string.dash_updates_desc),
                actionLabel = stringResource(R.string.dash_updates_history),
                onAction = onUpdatesOpen,
            )
        }
        item {
            if (state.updates.isEmpty()) {
                MiruCard {
                    Text(
                        text = stringResource(R.string.dash_up_to_date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
            } else {
                UpdatesRail(
                    updates = state.updates,
                    onOpenBook = onBookOpen,
                )
            }
        }

    }
}

// Loading skeleton in the final geometry: stat panel + a rail of tiles +
// update rows, so first paint never jumps when the single data commit lands.
@Composable
private fun DashboardSkeleton() {
    Column(modifier = Modifier.fillMaxWidth()) {
        SkeletonBar(
            modifier = Modifier.fillMaxWidth(),
            height = 232.dp,
            corner = MiruRadius.Card,
        )
        Spacer(modifier = Modifier.height(MiruSpace.Xl))
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
            repeat(2) {
                SkeletonBox(width = BookTileWidth, height = 272.dp, corner = MiruRadius.Card)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = miruCardSurface(),
        ) {
            Column(modifier = Modifier.padding(horizontal = MiruSpace.L, vertical = MiruSpace.S)) {
                repeat(3) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MiruSpace.M),
                    ) {
                        SkeletonBox(width = 48.dp, height = 72.dp, corner = MiruRadius.Cover)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            SkeletonBar(modifier = Modifier.fillMaxWidth(0.7f))
                            Spacer(modifier = Modifier.height(8.dp))
                            SkeletonBar(modifier = Modifier.fillMaxWidth(0.5f))
                        }
                    }
                }
            }
        }
    }
}

// Thin dashboard progress bar: accent fill on an ink-tinted track (visible on
// any plate; the old surfaceVariant track melted into same-toned cards).
@Composable
internal fun DashBar(fraction: Float, modifier: Modifier = Modifier) {
    CardProgressBar(fraction = fraction, modifier = modifier, height = 4.dp)
}

// Updates rail: the same library card as History, with the accent +N label.
// Both Dashboard rails are one visual language and one 4dp gap.
@Composable
private fun UpdatesRail(
    updates: List<UpdateItem>,
    onOpenBook: (String) -> Unit,
) {
    if (updates.isEmpty()) return
    val railStyle = AppSettings.cardStyle
        .takeIf { it != CardStyle.Horizontal } ?: CardStyle.Mat
    val railScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val railWidth = MiruSize.Tile * railScale
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            updates,
            key = { "update:${it.book.bookUrl}" },
        ) { update ->
            LibraryCard(
                book = update.book.toLibraryBook().copy(newChapters = update.newChapters),
                onOpen = { onOpenBook(update.book.bookUrl) },
                modifier = Modifier.width(railWidth),
                style = railStyle,
            )
        }
    }
}

// History rail: the same library card as the library rails. The cover opens
// the book; the small play disc continues from the saved position (a finished
// book gets "replay" instead).
@Composable
private fun HistoryRail(
    history: List<HistoryEntry>,
    onOpenBook: (String) -> Unit,
) {
    val railStyle = AppSettings.cardStyle
        .takeIf { it != CardStyle.Horizontal } ?: CardStyle.Mat
    val railScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    val railWidth = MiruSize.Tile * railScale
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(
            history,
            key = { index, entry -> "history:${entry.book.bookUrl}:$index" },
        ) { _, entry ->
            val book = entry.book
            LibraryCard(
                book = book.toLibraryBook(),
                onOpen = { onOpenBook(book.bookUrl) },
                modifier = Modifier.width(railWidth),
                style = railStyle,
            )
        }
    }
}
