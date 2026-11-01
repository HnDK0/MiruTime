@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.migration.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.design.components.CatalogTile
import dev.mirutime.app.design.components.SelectionBadge
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.design.components.toMiruBadge
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.ui.LibraryCard
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.app.features.migration.domain.MigrationHistoryGroup
import dev.mirutime.app.features.migration.domain.MigrationLibraryLayout
import dev.mirutime.app.features.migration.domain.MigrationSearchHit
import dev.mirutime.app.features.migration.domain.MigrationSearchResult
import dev.mirutime.app.features.migration.domain.MigrationTargetDetails
import dev.mirutime.app.features.migration.domain.groupMigrationHits
import dev.mirutime.app.features.migration.domain.migrationLibraryLayout
import dev.mirutime.app.features.migration.domain.migrationVerifiedChapterCount
import dev.mirutime.data.library.BookMigrationEntity
import dev.mirutime.data.library.migration.BookMigrationOptions
import dev.mirutime.data.library.migration.BookMigrationPlan
import dev.mirutime.data.library.migration.BookMigrationResult
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface

sealed interface MigrationSearchUi {
    data object Idle : MigrationSearchUi
    data object Searching : MigrationSearchUi
    data class Ready(val result: MigrationSearchResult) : MigrationSearchUi
    data class Failed(val message: String) : MigrationSearchUi
}

sealed interface MigrationRunUi {
    data object Idle : MigrationRunUi
    data class Running(val current: Int, val total: Int, val title: String) : MigrationRunUi
    data class Success(val result: BookMigrationResult) : MigrationRunUi
    data class Failure(val message: String) : MigrationRunUi
}

@Composable
fun MigrationSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalMiruExtraColors.current.muted,
        modifier = Modifier.padding(top = MiruSpace.L, bottom = MiruSpace.S),
    )
}

// === Book cards ===
// Two adapters over the ONE shared CatalogTile (global search / catalogue):
// migration hits and library candidates. Only the data mapping and the
// chapter label (real vs "—") differ; the tile itself is never re-implemented.
@Composable
fun MigrationCatalogTile(
    hit: MigrationSearchHit,
    selected: Boolean,
    chapterCount: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CatalogTile(
        book = hit.book,
        inLibrary = hit.book.inLibrary,
        modifier = modifier,
        selected = selected,
        chapterLabel = chapterCount?.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.migration_result_chapters, it, it)
        } ?: stringResource(R.string.migration_result_chapters_unknown),
        // Reserve the caption row for every result so the grid baseline is
        // even; membership is the bookmark, never text.
        caption = "",
        plain = true,
        selectionMode = true,
        membership = hit.book.badge?.toMiruBadge(),
        // A letter tile reads as a cover; a neutral mark says "no art yet".
        showInitial = false,
        placeholderIcon = Icons.Filled.MenuBook,
        onClick = onClick,
        onLongClick = onClick,
    )
}

fun MigrationCandidateBook.asLibraryBook(): LibraryBook = LibraryBook(
    bookUrl = bookUrl,
    title = title,
    source = sourceName,
    author = author,
    rating = rating,
    contentType = runCatching { ContentType.valueOf(contentType) }
        .getOrDefault(ContentType.Novel),
    totalChapters = chapterCount,
    readChapters = readChapters.coerceIn(0, chapterCount),
    hasUpdate = hasUpdate,
    shelfId = shelfId,
    genres = emptyList(),
    lastReadRank = 0,
    lastUpdateRank = 0,
    addedRank = 0,
    lastReadLabel = "",
    continueLabel = "",
    coverUrl = coverUrl,
)

