package dev.mirutime.app.features.downloads.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.LocalMiruLocale
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.app.features.downloads.data.DownloadBookUi
import dev.mirutime.app.features.downloads.data.DownloadState
import dev.mirutime.app.features.downloads.data.DownloadUiMapper
import dev.mirutime.app.features.settings.data.StorageBucket
import dev.mirutime.app.features.settings.data.StorageUsage
import dev.mirutime.app.features.settings.ui.formatBytes
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Download manager: device storage panel,
// download settings in a bottom sheet, then Downloaded / Queue tabs over the
// real Room queue. Storage sizes and cleanup use StorageUsageService.
private enum class DlTab { Downloaded, Queue }

// Cleanup targets of the download settings sheet. Every one of them deletes
// data, so the screen confirms before clearing the real cache bucket.
private enum class CleanupKind { Cache, MangaChapters, Novels, Video }

private data class StorageSlice(val labelRes: Int, val bytes: Long, val color: Color)

// Latest translation status per chapter collapsed to per-book counts.
private data class TranslationQueueSummary(val pending: Int = 0, val failed: Int = 0) {
    val total: Int get() = pending + failed
}

@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    onOpenBook: (String) -> Unit = {},
    // bookId + source URL: the CF/login source action must re-arm the parked
    // book, so the shell routes it through CfSolveActivity (same solver as the
    // notification action) instead of a bare in-app WebView.
    onOpenSource: (Long, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalMiruLocale.current
    val app = context.applicationContext as MiruApp
    val engine = app.downloadEngine
    val repository = app.libraryRepository
    val storage = app.storageUsageService
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf(StorageUsage.Empty) }
    // First Room/storage load owns the skeleton; "empty" and "0 B" are only
    // claimed after real data has landed.
    var rowsLoaded by remember { mutableStateOf(false) }
    var usageLoaded by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<DownloadBookUi>>(emptyList()) }
    var tab by remember { mutableStateOf(DlTab.Downloaded) }
    var settingsOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<DownloadBookUi?>(null) }
    var pendingDelete by remember { mutableStateOf<DownloadBookUi?>(null) }
    var pendingCleanup by remember { mutableStateOf<CleanupKind?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // Resource id of the action currently running (delete/cleanup/enqueue);
    // shows an indeterminate banner and ignores duplicate taps.
    var busyLabel by remember { mutableStateOf<Int?>(null) }
    var translationSummaries by remember { mutableStateOf<Map<Long, TranslationQueueSummary>>(emptyMap()) }

    LaunchedEffect(engine, repository, refreshTick) {
        engine.observeAll().collect { tasks ->
            rows = tasks.groupBy { it.bookId }.mapNotNull { (bookId, bookTasks) ->
                val book = runCatching { repository.book(bookId) }.getOrNull()
                    ?: return@mapNotNull null
                val snapshot = runCatching { repository.chapterStatusSnapshot(bookId) }
                    .getOrNull()
                DownloadUiMapper.map(
                    book = book,
                    tasks = bookTasks,
                    downloadedChapters = snapshot?.downloadedIndexes?.size ?: 0,
                    chapterCount = snapshot?.chapterCount ?: book.totalChapters,
                )
            }.sortedByDescending { it.bookId }
            rowsLoaded = true
            val refreshed = runCatching { storage.refresh() }.getOrNull()
            if (refreshed != null) {
                usage = refreshed
                usageLoaded = true
            }
        }
    }

    LaunchedEffect(repository) {
        repository.chapterTranslationQueuePendingRowsFlow().collect { rows ->
            translationSummaries = rows
                .groupBy { it.chapterId }
                .mapValues { (_, chapterRows) -> chapterRows.first() }
                .values
                .groupBy { it.bookId }
                .mapValues { (_, chapterRows) ->
                    TranslationQueueSummary(
                        pending = chapterRows.count {
                            it.status.equals("PENDING", ignoreCase = true)
                        },
                        failed = chapterRows.count {
                            it.status.equals("FAILED", ignoreCase = true)
                        },
                    )
                }
        }
    }

    fun toast(res: Int, vararg args: Any) {
        Toast.makeText(context, resources.getString(res, *args), Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        refreshTick++
    }

    fun runBusy(labelRes: Int, block: suspend () -> Unit) {
        if (busyLabel != null) return
        busyLabel = labelRes
        scope.launch {
            try {
                block()
            } finally {
                busyLabel = null
            }
        }
    }

    val activeRows = remember(rows) {
        rows.filter {
            it.isActive || it.isPaused || it.isFailed || it.isBlocked
        }
    }
    val historyRows = remember(rows) {
        rows.filter { row ->
            row.state == DownloadState.COMPLETED || row.downloaded > 0
        }
    }
    val shown = if (tab == DlTab.Downloaded) historyRows else activeRows

    val extra = LocalMiruExtraColors.current
    val slices = listOf(
        StorageSlice(
            R.string.dl_storage_cache,
            usage.bytesFor(StorageBucket.MangaCache),
            extra.accent,
        ),
        StorageSlice(
            R.string.dl_storage_images,
            usage.bytesFor(StorageBucket.Images),
            MaterialTheme.colorScheme.tertiary,
        ),
        StorageSlice(
            R.string.dl_storage_texts,
            usage.bytesFor(StorageBucket.NovelCache),
            MaterialTheme.colorScheme.primary,
        ),
        StorageSlice(
            R.string.dl_storage_video,
            usage.bytesFor(StorageBucket.OfflineVideo),
            MaterialTheme.colorScheme.secondary,
        ),
    )
    val totalBytes = slices.sumOf { it.bytes }
    // Counts and sizes stay "…" until the first real emission, so the screen
    // never claims "0" / "0 B" before Room and storage answer.
    val downloadedLabel = if (rowsLoaded) {
        "${stringResource(R.string.dl_tab_downloaded)} (${historyRows.size})"
    } else {
        stringResource(R.string.dl_tab_downloaded)
    }
    val queueLabel = if (rowsLoaded) {
        "${stringResource(R.string.dl_tab_queue)} (${activeRows.size})"
    } else {
        stringResource(R.string.dl_tab_queue)
    }
    val storageValue = if (usageLoaded) {
        formatBytes(totalBytes, locale)
    } else {
        stringResource(R.string.loading_value)
    }

    SettingsPage(contentPadding) {
        busyLabel?.let { label ->
            item(key = "busy") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MiruSpace.S),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalMiruExtraColors.current.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(MiruSpace.S))
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
            }
        }
        item(key = "storage") {
            MiruSectionCard(
                title = stringResource(R.string.dl_storage_title),
                description = stringResource(R.string.dl_storage_used_fmt, storageValue),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(modifier = Modifier.padding(MiruSpace.L)) {
                    if (!usageLoaded) {
                        SkeletonBar(
                            modifier = Modifier.fillMaxWidth(),
                            height = 14.dp,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .clip(RoundedCornerShape(MiruRadius.Chip))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            slices.filter { it.bytes > 0L }.forEach { slice ->
                                Box(
                                    modifier = Modifier
                                        .weight(slice.bytes.toFloat())
                                        .fillMaxHeight()
                                        .background(slice.color),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(MiruSpace.M))
                    slices.forEach { StorageLegendRow(it, loading = !usageLoaded) }
                }
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.dl_settings),
                    subtitle = stringResource(R.string.dl_settings_sub),
                    onClick = { settingsOpen = true },
                )
            }
        }
        item(key = "tabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                MiruFilterChip(
                    selected = tab == DlTab.Downloaded,
                    onClick = { tab = DlTab.Downloaded },
                    label = downloadedLabel,
                )
                MiruFilterChip(
                    selected = tab == DlTab.Queue,
                    onClick = { tab = DlTab.Queue },
                    label = queueLabel,
                )
            }
        }
        if (!rowsLoaded) {
            item(key = "loading") {
                DownloadsSkeleton()
            }
        } else if (shown.isEmpty()) {
            item(key = "empty") {
                MiruSectionCard(
                    title = stringResource(
                        if (tab == DlTab.Downloaded) R.string.dl_tab_downloaded else R.string.dl_tab_queue,
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SectionMessage(
                        stringResource(
                            if (tab == DlTab.Downloaded) {
                                R.string.dl_empty_done
                            } else {
                                R.string.dl_empty_active
                            },
                        ),
                    )
                }
            }
        } else if (tab == DlTab.Downloaded) {
            item(key = "downloaded") {
                MiruSectionCard(
                    title = stringResource(R.string.dl_completed_fmt, historyRows.size),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    historyRows.forEachIndexed { index, row ->
                        if (index > 0) MiruRowDivider()
                        WorkRow(
                            work = row,
                            translation = translationSummaries[row.bookId]
                                ?: TranslationQueueSummary(),
                            onClick = { onOpenBook(row.bookUrl) },
                            onMore = { actionsFor = row },
                        )
                    }
                }
            }
        } else {
            item(key = "queue") {
                MiruSectionCard(
                    title = stringResource(R.string.dl_in_progress_fmt, activeRows.size),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    activeRows.forEachIndexed { index, row ->
                        if (index > 0) MiruRowDivider()
                        QueueRow(
                            work = row,
                            translation = translationSummaries[row.bookId]
                                ?: TranslationQueueSummary(),
                            onPause = { engine.pause(row.bookId); refresh() },
                            onResume = { engine.resume(row.bookId); refresh() },
                            onRetry = { engine.retry(row.bookId); refresh() },
                            onCancel = { engine.cancel(row.bookId); refresh() },
                            onOpenSource = { onOpenSource(row.bookId, row.bookUrl) },
                        )
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        DownloadSettingsSheet(
            onClose = { settingsOpen = false },
            onCleanup = { pendingCleanup = it },
        )
    }

    actionsFor?.let { work ->
        DownloadActionsSheet(
            work = work,
            onDelete = {
                actionsFor = null
                pendingDelete = work
            },
            onDownloadAll = {
                actionsFor = null
                runBusy(R.string.loading_queueing) {
                    val result = engine.enqueueNow(work.bookUrl, null)
                    toast(
                        if (result.queued > 0) R.string.dl_toast_queued else R.string.book_dl_nothing_new,
                        result.queued,
                    )
                    refresh()
                }
            },
            onMoreChapters = {
                actionsFor = null
                runBusy(R.string.loading_queueing) {
                    val result = engine.enqueueNext100Now(work.bookUrl)
                    toast(
                        if (result.queued > 0) {
                            R.string.dl_toast_more_chapters
                        } else {
                            R.string.book_dl_nothing_new
                        },
                        result.queued,
                    )
                    refresh()
                }
            },
            onDownloadAndTranslate = {
                actionsFor = null
                runBusy(R.string.loading_queueing) {
                    val result = engine.enqueueNow(work.bookUrl, null, translateAfter = true)
                    when {
                        !result.translationConfigured ->
                            toast(R.string.book_translate_not_configured)
                        result.queued > 0 -> toast(R.string.dl_toast_queued, result.queued)
                        result.translationsQueued > 0 -> toast(
                            R.string.book_dl_translate_queued_fmt,
                            result.translationsQueued,
                        )
                        result.translationsAlreadyQueued > 0 -> toast(
                            R.string.book_dl_translate_pending_fmt,
                            result.translationsAlreadyQueued,
                        )
                        else -> toast(R.string.book_dl_nothing_new)
                    }
                    refresh()
                }
            },
            onRetry = {
                actionsFor = null
                runBusy(R.string.loading_queueing) {
                    engine.retry(work.bookId)
                    refresh()
                }
            },
            onOpenSource = {
                actionsFor = null
                if (work.bookUrl.isNotBlank()) onOpenSource(work.bookId, work.bookUrl)
            },
            onClose = { actionsFor = null },
        )
    }

    // Destructive actions confirm first, in the same sheet standard as
    // Settings -> Data and storage.
    pendingDelete?.let { work ->
        AppBottomSheet(
            title = work.title,
            description = stringResource(R.string.dl_confirm_delete_desc),
            onClose = { pendingDelete = null },
            doneLabel = stringResource(R.string.dl_action_delete),
            doneDanger = true,
            onDone = {
                pendingDelete = null
                runBusy(R.string.loading_deleting) {
                    engine.deleteNow(work.bookId)
                    toast(R.string.dl_toast_deleted)
                    refresh()
                }
            },
        ) {
            // No extra content: title + description carry the warning.
        }
    }

    pendingCleanup?.let { kind ->
        val cleanupBytes = usage.bytesFor(cleanupBucket(kind))
        AppBottomSheet(
            title = stringResource(cleanupTitleRes(kind)),
            description = stringResource(
                R.string.dl_confirm_cleanup_desc,
                formatBytes(cleanupBytes, locale),
            ),
            onClose = { pendingCleanup = null },
            doneLabel = stringResource(R.string.st_data_clear),
            doneDanger = true,
            onDone = {
                pendingCleanup = null
                runBusy(R.string.loading_cleaning) {
                    val freed = cleanupBytes
                    runCatching { storage.clear(cleanupBucket(kind)) }
                    usage = runCatching { storage.refresh() }.getOrDefault(usage)
                    usageLoaded = true
                    toast(R.string.dl_cleanup_toast_fmt, formatBytes(freed, locale))
                    refresh()
                }
            },
        ) {
            // No extra content: title + description carry the warning.
        }
    }
}

@Composable
private fun StorageLegendRow(slice: StorageSlice, loading: Boolean = false) {
    val extra = LocalMiruExtraColors.current
    val locale = LocalMiruLocale.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 26.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(slice.color),
        )
        Spacer(modifier = Modifier.width(MiruSpace.S))
        Text(
            text = stringResource(slice.labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            SkeletonBar(modifier = Modifier.width(40.dp), height = 10.dp)
        } else {
            Text(
                text = formatBytes(slice.bytes, locale),
                style = MaterialTheme.typography.labelSmall,
                color = extra.muted,
            )
        }
    }
}

@Composable
private fun WorkRow(
    work: DownloadBookUi,
    translation: TranslationQueueSummary,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 88.dp)
            .padding(start = MiruSpace.L, end = MiruSpace.Xs, top = MiruSpace.S, bottom = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        BookCover(
            title = work.title,
            corner = MiruRadius.Cover,
            coverUrl = work.coverUrl,
            modifier = Modifier
                .width(40.dp)
                .aspectRatio(1f / 1.45f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.dl_work_chapters_fmt,
                    work.downloaded,
                    work.chapterCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TranslationQueueLine(translation)
        }
        IconButton(onClick = onMore, modifier = Modifier.size(MiruSize.Touch)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.dl_more_cd),
                tint = extra.muted,
            )
        }
    }
}

