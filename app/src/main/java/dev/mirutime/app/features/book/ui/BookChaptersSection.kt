package dev.mirutime.app.features.book.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.core.PluginErrorReport
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookReview
import dev.mirutime.app.features.downloads.data.DownloadState
import dev.mirutime.app.features.translation.data.ChapterTranslationStatus
import dev.mirutime.app.features.video.data.VideoDownloadAction
import dev.mirutime.app.features.video.data.VideoDownloadItem
import dev.mirutime.app.features.video.data.VideoDownloadState
import dev.mirutime.app.features.video.data.videoDownloadActions
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruCardType
import dev.mirutime.uikit.MiruCompactButton
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.compactTouchTarget
import dev.mirutime.uikit.isLargeText
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

// Chapter jump position follows the visible reading order, not the source
// chapter index: the list may be filtered and/or newest-first.
internal fun chapterJumpPosition(visible: List<BookChapter>, chapterIndex: Int): Int =
    visible.indexOfFirst { it.index == chapterIndex }

// Toggle helper: add/remove one index (pure, no composable state).
internal fun toggleIn(set: Set<Int>, index: Int): Set<Int> =
    if (index in set) set - index else set + index

// A bulk pass that is still running. The bar is disabled and shows the
// matching label until the enqueue/translate call returns.
internal enum class BulkAction(val labelRes: Int) {
    Translate(R.string.loading_translating),
    Download(R.string.loading_adding_to_queue),
}

// Selection bar: count + select all/clear, then every bulk action as a
// labeled compact rectangle (no icon-only row). Delete translations sits
// right after Translate; both deletes are disabled when nothing selected
// actually owns translations/downloads. Close exits selection mode.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionBar(
    selectionCount: Int,
    allSelected: Boolean,
    translationConfigured: Boolean,
    downloadAvailable: Boolean,
    translatedCount: Int,
    downloadedCount: Int,
    busy: BulkAction?,
    onSelectAll: () -> Unit,
    onTranslate: () -> Unit,
    onMarkRead: (Boolean) -> Unit,
    onDeleteTranslations: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteDownloads: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiruRadius.Control))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiruIconButton(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.lib_selection_close),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                iconSize = MiruSize.IconCompact,
                onClick = onClose,
            )
            Text(
                text = if (selectionCount == 0) {
                    stringResource(R.string.book_select_chapters)
                } else {
                    pluralStringResource(
                        R.plurals.plural_selected_chapters,
                        selectionCount,
                        selectionCount,
                    )
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (busy != null) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(busy.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onSelectAll, enabled = busy == null && !allSelected) {
                Text(text = stringResource(R.string.lib_select_all))
            }
            TextButton(onClick = onClear, enabled = busy == null && selectionCount > 0) {
                Text(text = stringResource(R.string.book_sel_clear))
            }
        }
        // Compact action strip: never wrap. On narrow screens the user
        // scrolls horizontally instead of growing the bar vertically.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = MiruSpace.S, end = MiruSpace.S, bottom = MiruSpace.S),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            MiruCompactButton(
                icon = Icons.Filled.Translate,
                label = stringResource(R.string.book_sel_action_translate),
                ink = if (translationConfigured) extra.accent else extra.muted,
                enabled = busy == null && selectionCount > 0,
                onClick = onTranslate,
            )
            MiruCompactButton(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.book_sel_action_delete_translations),
                ink = MaterialTheme.colorScheme.error,
                enabled = busy == null && selectionCount > 0 && translatedCount > 0,
                onClick = onDeleteTranslations,
            )
            MiruCompactButton(
                icon = Icons.Filled.Download,
                label = stringResource(R.string.book_sel_action_download),
                ink = if (downloadAvailable) extra.accent else extra.muted,
                enabled = busy == null && selectionCount > 0,
                onClick = onDownloadSelected,
            )
            MiruCompactButton(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.book_sel_action_delete_downloads),
                ink = MaterialTheme.colorScheme.error,
                enabled = busy == null && selectionCount > 0 && downloadedCount > 0,
                onClick = onDeleteDownloads,
            )
            MiruCompactButton(
                icon = Icons.Filled.Check,
                label = stringResource(R.string.book_sel_action_mark_read),
                ink = MaterialTheme.colorScheme.primary,
                enabled = busy == null && selectionCount > 0,
                onClick = { onMarkRead(true) },
            )
            MiruCompactButton(
                icon = Icons.Filled.RemoveDone,
                label = stringResource(R.string.book_sel_action_mark_unread),
                ink = MaterialTheme.colorScheme.onSurfaceVariant,
                enabled = busy == null && selectionCount > 0,
                onClick = { onMarkRead(false) },
            )
        }
    }
}