// Migration library surface follows the Library screen exactly: list -> C
// full-width rows; grid -> A/B/D in gridColumns (2..6); Horizontal in grid
// falls back to full-width rows too. No CatalogTile on library candidates.
@Composable
fun MigrationLibraryCards(
    books: List<MigrationCandidateBook>,
    selectedUrls: Set<String>,
    selectionMode: Boolean,
    onBook: (MigrationCandidateBook) -> Unit,
) {
    // Reading the settings here makes both tabs re-layout the moment the
    // library filter sheet (or Settings -> Library) changes them.
    val fontScale = LocalDensity.current.fontScale
    val columns = if (fontScale >= 1.3f) 2 else AppSettings.gridColumns
    val layout: MigrationLibraryLayout = migrationLibraryLayout(
        listLayout = AppSettings.listLayout,
        gridColumns = columns,
        cardStyle = AppSettings.cardStyle,
    )
    if (layout.rows) {
        Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.CardGap)) {
            books.forEach { book ->
                MigrationBookCard(
                    book = book,
                    style = CardStyle.Horizontal,
                    selected = selectionMode && book.bookUrl in selectedUrls,
                    selectionMode = selectionMode,
                    onBook = onBook,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.CardGap)) {
            books.chunked(layout.columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
                ) {
                    row.forEach { book ->
                        MigrationBookCard(
                            book = book,
                            style = layout.style,
                            selected = selectionMode && book.bookUrl in selectedUrls,
                            selectionMode = selectionMode,
                            onBook = onBook,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(layout.columns - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// LibraryCard keeps its own selection overlay for the library's long-press
// mode. Migration uses the shared SelectionBadge instead so the checked state
// reads the same as source rows and result tiles.
@Composable
private fun MigrationBookCard(
    book: MigrationCandidateBook,
    style: CardStyle,
    selected: Boolean,
    selectionMode: Boolean,
    onBook: (MigrationCandidateBook) -> Unit,
    modifier: Modifier,
) {
    LibraryCard(
        book = book.asLibraryBook(),
        style = style,
        selected = selected,
        selectionMode = selectionMode,
        onOpen = { onBook(book) },
        onLongPress = { onBook(book) },
        modifier = modifier.fillMaxWidth(),
    )
}

// Catalogue hits (not library cards) keep CatalogTile, two per row: no
// horizontal carousel means no clipped third tile at any width.
@Composable
fun MigrationResultTiles(
    hits: List<MigrationSearchHit>,
    selectedBookUrl: String?,
    selectedChapterCount: Int?,
    onSelect: (MigrationSearchHit) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
        contentPadding = PaddingValues(vertical = MiruSpace.Xs),
    ) {
        items(hits, key = { it.book.bookUrl }) { hit ->
            MigrationCatalogTile(
                hit = hit,
                selected = hit.book.bookUrl == selectedBookUrl,
                chapterCount = migrationVerifiedChapterCount(
                    hit = hit,
                    selectedBookUrl = selectedBookUrl,
                    selectedChapterCount = selectedChapterCount,
                ),
                modifier = Modifier.width(MiruSize.Tile),
                onClick = { onSelect(hit) },
            )
        }
    }
}

// === Source rows ===
// One target source: accent icon tile, name, live library count, and the
// shared selection badge that decides whether the next search includes it.
@Composable
fun MigrationSourceRow(
    source: CatalogSource,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MiruSize.Control)
                .clip(RoundedCornerShape(MiruRadius.Inner))
                .background(extra.accent.copy(alpha = 0.14f)),
        ) {
            Text(
                text = source.name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = extra.accent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.migration_source_meta_fmt,
                    source.language,
                    source.libraryCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SelectionBadge(selected = selected)
    }
}

// === Search results (sheet) ===
// The book sheet keeps the flat source label + result grid of global search:
// no nested shadowed section card inside the sheet surface.
@Composable
fun MigrationGroupedResults(
    state: MigrationSearchUi,
    selectedBookUrl: String?,
    selectedChapterCount: Int?,
    onSelect: (MigrationSearchHit) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        MigrationSearchUi.Idle -> Unit
        MigrationSearchUi.Searching -> MigrationLoadingRow(
            stringResource(R.string.migration_searching),
        )
        is MigrationSearchUi.Failed -> MigrationErrorNotice(
            message = state.message.ifBlank {
                stringResource(R.string.migration_unknown_error)
            },
            onRetry = onRetry,
        )
        is MigrationSearchUi.Ready -> {
            val groups = groupMigrationHits(state.result.hits)
            if (groups.isEmpty()) {
                MigrationEmptyState(stringResource(R.string.migration_search_empty))
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.migration_retry))
                }
            } else {
                groups.forEach { group ->
                    MigrationSectionLabel(group.sourceName)
                    MigrationResultTiles(
                        hits = group.hits,
                        selectedBookUrl = selectedBookUrl,
                        selectedChapterCount = selectedChapterCount,
                        onSelect = onSelect,
                    )
                }
            }
            if (state.result.failedSources.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.migration_search_partial_fmt,
                        state.result.failedSources.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.S),
                )
            }
        }
    }
}