@Composable
private fun QueueRow(
    work: DownloadBookUi,
    translation: TranslationQueueSummary,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val status = when (work.state) {
        DownloadState.QUEUED -> stringResource(R.string.dl_status_queued)
        DownloadState.RUNNING -> stringResource(
            R.string.dl_status_downloading_fmt,
            (work.progress * 100).roundToInt(),
        )
        DownloadState.PAUSED -> stringResource(R.string.dl_status_paused)
        DownloadState.FAILED -> stringResource(
            R.string.dl_status_failed_fmt,
            work.error ?: stringResource(R.string.dl_status_cancelled),
        )
        DownloadState.NEEDS_SOURCE_ACTION -> stringResource(
            R.string.dl_status_source_action_fmt,
            work.error ?: stringResource(R.string.dl_status_source_action),
        )
        DownloadState.WAITING_NETWORK -> stringResource(
            R.string.dl_status_waiting_network_fmt,
            work.error ?: stringResource(R.string.dl_status_waiting_network),
        )
        DownloadState.COMPLETED -> stringResource(R.string.dl_status_completed)
        DownloadState.CANCELLED -> stringResource(R.string.dl_status_cancelled)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 88.dp)
            .padding(start = MiruSpace.L, end = MiruSpace.Xs, top = MiruSpace.S, bottom = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.dl_book_progress_fmt,
                    work.completedTasks,
                    work.taskCount,
                    (work.progress * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(MiruSpace.Xs))
            LinearProgressIndicator(
                progress = { work.progress },
                color = if (work.state == DownloadState.PAUSED) extra.muted else extra.accent,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(MiruSpace.Xxs))
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = if (work.isFailed || work.isNeedsSourceAction) {
                    MaterialTheme.colorScheme.error
                } else {
                    extra.muted
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TranslationQueueLine(translation)
        }
        if (work.isFailed || work.isNeedsSourceAction || work.isWaitingNetwork) {
            if (work.isNeedsSourceAction && work.bookUrl.isNotBlank()) {
                IconButton(onClick = onOpenSource, modifier = Modifier.size(MiruSize.Touch)) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = stringResource(R.string.dl_action_open_source),
                        tint = extra.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = onRetry, modifier = Modifier.size(MiruSize.Touch)) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.dl_action_retry),
                    tint = extra.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (work.isWaitingNetwork) {
                IconButton(onClick = onPause, modifier = Modifier.size(MiruSize.Touch)) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = stringResource(R.string.dl_queue_pause_cd),
                        tint = extra.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            IconButton(
                onClick = if (work.state == DownloadState.PAUSED) onResume else onPause,
                modifier = Modifier.size(MiruSize.Touch),
            ) {
                Icon(
                    imageVector = if (work.state == DownloadState.PAUSED) {
                        Icons.Filled.PlayArrow
                    } else {
                        Icons.Filled.Pause
                    },
                    contentDescription = stringResource(
                        if (work.state == DownloadState.PAUSED) {
                            R.string.dl_queue_resume_cd
                        } else {
                            R.string.dl_queue_pause_cd
                        },
                    ),
                    tint = extra.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(MiruSize.Touch)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.dl_cancel_cd),
                tint = extra.muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TranslationQueueLine(summary: TranslationQueueSummary) {
    if (summary.total == 0) return
    val extra = LocalMiruExtraColors.current
    Text(
        text = when {
            summary.failed > 0 && summary.pending > 0 ->
                stringResource(
                    R.string.dl_translation_queue_fmt,
                    summary.pending,
                    summary.failed,
                )
            summary.failed > 0 -> stringResource(R.string.dl_translation_failed_fmt, summary.failed)
            else -> stringResource(R.string.dl_translation_pending_fmt, summary.pending)
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (summary.failed > 0) MaterialTheme.colorScheme.error else extra.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DownloadActionsSheet(
    work: DownloadBookUi,
    onDelete: () -> Unit,
    onDownloadAll: () -> Unit,
    onMoreChapters: () -> Unit,
    onDownloadAndTranslate: () -> Unit,
    onRetry: () -> Unit,
    onOpenSource: () -> Unit,
    onClose: () -> Unit,
) {
    AppBottomSheet(
        title = work.title,
        description = stringResource(
            R.string.dl_work_chapters_fmt,
            work.downloaded,
            work.chapterCount,
        ),
        onClose = onClose,
    ) {
        if (work.isFailed || work.isNeedsSourceAction || work.isWaitingNetwork) {
            if (work.isNeedsSourceAction && work.bookUrl.isNotBlank()) {
                MiruActionRow(
                    icon = Icons.Filled.Public,
                    title = stringResource(R.string.dl_action_open_source),
                    subtitle = work.error.orEmpty(),
                    onClick = onOpenSource,
                )
                MiruRowDivider()
            }
            MiruActionRow(
                icon = Icons.Filled.Refresh,
                title = stringResource(R.string.dl_action_retry),
                subtitle = work.error.orEmpty(),
                onClick = onRetry,
            )
            MiruRowDivider()
        }
        if (work.downloaded < work.chapterCount) {
            MiruActionRow(
                icon = Icons.Filled.Download,
                title = stringResource(R.string.dl_action_download_all),
                subtitle = stringResource(R.string.dl_action_download_all_sub),
                onClick = onDownloadAll,
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Add,
                title = stringResource(R.string.dl_action_more_chapters),
                subtitle = stringResource(R.string.dl_action_more_chapters_sub),
                onClick = onMoreChapters,
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Translate,
                title = stringResource(R.string.book_dl_and_translate),
                subtitle = stringResource(R.string.book_dl_and_translate_desc),
                onClick = onDownloadAndTranslate,
            )
            MiruRowDivider()
        }
        MiruActionRow(
            icon = Icons.Filled.Delete,
            title = stringResource(R.string.dl_action_delete),
            subtitle = stringResource(R.string.dl_action_delete_sub),
            onClick = onDelete,
        )
    }
}

private fun cleanupTitleRes(kind: CleanupKind): Int = when (kind) {
    CleanupKind.Cache -> R.string.dl_cleanup_cache
    CleanupKind.MangaChapters -> R.string.dl_cleanup_manga
    CleanupKind.Novels -> R.string.dl_cleanup_novels
    CleanupKind.Video -> R.string.dl_cleanup_video
}

private fun cleanupBucket(kind: CleanupKind): StorageBucket = when (kind) {
    CleanupKind.Cache, CleanupKind.MangaChapters -> StorageBucket.MangaCache
    CleanupKind.Novels -> StorageBucket.NovelCache
    CleanupKind.Video -> StorageBucket.OfflineVideo
}

@Composable
private fun DownloadSettingsSheet(
    onClose: () -> Unit,
    onCleanup: (CleanupKind) -> Unit,
) {
    val extra = LocalMiruExtraColors.current

    AppBottomSheet(
        title = stringResource(R.string.dl_settings),
        description = stringResource(R.string.dl_settings_desc),
        onClose = onClose,
    ) {
        Text(
            text = stringResource(R.string.dl_cleanup_section),
            style = MaterialTheme.typography.labelLarge,
            color = extra.accent,
        )
        MiruActionRow(
            icon = Icons.Filled.PhotoLibrary,
            title = stringResource(R.string.dl_cleanup_cache),
            subtitle = stringResource(R.string.dl_cleanup_cache_sub),
            onClick = { onCleanup(CleanupKind.Cache) },
        )
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.MenuBook,
            title = stringResource(R.string.dl_cleanup_manga),
            subtitle = stringResource(R.string.dl_cleanup_manga_sub),
            onClick = { onCleanup(CleanupKind.MangaChapters) },
        )
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Translate,
            title = stringResource(R.string.dl_cleanup_novels),
            subtitle = stringResource(R.string.dl_cleanup_novels_sub),
            onClick = { onCleanup(CleanupKind.Novels) },
        )
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Movie,
            title = stringResource(R.string.dl_cleanup_video),
            subtitle = stringResource(R.string.dl_cleanup_video_sub),
            onClick = { onCleanup(CleanupKind.Video) },
        )
    }
}

// First Room load: three row skeletons in the real card geometry, so the
// "nothing downloaded" card never flashes before data arrives.
@Composable
private fun DownloadsSkeleton() {
    MiruSectionCard(
        title = stringResource(R.string.loading_downloads),
        contentPadding = PaddingValues(0.dp),
    ) {
        repeat(3) { index ->
            if (index > 0) MiruRowDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 88.dp)
                    .padding(start = MiruSpace.L, end = MiruSpace.L, top = MiruSpace.S, bottom = MiruSpace.S),
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            ) {
                SkeletonBar(modifier = Modifier.width(40.dp), height = 58.dp)
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBar(modifier = Modifier.fillMaxWidth(0.65f), height = 12.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBar(modifier = Modifier.fillMaxWidth(0.4f), height = 10.dp)
                }
            }
        }
    }
}

// Short message inside a section card (empty queue / empty downloads).
@Composable
private fun SectionMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalMiruExtraColors.current.muted,
        modifier = Modifier.padding(MiruSpace.L),
    )
}