// Chapter row: number + title, at most two trailing glyphs — translated
// (accent) and downloaded. Read state is carried by a dimmed title, never by
// a third icon; a pending/loading translation shows a spinner or retry in the
// translated slot but leaves no permanent mark. Long-press starts selection,
// like in the library; a tap in selection mode toggles the row.
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChapterRow(
    chapter: BookChapter,
    title: String,
    isLastRead: Boolean,
    translationStatus: ChapterTranslationStatus?,
    videoDownload: VideoDownloadItem?,
    isVideoBook: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRetryTranslation: () -> Unit,
    onVideoDownloadAction: (VideoDownloadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val translationLoading = stringResource(R.string.book_ch_translation_loading)
    // 0 means the body/pages are not stored locally; show no size at all
    // rather than a fake estimate or "0 B".
    val sizeLabel = remember(chapter.sizeBytes) {
        chapter.sizeBytes.takeIf { it > 0L }?.let(::formatChapterSize)
    }
    // Video rows take their state from media3; text rows keep the existing
    // DownloadEngine queue state. The two never mix for one book.
    val videoState = videoDownload?.state
    val videoProgress = videoDownload?.progress ?: 0f
    val downloaded = if (isVideoBook) {
        videoState == VideoDownloadState.Completed
    } else {
        chapter.downloaded
    }
    val queuedState = if (isVideoBook) {
        when (videoState) {
            VideoDownloadState.Queued -> DownloadState.QUEUED
            VideoDownloadState.Downloading -> DownloadState.RUNNING
            VideoDownloadState.Paused -> DownloadState.PAUSED
            VideoDownloadState.Failed -> DownloadState.FAILED
            VideoDownloadState.Completed, null -> null
        }
    } else {
        DownloadState.from(chapter.downloadState)
    }
    val progress = if (isVideoBook) videoProgress else chapter.downloadProgress
    var expanded by rememberSaveable(chapter.index) { mutableStateOf(false) }
    var canExpand by remember(chapter.index) { mutableStateOf(false) }
    val queueLabel = if (queuedState != null && !downloaded) {
        when (queuedState) {
            DownloadState.QUEUED -> stringResource(R.string.book_ch_dl_queued)
            DownloadState.RUNNING -> stringResource(
                R.string.book_ch_dl_running_fmt,
                (progress * 100).roundToInt(),
            )
            DownloadState.PAUSED -> stringResource(
                R.string.book_ch_dl_paused_fmt,
                (progress * 100).roundToInt(),
            )
            DownloadState.FAILED -> stringResource(R.string.book_ch_dl_failed)
            DownloadState.NEEDS_SOURCE_ACTION -> stringResource(R.string.book_ch_dl_source_action)
            DownloadState.WAITING_NETWORK -> stringResource(R.string.book_ch_dl_waiting_network)
            else -> null
        }
    } else {
        null
    }
    val meta = buildList {
        if (!isLastRead && chapter.isLast) add(stringResource(R.string.book_ch_last_chapter_badge))
        if (!isLastRead && chapter.isRead) add(stringResource(R.string.book_ch_read_badge))
        if (queueLabel != null) add(queueLabel)
        if (sizeLabel != null) add(sizeLabel)
    }
    val metaColor = if (queuedState == DownloadState.FAILED ||
        queuedState == DownloadState.NEEDS_SOURCE_ACTION
    ) {
        MaterialTheme.colorScheme.error
    } else {
        extra.muted
    }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MiruSize.RowDense)
                .clip(RoundedCornerShape(MiruRadius.Control))
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        isLastRead -> extra.accent.copy(alpha = 0.10f)
                        else -> Color.Transparent
                    },
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(start = MiruSpace.S, end = ChapterRowEndGutter)
                .padding(vertical = MiruSpace.Xxs),
        ) {
            if (selectionActive) {
                Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else extra.muted,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = chapter.index.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = extra.muted,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 30.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(
                        if (chapter.isRead && !isLastRead && !selected) 0.72f else 1f,
                    ),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chapter.isRead) {
                            extra.muted
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            if (!canExpand && layout.hasVisualOverflow) canExpand = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (canExpand) {
                        Box(
                            modifier = Modifier
                                .compactTouchTarget(MiruSize.Control)
                                .size(MiruSize.Control)
                                .clip(RoundedCornerShape(MiruRadius.Split))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.book_ch_expand_title),
                                    onClick = { expanded = !expanded },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (expanded) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = stringResource(R.string.book_ch_expand_title),
                                tint = extra.muted,
                                modifier = Modifier.size(MiruSize.IconSmall),
                            )
                        }
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        style = MiruCardType.Meta,
                        color = metaColor,
                        maxLines = if (isLargeText()) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLastRead && !selectionActive) {
                    Text(
                        text = stringResource(R.string.book_ch_current_badge),
                        style = MiruCardType.Meta,
                        color = extra.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(MiruRadius.Split))
                            .background(extra.accent.copy(alpha = 0.16f))
                            .padding(horizontal = MiruSpace.Xxs, vertical = 1.dp),
                    )
                }
                if (isVideoBook && !selectionActive) {
                    videoDownloadActions(videoState).forEach { action ->
                        when (action) {
                            VideoDownloadAction.Pause -> MiruIconButton(
                                icon = Icons.Filled.Pause,
                                contentDescription = stringResource(R.string.dl_action_pause),
                                tint = extra.muted,
                                iconSize = MiruSize.IconCompact,
                                contentAlignment = Alignment.CenterEnd,
                                onClick = { onVideoDownloadAction(action) },
                            )
                            VideoDownloadAction.Resume -> MiruIconButton(
                                icon = Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.dl_action_resume),
                                tint = extra.accent,
                                iconSize = MiruSize.IconCompact,
                                contentAlignment = Alignment.CenterEnd,
                                onClick = { onVideoDownloadAction(action) },
                            )
                            VideoDownloadAction.Cancel -> MiruIconButton(
                                icon = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.dl_action_cancel),
                                tint = MaterialTheme.colorScheme.error,
                                iconSize = MiruSize.IconCompact,
                                contentAlignment = Alignment.CenterEnd,
                                onClick = { onVideoDownloadAction(action) },
                            )
                        }
                    }
                }
                if (translationStatus == ChapterTranslationStatus.Loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = extra.accent,
                        modifier = Modifier
                            .size(14.dp)
                            .semantics { contentDescription = translationLoading },
                    )
                } else if (
                    translationStatus == ChapterTranslationStatus.Failed ||
                    chapter.translationState.equals("FAILED", ignoreCase = true)
                ) {
                    Box(
                        modifier = Modifier
                            .compactTouchTarget(MiruSize.Control)
                            .size(MiruSize.Control)
                            .clip(RoundedCornerShape(MiruRadius.Split))
                            .clickable(role = Role.Button, onClick = onRetryTranslation),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.book_ch_retry_translation),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else if (chapter.translated ||
                    translationStatus == ChapterTranslationStatus.Ready
                ) {
                    Icon(
                        imageVector = Icons.Filled.Translate,
                        contentDescription = stringResource(R.string.book_ch_cd_translated),
                        tint = extra.accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
                if (queuedState == DownloadState.RUNNING) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        strokeWidth = 2.dp,
                        color = extra.accent,
                        modifier = Modifier.size(14.dp),
                    )
                } else if (downloaded) {
                    Box(
                        modifier = Modifier.size(MiruSize.IconSlot),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = stringResource(R.string.book_ch_cd_downloaded),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        )
    }
}

