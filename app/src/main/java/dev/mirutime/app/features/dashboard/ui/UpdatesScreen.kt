package dev.mirutime.app.features.dashboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.app.features.library.data.UpdateCheckSummary
import dev.mirutime.app.features.library.data.UpdateProgress
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.core.PluginErrorReport
import dev.mirutime.app.design.components.PluginErrorState
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.SkeletonBox
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.app.features.dashboard.data.DashboardDataProvider
import dev.mirutime.app.features.dashboard.domain.UpdateItem

// Drill-in route: the shell hides the floating tab panel here, so the
// content only keeps a small breathing gap above the system navigation bar.
private val BottomClearance = 8.dp

// Dedicated Updates screen (drill-in from the dashboard card's "Show all N"):
// every new chapter on the shelves, same fake data, rows shared with the card.
// Back returns to the dashboard via the shell header — standard back behavior.
@Composable
fun UpdatesScreen(
    contentPadding: PaddingValues,
    provider: DashboardDataProvider,
    refreshKey: Int = 0,
    onRefresh: suspend ((UpdateProgress) -> Unit) -> UpdateCheckSummary = { UpdateCheckSummary(0, 0, 0) },
    onOpenSettings: () -> Unit = {},
    onBookOpen: (String) -> Unit = {},
) {
    var updates by remember { mutableStateOf<List<UpdateItem>>(emptyList()) }
    // First load owns the skeleton; a later refresh keeps the rows already on
    // screen, so checkAll() + updates() never blanks the list.
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<UpdateProgress?>(null) }
    var failure by remember { mutableStateOf<PluginErrorReport?>(null) }
    val scope = rememberCoroutineScope()

    fun runCheck() {
        if (refreshing) return
        refreshing = true
        progress = null
        scope.launch {
            try {
                val summary = onRefresh { p -> progress = p }
                failure = summary.firstError?.let { error ->
                    buildPluginErrorReport(
                        error = error,
                        sourceId = summary.firstErrorSource,
                        operation = "updates",
                        identity = summary.firstErrorSource,
                    )
                }
            } finally {
                AppSettings.libraryLastRunAt = System.currentTimeMillis()
                updates = provider.updates()
                loaded = true
                refreshing = false
                progress = null
            }
        }
    }

    // Opening the screen only reads the stored feed: a network check is an
    // explicit user action (the primary button), never a side effect of
    // navigation.
    LaunchedEffect(provider) {
        updates = provider.updates()
        loaded = true
    }
    // Opening a book marks its updates seen outside this screen; the shell
    // bumps the key so the badge disappears when the user comes back.
    LaunchedEffect(provider, refreshKey) {
        if (refreshKey > 0) updates = provider.updates()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + BottomClearance,
        ),
    ) {
        item(key = "updates-header") {
            UpdatesHeader(
                refreshing = refreshing,
                progress = progress,
                onCheckNow = ::runCheck,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(bottom = MiruSpace.M),
            )
        }
        failure?.let { report ->
            item(key = "updates-error") {
                PluginErrorState(
                    title = report.title ?: stringResource(R.string.updates_error_title),
                    message = report.message
                        ?: stringResource(R.string.updates_error_desc),
                    detailText = report.detailText,
                    reportText = report.reportText,
                    onRetry = ::runCheck,
                    modifier = Modifier.padding(bottom = MiruSpace.M),
                )
            }
        }
        if (loaded && updates.isEmpty()) {
            item(key = "updates-empty") {
                MiruSectionCard(
                    title = stringResource(R.string.updates_empty_title),
                    description = stringResource(R.string.updates_empty_desc),
                ) {}
            }
        } else if (!loaded) {
            // Three rows in the final geometry instead of a blank list while
            // checkAll() + updates() run on the first open.
            items(3) {
                UpdateRowSkeleton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
        } else {
            itemsIndexed(updates, key = { index, update -> "update:${update.book.bookKey}:$index" }) { _, update ->
                UpdateRow(
                    update = update,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onOpen = { onBookOpen(update.book.bookKey) },
                )
            }
        }
    }
}

