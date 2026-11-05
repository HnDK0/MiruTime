package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSlider
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruSplitProgress
import dev.mirutime.feature.reader.R
import dev.mirutime.feature.tts.R as TtsR
import dev.mirutime.feature.tts.TtsSleepTimerState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.ui.ttsSleepTimerDescription
import dev.mirutime.feature.tts.ui.ttsSleepTimerLabel
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun ReaderChrome(
    title: String,
    bookTitle: String,
    chapterIndex: Int,
    chapterCount: Int,
    chapterPercent: Int,
    canPrev: Boolean,
    canNext: Boolean,
    canOpenWebView: Boolean,
    ttsStatus: TtsStatus,
    sleepTimer: StateFlow<TtsSleepTimerState?>,
    ttsEnabled: Boolean = true,
    manga: Boolean = false,
    mangaPage: Int = 0,
    mangaPageCount: Int = 0,
    onMangaPageChange: (Int) -> Unit = {},
    onMangaPageChangeFinished: () -> Unit = {},
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenWebView: () -> Unit,
    onTts: () -> Unit,
    onTranslation: () -> Unit,
    onStyle: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    ttsMiniPlayer: (@Composable () -> Unit)? = null,
) {
    val ttsActive = ttsStatus == TtsStatus.Playing || ttsStatus == TtsStatus.Loading
    val ttsSession = ttsActive || ttsStatus == TtsStatus.Paused
    val ttsLabel = when {
        ttsStatus == TtsStatus.Loading -> stringResource(TtsR.string.rd_tts_loading)
        ttsActive -> stringResource(TtsR.string.rd_tts_playing)
        ttsStatus == TtsStatus.Paused -> stringResource(TtsR.string.rd_tts_paused)
        else -> stringResource(R.string.rd_tts)
    }
    Box(modifier = modifier.fillMaxSize()) {
        // A tap outside the bars closes the menu. This layer lives inside the
        // chrome so it is guaranteed above the page and below both surfaces.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        waitForUpOrCancellation()?.consume()
                        onDismiss()
                    }
                },
        )
        Surface(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(MiruRadius.Plate),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = MiruSize.Row)
                        .padding(horizontal = 8.dp),
                ) {
                    ReaderIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.header_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = onBack,
                    )
                    Column(Modifier.weight(1f)) {
                        // NoveLA order: book above, chapter below.
                        if (bookTitle.isNotBlank()) {
                            Text(
                                text = bookTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ReaderIconButton(
                        icon = Icons.Outlined.Public,
                        contentDescription = stringResource(R.string.rd_open_web),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        enabled = canOpenWebView,
                        onClick = onOpenWebView,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((chapterPercent / 100f).coerceIn(0f, 1f))
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .zIndex(1f)
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 18.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(MiruRadius.Sheet),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                if (ttsMiniPlayer != null) {
                    ttsMiniPlayer()
                    Spacer(Modifier.height(8.dp))
                }
                if (manga && mangaPageCount > 1) {
                    MangaPageScrubber(
                        page = mangaPage,
                        pageCount = mangaPageCount,
                        onPageChange = onMangaPageChange,
                        onPageChangeFinished = onMangaPageChangeFinished,
                    )
                    Spacer(Modifier.height(MiruSpace.S))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ReaderIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.rd_prev_chapter),
                        tint = MaterialTheme.colorScheme.onSurface,
                        enabled = canPrev,
                        onClick = onPrev,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ReaderChapterProgress(
                            chapterIndex = chapterIndex,
                            chapterCount = chapterCount,
                            chapterPercent = chapterPercent,
                            sleepTimer = sleepTimer,
                        )
                    }
                    ReaderIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.rd_next_chapter),
                        tint = MaterialTheme.colorScheme.onSurface,
                        enabled = canNext,
                        onClick = onNext,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ttsEnabled) {
                        // One entry for idle and live sessions alike: it opens
                        // the player sheet, and the label/tint carry the state.
                        DockAction(
                            icon = Icons.Outlined.RecordVoiceOver,
                            label = ttsLabel,
                            active = ttsSession,
                            onClick = onTts,
                        )
                    }
                    if (!manga) {
                        DockAction(
                            icon = Icons.Outlined.Translate,
                            label = stringResource(R.string.rd_translation),
                            onClick = onTranslation,
                        )
                    }
                    DockAction(
                        icon = if (manga) Icons.Outlined.Tune else Icons.Outlined.FormatSize,
                        label = if (manga) {
                            stringResource(R.string.rd_settings)
                        } else {
                            stringResource(R.string.rd_style)
                        },
                        onClick = onStyle,
                    )
                    if (!manga) {
                        DockAction(
                            icon = Icons.Outlined.MoreVert,
                            label = stringResource(R.string.rd_more),
                            onClick = onMore,
                        )
                    }
                }
            }
        }
    }
}

// A pill above the chapter dock: page text plus a full-width 48dp slider. It
// exists only while the manga chrome is open, and the drag only seeks the
// viewer; the reading anchor is saved once when the drag finishes.
@Composable
private fun MangaPageScrubber(
    page: Int,
    pageCount: Int,
    onPageChange: (Int) -> Unit,
    onPageChangeFinished: () -> Unit,
) {
    val label = stringResource(R.string.rd_manga_page_fmt, page + 1, pageCount)
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = MiruSize.Touch)
                .padding(start = MiruSpace.L, end = MiruSpace.S),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(MiruSpace.M))
            MiruSlider(
                value = (page + 1).toFloat(),
                onValueChange = { value ->
                    onPageChange(mangaScrubberPage(value - 1f, pageCount))
                },
                valueRange = 1f..pageCount.toFloat(),
                steps = (pageCount - 2).coerceAtLeast(0),
                onValueChangeFinished = onPageChangeFinished,
                contentDescription = label,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DockAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val iconColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val labelColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .width(72.dp)
            // Keep the interactive box at the 48dp touch minimum (D2-03).
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(MiruRadius.Large))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ReaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    MiruIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        enabled = enabled,
        tint = tint,
    )
}

/**
 * Chapter progress in the bottom dock: a card-style split pill (chapters X/Y)
 * with the percent under it. While a sleep timer runs the caption shows its
 * countdown instead of the percent. Width follows the font scale, exactly like
 * the book cards, or the split numbers clip at 200% text.
 */
@Composable
private fun ReaderChapterProgress(
    chapterIndex: Int,
    chapterCount: Int,
    chapterPercent: Int,
    sleepTimer: StateFlow<TtsSleepTimerState?>,
) {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MiruSplitProgress(
            read = chapterIndex,
            total = chapterCount,
            modifier = Modifier
                .width(MiruSize.SplitProgress * fontScale)
                .height(MiruSize.SplitProgressHeight * fontScale),
            contentDescription = stringResource(
                R.string.rd_chapter_fmt,
                chapterIndex,
                chapterCount,
            ),
        )
        ReaderProgressCaption(
            sleepTimer = sleepTimer,
            chapterPercent = chapterPercent,
        )
    }
}

@Composable
private fun ReaderProgressCaption(
    sleepTimer: StateFlow<TtsSleepTimerState?>,
    chapterPercent: Int,
) {
    val timer by sleepTimer.collectAsState()
    val timerState = timer
    val text = if (timerState != null) {
        ttsSleepTimerLabel(timerState)
    } else {
        stringResource(R.string.rd_percent_fmt, chapterPercent)
    }
    val description = if (timerState != null) {
        ttsSleepTimerDescription(timerState)
    } else {
        null
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (description != null) {
            Modifier.clearAndSetSemantics { contentDescription = description }
        } else {
            Modifier
        },
    )
}