// READER NOTES: ONE card — header, hairline, then the
// reviews from the source separated by hairlines; long texts collapse behind
// the shared inline "Read more". The per-review translate icon is a stub
// toast; FULL translation mode would flip the flags in the data layer — the
// closing note says so. An empty list is a normal state (plugins may ship no
// reviews) and gets a quiet line inside the same card.
@Composable
internal fun ReaderNotesSection(
    reviews: List<BookReview>,
    onTranslate: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Column {
        if (reviews.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MiruSpace.L),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(MiruRadius.Control))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(MiruRadius.Control),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        tint = extra.muted,
                        modifier = Modifier.size(MiruSize.IconCompact),
                    )
                }
                Spacer(modifier = Modifier.height(MiruSpace.S))
                Text(
                    text = stringResource(R.string.book_reviews_empty),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MiruSpace.Xxs))
                Text(
                    text = stringResource(R.string.book_section_reviews_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            reviews.forEachIndexed { index, review ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = MiruSpace.M),
                    )
                }
                ReviewItem(review = review, onTranslate = onTranslate)
            }
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Text(
                text = stringResource(R.string.book_reviews_auto_note),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }
}

@Composable
private fun ReviewItem(review: BookReview, onTranslate: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                ) {
                    Text(
                        text = review.author,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (review.rating > 0f) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = stringResource(
                                R.string.book_review_rating_cd_fmt,
                                formatRating(review.rating),
                            ),
                            tint = extra.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = formatRating(review.rating),
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.accent,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = review.dateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    maxLines = 1,
                )
            }
            if (review.translated) {
                Text(
                    text = stringResource(R.string.book_review_translated),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    modifier = Modifier.padding(start = MiruSpace.S),
                )
            } else {
                MiruIconButton(
                    icon = Icons.Filled.Translate,
                    contentDescription = stringResource(R.string.book_review_translate_cd),
                    tint = extra.accent,
                    iconSize = MiruSize.IconCompact,
                    onClick = onTranslate,
                )
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        ExpandableBody(text = review.text, collapsedLines = 4)
    }
}