@Composable
private fun UpdatesHeader(
    refreshing: Boolean,
    progress: UpdateProgress?,
    onCheckNow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = AppSettings
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            now = System.currentTimeMillis()
        }
    }
    val lastRunAt = settings.libraryLastRunAt
    val intervalMs = settings.libraryIntervalHours * 3_600_000L
    // Before the first recorded run the periodic work still has an initial
    // delay of one interval; show that countdown instead of a duplicate
    // "no run yet" line.
    val nextAt = if (
        settings.libraryAutoUpdate &&
        settings.libraryIntervalHours > 0
    ) {
        (if (lastRunAt > 0L) lastRunAt else now) + intervalMs
    } else {
        0L
    }
    val relativeLast = if (lastRunAt > 0L) {
        if (now - lastRunAt < 60_000L) {
            stringResource(R.string.updates_last_run_just_now)
        } else {
            android.text.format.DateUtils.getRelativeTimeSpanString(
                lastRunAt,
                now,
                android.text.format.DateUtils.MINUTE_IN_MILLIS,
                android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
        }
    } else {
        null
    }

    MiruSectionCard(
        title = stringResource(R.string.st_updates_library),
        description = stringResource(R.string.st_updates_library_desc),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(MiruSpace.L)) {
            MiruButton(
                onClick = onCheckNow,
                style = MiruButtonStyle.Primary,
                busy = refreshing,
                busyDescription = stringResource(R.string.st_updates_checking),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.updates_check_now))
            }
            if (refreshing && progress != null) {
                LinearProgressIndicator(
                    progress = {
                        if (progress.total > 0) {
                            progress.done.toFloat() / progress.total.toFloat()
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MiruSpace.S),
                )
                if (progress.title.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.updates_progress_fmt,
                            progress.done,
                            progress.total,
                            progress.title,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = MiruSpace.Xs),
                    )
                }
            }
            Text(
                text = relativeLast?.let {
                    stringResource(R.string.updates_last_run_fmt, it)
                } ?: stringResource(R.string.updates_never_run),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(top = MiruSpace.S),
            )
            val nextText = when {
                !settings.libraryAutoUpdate -> stringResource(R.string.updates_auto_off)
                settings.libraryIntervalHours <= 0 -> stringResource(R.string.updates_next_launch)
                nextAt <= 0L -> stringResource(R.string.updates_next_launch)
                nextAt <= now -> stringResource(R.string.updates_next_soon)
                else -> stringResource(
                    R.string.updates_next_fmt,
                    formatRemaining(nextAt - now),
                )
            }
            Text(
                text = nextText,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(top = MiruSpace.Xxs),
            )
        }
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Tune,
            title = stringResource(R.string.updates_settings),
            subtitle = stringResource(R.string.updates_settings_desc),
            onClick = onOpenSettings,
        )
    }
}

private fun formatRemaining(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun UpdateRowSkeleton(modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                start = CoverInset,
                top = 8.dp,
                end = 12.dp,
                bottom = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkeletonBox(width = 76.dp, height = 110.dp, corner = MiruRadius.Cover)
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.28f), height = 10.dp)
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.8f), height = 14.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.5f), height = 10.dp)
                Spacer(modifier = Modifier.height(10.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 4.dp)
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBar(modifier = Modifier.fillMaxWidth(0.35f), height = 10.dp)
            }
        }
    }
}

// The dashboard's OWN dense update row (panel + drill-in share it): 56x84
// cover inside a 4dp frame — the height follows the content, there is no
// reserved thumb-sized void. "N new chapters" badge plate
// (primaryContainer/onPrimaryContainer), 15sp bold title, "source · time"
// line, thin primary bar and ONE "X / Y гл. · ещё Z" counts line that wraps
// instead of being cut.
@Composable
fun UpdateRow(
    update: UpdateItem,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    MiruCard(
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen),
    ) {
        Row(
            // Cover sits on the plate with the shared 2dp frame; top/bottom
            // stay at 8dp so the row keeps its density. Centering keeps the
            // taller cover from leaving a gap under the text block.
            modifier = Modifier.padding(
                start = CoverInset,
                top = 8.dp,
                end = 12.dp,
                bottom = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                title = update.book.translatedTitle ?: update.book.title,
                coverUrl = update.book.coverUrl,
                modifier = Modifier
                    .width(76.dp)
                    .aspectRatio(1f / 1.45f)
                    .padding(CoverInset),
                corner = MiruRadius.Cover,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(MiruRadius.Badge),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.plural_new_chapters,
                                update.newChapters,
                                update.newChapters,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = MiruSpace.S, vertical = 3.dp),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // Where a tap lands: the first unread chapter.
                    Text(
                        text = stringResource(R.string.card_next_ch_fmt, update.book.nextChapter),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = LocalMiruExtraColors.current.accent,
                        maxLines = 1,
                    )
                }
                Text(
                    text = update.book.translatedTitle ?: update.book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "${update.book.sourceLabel} · ${update.book.updatedLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalMiruExtraColors.current.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.height(MiruSpace.S))
                DashBar(
                    update.book.readChapters.toFloat() /
                        update.book.totalChapters.coerceAtLeast(1),
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            append(
                                stringResource(
                                    R.string.card_slash_fmt,
                                    update.book.readChapters,
                                    update.book.totalChapters,
                                ),
                            )
                        }
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Normal,
                                color = LocalMiruExtraColors.current.muted,
                            ),
                        ) {
                            append(
                                " · ${stringResource(
                                    R.string.card_more_fmt,
                                    update.book.chaptersLeft,
                                )}",
                            )
                        }
                    },
                    modifier = Modifier.padding(top = MiruSpace.Xs),
                )
            }
        }
    }
}
