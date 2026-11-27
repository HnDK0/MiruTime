package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.isLargeText
import dev.mirutime.app.design.components.BookTileWidth
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruContinueCard
import dev.mirutime.uikit.SectionHeader
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.SkeletonBox
import dev.mirutime.uikit.miruCardBorder
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
import dev.mirutime.app.features.translation.data.MetadataTranslationController
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.app.features.translation.ui.TitleItem
import dev.mirutime.app.features.translation.ui.rememberTranslatedTitles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlin.math.roundToInt

// Dashboard books are library books without the shelf/ranking metadata; the
// shared library card consumes this mapped shape.
private fun DashboardBook.toLibraryBook(): LibraryBook = LibraryBook(
    bookUrl = bookUrl,
    title = title,
    translatedTitle = translatedTitle,
    source = source,
    installId = installId,
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

// Live title when the current config still matches, otherwise the provider's
// persisted value (already on the row) stays.
private fun DashboardBook.withTitle(titles: Map<String, String>): DashboardBook =
    titles[bookKey]?.let { copy(translatedTitle = it) } ?: this

private data class DashboardState(
    val stats: ReadingStats? = null,
    val continueBook: DashboardBook? = null,
    val updates: List<UpdateItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val failed: Boolean = false,
)

// The floating MiruShell tab panel (photo themes) overlays the scroll —
// content must end above its icons, so the bottom padding carries the
// panel height on top of the shell padding.
private val BottomFloatClearance = 48.dp

// Dashboard order: time stats, continue reading, history, updates.
@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    provider: DashboardDataProvider,
    metadataTranslations: MetadataTranslationController,
    refreshKey: Int = 0,
    onBookOpen: (String) -> Unit = {},
    onUpdatesOpen: () -> Unit = {},
    onHistoryOpen: () -> Unit = {},
) {
    var state by remember { mutableStateOf(DashboardState()) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(provider, refreshKey, retryTick) {
        state = DashboardState()
        try {
            // Concurrent, one commit: sequential loads popped the screen in parts.
            supervisorScope {
                val statsDeferred = async { provider.readingStats() }
                val continueDeferred = async { provider.continueReading() }
                val updatesDeferred = async { provider.updates() }
                val historyDeferred = async { provider.history(6) }
                state = DashboardState(
                    stats = statsDeferred.await(),
                    continueBook = continueDeferred.await(),
                    updates = updatesDeferred.await(),
                    history = historyDeferred.await(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            state = DashboardState(failed = true)
        }
    }

    // FULL-mode titles: the live lane warms the rails; the provider's persisted
    // overlay stays the first-paint fallback.
    val libraryTitles = rememberTranslatedTitles(
        controller = metadataTranslations,
        items = remember(state.continueBook, state.updates) {
            (listOfNotNull(state.continueBook) + state.updates.map { it.book })
                .map { TitleItem(it.bookKey, it.title) }
        },
        surface = TitleSurface.Library,
    )
    val historyTitles = rememberTranslatedTitles(
        controller = metadataTranslations,
        items = remember(state.history) {
            state.history.map { TitleItem(it.book.bookKey, it.book.title) }
        },
        surface = TitleSurface.History,
    )
    val display = remember(state, libraryTitles, historyTitles) {
        state.copy(
            continueBook = state.continueBook?.withTitle(libraryTitles),
            updates = state.updates.map { it.copy(book = it.book.withTitle(libraryTitles)) },
            history = state.history.map { it.copy(book = it.book.withTitle(historyTitles)) },
        )
    }

    if (state.failed) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MiruSize.ScreenPad,
                end = MiruSize.ScreenPad,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + BottomFloatClearance,
            ),
        ) {
            item {
                MiruCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.dash_load_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(MiruSpace.M))
                    MiruButton(
                        onClick = { retryTick++ },
                        style = MiruButtonStyle.Primary,
                    ) {
                        Text(text = stringResource(R.string.dash_retry))
                    }
                }
            }
        }
        return
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
        item(key = "today") {
            MiruCard(
                contentPadding = PaddingValues(MiruSpace.M),
            ) {
                TimeStatsPanel(stats = display.stats!!)
            }
        }

        display.continueBook?.let { continueBook ->
            item(key = "continue") {
                val lastSession = remember(display.history, continueBook.bookKey) {
                    display.history.firstOrNull { it.book.bookKey == continueBook.bookKey }
                }
                ContinueCard(
                    book = continueBook,
                    lastSession = lastSession,
                    onOpenBook = onBookOpen,
                )
            }
        }

        item(key = "history") {
            if (display.history.isEmpty()) {
                MiruCard(
                    contentPadding = PaddingValues(MiruSpace.M),
                ) {
                    Text(
                        text = stringResource(R.string.dash_history),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(MiruSpace.Xxs))
                    Text(
                        text = stringResource(R.string.dash_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs)) {
                    SectionHeader(
                        title = stringResource(R.string.dash_history),
                        compact = true,
                        actionLabel = stringResource(R.string.dash_show_history),
                        onAction = onHistoryOpen,
                    )
                    HistoryRail(
                        history = display.history,
                        onOpenBook = onBookOpen,
                    )
                }
            }
        }

        item(key = "updates") {
            if (display.updates.isEmpty()) {
                MiruCard(
                    contentPadding = PaddingValues(
                        horizontal = MiruSpace.M,
                        vertical = MiruSpace.S,
                    ),
                ) {
                    if (isLargeText()) {
                        Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs)) {
                            UpdatesEmptyCopy()
                            MiruButton(
                                onClick = onUpdatesOpen,
                                style = MiruButtonStyle.Text,
                            ) {
                                Text(text = stringResource(R.string.dash_updates_history))
                            }
                        }
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val maxActionWidth = maxWidth / 2
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UpdatesEmptyCopy(modifier = Modifier.weight(1f))
                                MiruButton(
                                    onClick = onUpdatesOpen,
                                    style = MiruButtonStyle.Text,
                                    modifier = Modifier.widthIn(max = maxActionWidth),
                                ) {
                                    Text(text = stringResource(R.string.dash_updates_history))
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs)) {
                    SectionHeader(
                        title = stringResource(R.string.dash_updates),
                        compact = true,
                        actionLabel = stringResource(R.string.dash_updates_history),
                        onAction = onUpdatesOpen,
                    )
                    UpdatesRail(
                        updates = display.updates,
                        onOpenBook = onBookOpen,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesEmptyCopy(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.dash_updates),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.dash_up_to_date),
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
        )
    }
}

// Dashboard adapter for the shared resume block (same card as the library):
// ratio · saved chapter · source · author · last-read label, with the cover
// driving the card height.
@Composable
private fun ContinueCard(
    book: DashboardBook,
    lastSession: HistoryEntry?,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = book.translatedTitle ?: book.title
    val total = book.totalChapters.coerceAtLeast(0)
    val read = book.readChapters.coerceIn(0, total)
    val fraction = if (total > 0) read.toFloat() / total else 0f
    val sessionChapter = lastSession?.chapter?.takeIf { it > 0 }
    val meta = listOfNotNull(
        stringResource(R.string.card_ratio_fmt, read, total).takeIf { total > 0 },
        sessionChapter?.let { stringResource(R.string.card_next_ch_fmt, it) },
        book.sourceLabel.trim().takeIf { it.isNotBlank() },
        book.author.trim().takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) },
        lastSession?.readLabel?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { stringResource(R.string.book_no_chapters) }
    MiruContinueCard(
        title = title,
        meta = meta,
        progressFraction = fraction,
        progressLabel = stringResource(
            R.string.lib_resume_complete_fmt,
            (fraction * 100).roundToInt(),
        ),
        continueDescription = sessionChapter
            ?.let { stringResource(R.string.book_continue_fmt, it) }
            ?: stringResource(R.string.dash_open),
        onClick = { onOpenBook(book.bookKey) },
        modifier = modifier,
        cover = {
            BookCover(
                title = title,
                coverUrl = book.coverUrl,
                hasUpdateDot = book.hasNewChapters,
                modifier = Modifier.fillMaxSize(),
                corner = MiruRadius.Cover,
            )
        },
    )
}

// Loading skeleton in the final geometry: stat panel + a rail of tiles +
// update rows, so first paint never jumps when the single data commit lands.
@Composable
private fun DashboardSkeleton() {
    Column(modifier = Modifier.fillMaxWidth()) {
        SkeletonBar(
            modifier = Modifier.fillMaxWidth(),
            height = 144.dp,
            corner = MiruRadius.Card,
        )
        Spacer(modifier = Modifier.height(MiruSpace.M))
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
            repeat(2) {
                SkeletonBox(width = BookTileWidth, height = 272.dp, corner = MiruRadius.Card)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = miruCardSurface(),
            border = miruCardBorder(),
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
            key = { "update:${it.book.bookKey}" },
        ) { update ->
            LibraryCard(
                book = update.book.toLibraryBook().copy(newChapters = update.newChapters),
                onOpen = { onOpenBook(update.book.bookKey) },
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
            key = { index, entry -> "history:${entry.book.bookKey}:$index" },
        ) { _, entry ->
            val book = entry.book
            LibraryCard(
                book = book.toLibraryBook(),
                onOpen = { onOpenBook(book.bookKey) },
                modifier = Modifier.width(railWidth),
                style = railStyle,
            )
        }
    }
}