// "4.5" / "5" from a float, no trailing zeros (stat strip + review stars).
internal fun formatRating(value: Float): String =
    String.format(Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')

// Honest sub-percent progress: 5/1432 must read 0.35%, not 0%.
internal fun formatPercent(read: Int, total: Int): String {
    if (read <= 0 || total <= 0) return "0"
    val value = read * 100.0 / total
    return when {
        value < 1.0 -> String.format(Locale.US, "%.2f", value)
        value < 10.0 -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.0f", value)
    }
}

// Compact chapter size for the row's secondary line: B under 1 KiB, one
// decimal below 10 KB/MB, whole numbers above. 0/unknown returns an empty
// string and the caller never renders it.
internal fun formatChapterSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes < kb -> "$bytes B"
        bytes < mb -> {
            val value = bytes / kb
            if (value < 10) String.format(Locale.US, "%.1f KB", value)
            else "${value.roundToInt()} KB"
        }
        else -> {
            val value = bytes / mb
            if (value < 10) String.format(Locale.US, "%.1f MB", value)
            else "${value.roundToInt()} MB"
        }
    }
}

// A failed destructive action: message + the exact action to rerun. Kept in
// screen state (not a toast) until retry succeeds or the user dismisses it.
internal data class ChapterDeleteFailure(
    val message: String,
    val retry: () -> Unit,
)

// Honest outcome of a destructive chapter action: only a positive count with
// zero failures is a success that may clear the user's selection. A partial
// delete keeps the selection and offers retry for the remaining chapters.
internal enum class ChapterDeleteOutcome { Success, Nothing, Failed }

internal fun chapterDeleteOutcome(deleted: Int?, failed: Int = 0): ChapterDeleteOutcome = when {
    deleted == null -> ChapterDeleteOutcome.Failed
    failed > 0 -> ChapterDeleteOutcome.Failed
    deleted > 0 -> ChapterDeleteOutcome.Success
    else -> ChapterDeleteOutcome.Nothing
}

// One explicit chapter refresh pass for the page: a typed failure keeps the
// rows already on screen (a failed source must never read as "No chapters")
// and names the class for the log; only a successful answer may replace the
// list, including a genuinely empty one.
internal data class ChapterRefreshView(
    val rows: List<BookChapter>,
    val failure: PluginError?,
) {
    val ok: Boolean get() = failure == null
    val errorClass: String? get() = failure?.let(::pluginErrorClass)
}

// The chapters section renders for stored rows and for a successful empty
// answer (the real empty state); a typed failure with no rows is replaced by
// PluginErrorState instead.
internal fun showChaptersSection(
    chapters: List<BookChapter>,
    failure: PluginErrorReport?,
): Boolean = chapters.isNotEmpty() || failure == null

internal fun chapterRefreshView(
    current: List<BookChapter>,
    result: Outcome<List<BookChapter>>,
): ChapterRefreshView = when (result) {
    is Outcome.Ok -> ChapterRefreshView(rows = result.value, failure = null)
    is Outcome.Err -> ChapterRefreshView(
        rows = current,
        failure = result.error as? PluginError
            ?: PluginError.ScriptError(result.error::class.simpleName ?: "chapters"),
    )
}

// Opening a book: persisted rows need no source call; only an empty store asks
// the plugin, and its typed failure must reach the page rather than read as an
// empty chapter list. Unexpected provider exceptions become a ScriptError.
internal suspend fun loadInitialChapters(
    bookUrl: String,
    cached: suspend (String) -> List<BookChapter>?,
    remote: suspend (String) -> Outcome<List<BookChapter>>,
): Outcome<List<BookChapter>> {
    val stored = try {
        cached(bookUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    if (stored != null) return Outcome.Ok(stored)
    return try {
        remote(bookUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(PluginError.ScriptError(e::class.simpleName ?: "chapters"))
    }
}

// Log field only: HTTP <code> for a status failure, otherwise the class name.
internal fun pluginErrorClass(error: PluginError): String = when (error) {
    is PluginError.Network -> error.status?.let { "HTTP $it" } ?: "Network"
    is PluginError.CfChallengeRequired -> "CfChallengeRequired"
    is PluginError.ResponseTooLarge -> "ResponseTooLarge"
    is PluginError.UnsupportedApi -> "UnsupportedApi"
    is PluginError.ScriptError -> "ScriptError"
    is PluginError.DnsFailure -> "DnsFailure"
    is PluginError.UserMessage -> "UserMessage"
}