// === Target + options ===
@Composable
fun MigrationTargetCard(
    details: MigrationTargetDetails,
    plan: BookMigrationPlan,
    onOptions: (() -> Unit)? = null,
    onChangeTarget: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val book = details.target.book
    MiruSectionCard(
        title = stringResource(R.string.migration_plan_title),
        description = stringResource(R.string.migration_target_desc),
        contentPadding = PaddingValues(MiruSpace.L),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            BookCover(
                title = details.title,
                coverUrl = book.coverUrl,
                corner = MiruRadius.Cover,
                modifier = Modifier
                    .width(MiruSize.ButtonPrimary)
                    .aspectRatio(2f / 3f),
                showInitial = false,
                placeholderIcon = Icons.Filled.MenuBook,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.migration_plan_line_chapters,
                        details.chapters.size,
                        details.sourceName,
                        details.chapters.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank() && !book.author.equals("Unknown", ignoreCase = true)) {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        MigrationMatchProgress(
            plan = plan,
            modifier = Modifier.padding(top = MiruSpace.M),
        )
        // Same chip language as the step header; no text-link button next to
        // a chip, no action hidden in the section title.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
            verticalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
            modifier = Modifier.padding(top = MiruSpace.S),
        ) {
            if (onOptions != null) {
                MiruFilterChip(
                    selected = false,
                    onClick = onOptions,
                    label = stringResource(R.string.migration_options_action),
                    leadingIcon = Icons.Filled.Tune,
                )
            }
            if (onChangeTarget != null) {
                MiruFilterChip(
                    selected = false,
                    onClick = onChangeTarget,
                    label = stringResource(R.string.migration_change_target),
                    leadingIcon = Icons.Filled.SwapHoriz,
                )
            }
        }
    }
}

// Sheet-only flat target summary: still one source of truth for the target
// metadata, but it sits directly on the sheet surface.
@Composable
fun MigrationTargetSummary(details: MigrationTargetDetails) {
    MigrationSectionLabel(stringResource(R.string.migration_plan_title))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        BookCover(
            title = details.title,
            coverUrl = details.target.book.coverUrl,
            corner = MiruRadius.Cover,
            modifier = Modifier
                .width(MiruSize.Control)
                .aspectRatio(2f / 3f),
            showInitial = false,
            placeholderIcon = Icons.Filled.MenuBook,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = details.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.migration_plan_line_chapters,
                    details.chapters.size,
                    details.sourceName,
                    details.chapters.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MigrationMatchProgress(
    plan: BookMigrationPlan,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    // A missing chapter list is unknown, not "0 of 0 matched": never print a
    // contradictory count when the target has no chapters to compare.
    if (plan.chaptersTotal <= 0) {
        Text(
            text = stringResource(R.string.migration_no_chapters_to_match),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = modifier,
        )
        return
    }
    val fraction = plan.chaptersMatched.toFloat() / plan.chaptersTotal
    // One decimal: 3225/3227 is 99.9%, not a misleading 100%.
    val percent = String.format(java.util.Locale.US, "%.1f", fraction * 100f)
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.migration_matched_fmt,
                    plan.chaptersMatched,
                    plan.chaptersTotal,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.migration_percent_fmt, percent),
                style = MaterialTheme.typography.labelLarge,
                color = extra.accent,
            )
        }
        CardProgressBar(
            fraction = fraction,
            height = MiruSpace.S,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
            modifier = Modifier.padding(top = MiruSpace.S),
        )
        Text(
            text = stringResource(
                R.string.migration_unmatched_fmt,
                plan.unmatchedOld.size,
                plan.unmatchedNew.size,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
    }
}

@Composable
fun MigrationPlanSummary(plan: BookMigrationPlan) {
    MigrationMatchProgress(plan = plan, modifier = Modifier.padding(top = MiruSpace.S))
}

@Composable
fun MigrationOptionsEditor(
    options: BookMigrationOptions,
    onChange: (BookMigrationOptions) -> Unit,
    horizontalPadding: Dp = MiruSpace.L,
) {
    Column {
        SettingsSwitchRow(
            title = stringResource(R.string.migration_option_progress),
            subtitle = stringResource(R.string.migration_option_progress_desc),
            checked = options.transferProgress,
            onChange = { onChange(options.copy(transferProgress = it)) },
            horizontalPadding = horizontalPadding,
        )
        MiruRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.migration_option_body),
            subtitle = stringResource(R.string.migration_option_body_desc),
            checked = options.transferBodies,
            onChange = { onChange(options.copy(transferBodies = it)) },
            horizontalPadding = horizontalPadding,
        )
        MiruRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.migration_option_translations),
            subtitle = stringResource(R.string.migration_option_translations_desc),
            checked = options.transferTranslations,
            onChange = { onChange(options.copy(transferTranslations = it)) },
            horizontalPadding = horizontalPadding,
        )
        MiruRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.migration_option_delete_old),
            subtitle = stringResource(
                if (options.deleteOldBook) {
                    R.string.migration_delete_old_hint
                } else {
                    R.string.migration_keep_old_hint
                },
            ),
            checked = options.deleteOldBook,
            onChange = { onChange(options.copy(deleteOldBook = it)) },
            horizontalPadding = horizontalPadding,
        )
    }
}

// === Actions and states ===
@Composable
fun MigrationActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    fillWidth: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = MiruSize.ButtonPrimary),
        shape = MaterialTheme.shapes.small,
        // Busy keeps the primary fill so the spinner and label stay readable;
        // a plain disabled button sinks into the photo plate.
        colors = if (busy) {
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        contentPadding = PaddingValues(horizontal = MiruSpace.L, vertical = MiruSpace.S),
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = MiruSpace.Xxs,
                color = LocalContentColor.current,
                modifier = Modifier.size(MiruSize.Touch / 3),
            )
            Text(text = label, modifier = Modifier.padding(start = MiruSpace.S))
        } else {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(MiruSize.Touch / 3),
                )
                Text(text = label, modifier = Modifier.padding(start = MiruSpace.S))
            } else {
                Text(text = label)
            }
        }
    }
}

// The one floating primary action: the same 52dp action as a screen button on
// a card plate, so it lifts above the list instead of sitting inline at the
// bottom of the scroll. Callers own visibility/insets; this owns the look.
@Composable
fun MigrationFloatingAction(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(MiruRadius.Plate),
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        tonalElevation = 0.dp,
    ) {
        MigrationActionButton(
            label = label,
            onClick = onClick,
            enabled = enabled,
            busy = busy,
            icon = icon,
            fillWidth = false,
            modifier = Modifier.padding(MiruSpace.Xs),
        )
    }
}

@Composable
fun MigrationStateCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    loading: Boolean = false,
    errorTone: Boolean = false,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val tint = if (errorTone) MaterialTheme.colorScheme.error else extra.accent
    MiruCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MiruSize.Touch)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = MiruSpace.Xxs,
                        color = tint,
                        modifier = Modifier.size(MiruSize.Touch / 3),
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(MiruSize.Touch / 2),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = MiruSpace.M),
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = MiruSpace.Xs),
                )
            }
            if (actionLabel != null && onAction != null) {
                MigrationActionButton(
                    label = actionLabel,
                    onClick = onAction,
                    icon = actionIcon,
                    modifier = Modifier.padding(top = MiruSpace.M),
                )
            }
        }
    }
}

@Composable
fun MigrationErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    MigrationStateCard(
        title = stringResource(R.string.migration_error_title),
        description = message.ifBlank {
            stringResource(R.string.migration_unknown_error)
        },
        icon = Icons.Filled.Warning,
        errorTone = true,
        actionLabel = onRetry?.let { stringResource(R.string.migration_retry) },
        actionIcon = Icons.Filled.Refresh,
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
fun MigrationErrorNotice(message: String, onRetry: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MiruSpace.S),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = MiruSpace.Xxs)
                .size(MiruSize.Touch / 3),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.migration_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message.ifBlank {
                    stringResource(R.string.migration_unknown_error)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.migration_retry))
                }
            }
        }
    }
}

@Composable
fun MigrationLoadingRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MiruSpace.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        CircularProgressIndicator(
            strokeWidth = MiruSpace.Xxs,
            modifier = Modifier.size(MiruSize.Touch / 3),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
        )
    }
}

@Composable
fun MigrationRunningRow(current: Int, total: Int, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MiruSpace.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        CircularProgressIndicator(
            strokeWidth = MiruSpace.Xxs,
            modifier = Modifier.size(MiruSize.Touch / 3),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.migration_running_fmt, current, total),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MigrationRunCard(current: Int, total: Int, title: String) {
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            CircularProgressIndicator(
                strokeWidth = MiruSpace.Xxs,
                modifier = Modifier.size(MiruSize.Touch / 3),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.migration_running_fmt, current, total),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CardProgressBar(
            fraction = if (total > 0) current.toFloat() / total else 0f,
            height = MiruSpace.S,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
            modifier = Modifier.padding(top = MiruSpace.M),
        )
    }
}

@Composable
fun MigrationSuccessCard(
    result: BookMigrationResult,
    onOpenTarget: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MiruSize.Touch)
                    .clip(CircleShape)
                    .background(extra.accent.copy(alpha = 0.14f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = extra.accent,
                    modifier = Modifier.size(MiruSize.Touch / 2),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.migration_result_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (result.chaptersTotal > 0) {
                        stringResource(
                            R.string.migration_result_summary_fmt,
                            result.chaptersMatched,
                            result.chaptersTotal,
                            result.chaptersWithProgress,
                        )
                    } else {
                        stringResource(R.string.migration_no_chapter_data)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = MiruSpace.Xs),
                )
                Text(
                    text = stringResource(
                        if (result.oldDeleted) {
                            R.string.migration_result_old_deleted
                        } else {
                            R.string.migration_result_old_kept
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
        }
        if (onOpenTarget != null) {
            MigrationActionButton(
                label = stringResource(R.string.migration_open_target),
                onClick = onOpenTarget,
                modifier = Modifier.padding(top = MiruSpace.M),
            )
        }
        Text(
            text = stringResource(R.string.migration_idempotent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.S),
        )
    }
}

@Composable
fun MigrationSuccessNotice(result: BookMigrationResult) {
    val extra = LocalMiruExtraColors.current
    MigrationSectionLabel(stringResource(R.string.migration_result_title))
    Row(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = extra.accent,
            modifier = Modifier
                .padding(top = MiruSpace.Xxs)
                .size(MiruSize.Touch / 3),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (result.chaptersTotal > 0) {
                    stringResource(
                        R.string.migration_result_summary_fmt,
                        result.chaptersMatched,
                        result.chaptersTotal,
                        result.chaptersWithProgress,
                    )
                } else {
                    stringResource(R.string.migration_no_chapter_data)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    if (result.oldDeleted) {
                        R.string.migration_result_old_deleted
                    } else {
                        R.string.migration_result_old_kept
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

@Composable
fun MigrationHistoryCard(
    group: MigrationHistoryGroup,
    onDelete: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val latest = group.latest
    val fraction = if (latest.chaptersTotal > 0) {
        latest.chaptersMatched.toFloat() / latest.chaptersTotal
    } else {
        0f
    }
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    MigrationStatusPill(latest)
                    Text(
                        text = formatMigrationTime(latest.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                }
                if (latest.chaptersTotal > 0) {
                    Text(
                        text = stringResource(
                            R.string.migration_history_row_fmt,
                            latest.chaptersMatched,
                            latest.chaptersTotal,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = MiruSpace.Xs),
                    )
                    CardProgressBar(
                        fraction = fraction,
                        height = MiruSpace.S,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
                        modifier = Modifier.padding(top = MiruSpace.S),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.migration_no_chapter_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(top = MiruSpace.Xs),
                    )
                }
                latest.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = stringResource(R.string.migration_error_fmt, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = MiruSpace.Xs),
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(MiruSize.Touch),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.migration_history_delete_cd),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MiruSize.Touch / 2),
                )
            }
        }
    }
}

@Composable
private fun MigrationStatusPill(row: BookMigrationEntity) {
    val extra = LocalMiruExtraColors.current
    val tint = when (row.status) {
        "completed" -> extra.accent
        "failed", "cancelled" -> MaterialTheme.colorScheme.error
        else -> extra.muted
    }
    Surface(
        shape = RoundedCornerShape(MiruRadius.Badge),
        color = tint.copy(alpha = 0.14f),
    ) {
        Text(
            text = migrationStatusLabel(row),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xxs),
        )
    }
}

@Composable
fun migrationStatusLabel(row: BookMigrationEntity): String = stringResource(
    // Stored status values; the core constants are internal to :mirutime-data.
    when (row.status) {
        "running" -> R.string.migration_status_running
        "completed" -> R.string.migration_status_completed
        "partial" -> R.string.migration_status_partial
        "failed" -> R.string.migration_status_failed
        "cancelled" -> R.string.migration_status_cancelled
        else -> R.string.migration_status_unknown
    },
)

@Composable
fun MigrationEmptyState(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalMiruExtraColors.current.muted,
        modifier = Modifier.padding(vertical = MiruSpace.L),
    )
}

fun formatMigrationTime(at: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(at))
