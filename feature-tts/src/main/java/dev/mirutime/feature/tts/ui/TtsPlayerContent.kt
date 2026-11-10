package dev.mirutime.feature.tts.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.tts.R
import dev.mirutime.feature.tts.TtsFloatingMaxWidth
import dev.mirutime.feature.tts.TtsFloatingMinOpacity
import dev.mirutime.feature.tts.TtsFloatingRangeMargin
import dev.mirutime.feature.tts.floatingWidthRange
import dev.mirutime.feature.tts.clampFloatingPanelWidth
import dev.mirutime.feature.tts.pinchPanelWidth
import dev.mirutime.feature.tts.TtsSettings
import dev.mirutime.feature.tts.TtsSleepTimerState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.feature.tts.formatTtsDuration
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSlider
import dev.mirutime.uikit.compactTouchTarget
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Everything the rich player renders, independent of where it lives. */
data class TtsPlayerUi(
    val status: TtsStatus = TtsStatus.Idle,
    val chapterTitle: String = "",
    val sentencePreview: String? = null,
    val paragraphText: String? = null,
    val counterpartText: String? = null,
    val spokenIsTranslation: Boolean = false,
    val ttsHighlight: TtsHighlight? = null,
    val sentenceHighlightStart: Int = 0,
    val sentenceHighlightEnd: Int = 0,
    val sentenceNumber: Int = 0,
    val chunkCount: Int = 0,
    val chapterNumber: Int = 0,
    val chapterCount: Int = 0,
    val chapterPercent: Int = 0,
    val remainingSeconds: Int = 0,
    val atEnd: Boolean = false,
    val glowColorArgb: Long = 0L,
)

/**
 * The one rich TTS surface: transport row, actions, tuning strip and the
 * highlighted paragraph plate. The in-app floating player and the system
 * overlay both render exactly this, so a player that leaves the app keeps the
 * text, the glow and the appearance controls instead of collapsing into a bare
 * transport.
 *
 * Position, size and drag stay with the caller: only the surface body lives
 * here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsPlayerContent(
    ui: TtsPlayerUi,
    settings: TtsSettings,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onStartHere: () -> Unit,
    onFocus: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onOpenSettings: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    sleepTimer: StateFlow<TtsSleepTimerState?>? = null,
    onSleepTimerClick: (() -> Unit)? = null,
    /** Panel width in dp. The range comes from the SCREEN, never from the panel:
     *  a range that follows the panel width makes the slider chase its own max. */
    panelWidth: Float = settings.floatingPanelWidth,
    /** Persists and applies the settled width once the drag ends. */
    onPanelWidthChange: (Float) -> Unit = {},
    /** Screen width in dp; the allowed panel range is derived from it here, so a
     *  caller cannot pass the panel's own width back in as the range. */
    screenWidthDp: Float = TtsFloatingMaxWidth,
    /** Screen edge margin in dp, matching the panel's own inset. */
    marginDp: Float = TtsFloatingRangeMargin,
    opacity: Float = settings.floatingOpacity,
    onOpacityChange: (Float) -> Unit = {},
    showText: Boolean = settings.floatingShowText,
    onShowTextChange: (Boolean) -> Unit = {},
    glowMode: String = settings.floatingGlow,
    onGlowModeChange: (String) -> Unit = {},
    paragraphMode: String = settings.floatingParagraphMode,
    onParagraphModeChange: (String) -> Unit = {},
    /** Persists the settled appearance values after a slider drag. */
    onAppearanceCommit: () -> Unit = {},
) {
    var controlsHidden by rememberSaveable { mutableStateOf(false) }
    var showActions by rememberSaveable { mutableStateOf(false) }
    var showTuning by rememberSaveable { mutableStateOf(false) }
    // Pending first tap of a double tap on the text plate.
    var lastTapAt by remember { mutableLongStateOf(0L) }
    // Slider drafts: the track follows the finger, the panel changes on release.
    var widthDraft by remember(panelWidth) { mutableFloatStateOf(panelWidth) }
    var opacityDraft by remember(opacity) { mutableFloatStateOf(opacity) }

    val playing = ui.status == TtsStatus.Playing || ui.status == TtsStatus.Loading
    val session = playing || ui.status == TtsStatus.Paused
    val progress = if (ui.chunkCount > 0) {
        (ui.sentenceNumber.toFloat() / ui.chunkCount).coerceIn(0f, 1f)
    } else {
        ui.chapterPercent / 100f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300),
        label = "floating-tts-progress",
    )
    val hasCounterpart = !ui.counterpartText.isNullOrBlank()
    val spokenText = ui.sentencePreview?.takeIf { it.isNotBlank() } ?: ui.paragraphText
    val paragraph = floatingParagraph(
        mode = paragraphMode,
        spokenText = spokenText,
        paragraphText = ui.paragraphText,
        counterpartText = ui.counterpartText,
        spokenIsTranslation = ui.spokenIsTranslation,
    )
    val primaryText = paragraph.primary
    val highlight = ui.ttsHighlight?.takeIf { it.rangeEnabled }
    val primaryHighlight: Pair<IntRange, Long>? = highlight?.let { active ->
        val range = when {
            paragraph.blockRange -> active.start..active.end
            paragraph.sentenceRange -> ui.sentenceHighlightStart..ui.sentenceHighlightEnd
            else -> null
        }
        range?.let { it to active.colorArgb }
    }
    val primaryAnnotated = remember(primaryText, primaryHighlight) {
        val text = primaryText.orEmpty()
        primaryHighlight?.let { (range, color) ->
            highlightedRange(text, range.first, range.last, color)
        } ?: AnnotatedString(text)
    }
    val secondaryText = paragraph.secondary
    val hasText = showText && (!primaryText.isNullOrBlank() || !secondaryText.isNullOrBlank())
    val showGlow = when (glowMode) {
        "on" -> hasText
        "off" -> false
        else -> hasText && playing
    }
    val glowColor = remember(ui.glowColorArgb) {
        Color(ui.glowColorArgb).copy(alpha = 1f)
    }

    val chapterLabel = if (ui.chapterNumber > 0 && ui.chapterCount > 0) {
        stringResource(R.string.rd_tts_chapter_number_fmt, ui.chapterNumber, ui.chapterCount)
    } else {
        ""
    }
    val timerLabel = formatTtsDuration(ui.remainingSeconds)
    val timerDescription = stringResource(R.string.rd_tts_time_remaining_fmt, timerLabel)
    val chapterDescription = if (chapterLabel.isNotEmpty()) {
        stringResource(R.string.rd_tts_chapter_position_fmt, ui.chapterNumber, ui.chapterCount)
    } else {
        ""
    }

    Box(modifier = modifier) {
        // The panel's own width is NOT a range source: it changes while the
        // slider drags. Only clamp the value to the screen-derived range.
        val widthRange = floatingWidthRange(screenWidthDp, marginDp)
        val shownPanelWidth = clampFloatingPanelWidth(panelWidth, widthRange)
        // The pinch gesture block is keyed on Unit, so it keeps the first
        // composition's values. Read the live ones through updated state, or a
        // pinch would write the width range and width captured at open time.
        val currentWidthRange by rememberUpdatedState(widthRange)
        val currentPanelWidth by rememberUpdatedState(panelWidth)
        val currentOnPanelWidthChange by rememberUpdatedState(onPanelWidthChange)

        Surface(
            shape = RoundedCornerShape(MiruRadius.Plate),
            color = if (!controlsHidden || !hasText) {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = opacity)
            } else {
                Color.Transparent
            },
            tonalElevation = if (controlsHidden) 0.dp else 3.dp,
            shadowElevation = if (controlsHidden) 0.dp else 14.dp,
        ) {
        Column(Modifier.padding(MiruSpace.S)) {
            if (controlsHidden) {
                if (!hasText) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MiruRadius.Inner))
                            .clickable(role = Role.Button) { controlsHidden = false }
                            .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(MiruSpace.Xs))
                        Text(
                            text = ui.chapterTitle.ifBlank { stringResource(R.string.tts_ui_label) },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.rd_tts_floating_show_menu),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                FloatingControlRow(
                    chapterLabel = chapterLabel,
                    chapterDescription = chapterDescription,
                    progress = animatedProgress,
                    timerChip = {
                        TtsFloatingTimerChip(
                            sleepTimer = sleepTimer,
                            listeningLabel = timerLabel,
                            listeningDescription = timerDescription,
                            onSleepTimerClick = onSleepTimerClick,
                        )
                    },
                    playing = playing,
                    session = session,
                    canNext = session && !ui.atEnd,
                    actionsExpanded = showActions,
                    onToggleActions = {
                        showActions = !showActions
                        if (!showActions) showTuning = false
                    },
                    onCollapse = onCollapse,
                    onPreviousChunk = onPreviousChunk,
                    onToggle = onToggle,
                    onNextChunk = onNextChunk,
                )

                if (showActions) {
                    Spacer(Modifier.height(MiruSpace.Xs))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FloatingIconAction(
                            icon = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.rd_tts_stop),
                            enabled = session,
                            onClick = onStop,
                        )
                        FloatingIconAction(
                            icon = Icons.Filled.TouchApp,
                            contentDescription = stringResource(R.string.rd_tts_start_here),
                            onClick = onStartHere,
                        )
                        FloatingIconAction(
                            icon = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.rd_tts_focus),
                            onClick = onFocus,
                        )
                        FloatingIconAction(
                            icon = Icons.AutoMirrored.Rounded.TextSnippet,
                            contentDescription = stringResource(
                                if (showText) {
                                    R.string.rd_tts_floating_hide_text
                                } else {
                                    R.string.rd_tts_floating_show_text
                                },
                            ),
                            selected = showText,
                            onClick = {
                                onShowTextChange(!showText)
                                onAppearanceCommit()
                            },
                        )
                        FloatingIconAction(
                            icon = Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.rd_tts_floating_tune),
                            selected = showTuning,
                            onClick = {
                                showTuning = !showTuning
                                if (showTuning) showActions = true
                            },
                        )
                        FloatingIconAction(
                            icon = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.rd_tts_floating_settings),
                            onClick = onOpenSettings,
                        )
                    }

                    if (showTuning) {
                        Spacer(Modifier.height(MiruSpace.Xs))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(MiruRadius.Inner))
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                                        alpha = 0.55f,
                                    ),
                                )
                                .padding(MiruSpace.S),
                        ) {
                            FloatingTuneRow(
                                label = stringResource(R.string.rd_tts_floating_opacity),
                                value = "${(opacityDraft * 100).roundToInt()}%",
                            ) {
                                MiruSlider(
                                    value = opacityDraft,
                                    onValueChange = { opacityDraft = it },
                                    onValueChangeFinished = {
                                        onOpacityChange(opacityDraft)
                                        onAppearanceCommit()
                                    },
                                    valueRange = TtsFloatingMinOpacity..1f,
                                    contentDescription = stringResource(
                                        R.string.rd_tts_floating_opacity,
                                    ),
                                    compact = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            FloatingTuneRow(
                                label = stringResource(R.string.rd_tts_floating_width),
                                value = stringResource(
                                    R.string.rd_tts_floating_width_value,
                                    widthDraft.roundToInt(),
                                ),
                            ) {
                                MiruSlider(
                                    value = widthDraft,
                                    // The panel resizes on release only: resizing it
                                    // during the drag moved the track under the finger.
                                    onValueChange = { widthDraft = it },
                                    onValueChangeFinished = {
                                        onPanelWidthChange(widthDraft)
                                        onAppearanceCommit()
                                    },
                                    valueRange = widthRange,
                                    contentDescription = stringResource(
                                        R.string.rd_tts_floating_width,
                                    ),
                                    compact = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            FloatingChipRow(
                                label = stringResource(R.string.rd_tts_floating_glow),
                                options = listOf(
                                    "auto" to stringResource(R.string.rd_tts_glow_auto),
                                    "on" to stringResource(R.string.rd_tts_glow_on),
                                    "off" to stringResource(R.string.rd_tts_glow_off),
                                ),
                                selected = glowMode,
                                onSelect = {
                                    onGlowModeChange(it)
                                    onAppearanceCommit()
                                },
                            )
                            if (hasCounterpart) {
                                FloatingChipRow(
                                    label = stringResource(R.string.rd_tts_floating_paragraph),
                                    options = listOf(
                                        "tts" to stringResource(R.string.rd_tts_paragraph_spoken),
                                        "both" to stringResource(R.string.rd_tts_paragraph_both),
                                        "inverse" to stringResource(
                                            R.string.rd_tts_paragraph_translation,
                                        ),
                                    ),
                                    selected = paragraphMode,
                                    onSelect = {
                                        onParagraphModeChange(it)
                                        onAppearanceCommit()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (hasText) {
                if (!controlsHidden) {
                    Spacer(Modifier.height(MiruSpace.Xs))
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MiruSpace.L)
                            .clickable(role = Role.Button) { controlsHidden = false },
                    ) {
                        Box(
                            Modifier
                                .width(MiruSize.Chip)
                                .height(MiruSpace.Xxs)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                ),
                        )
                    }
                }
                val textShape = RoundedCornerShape(MiruRadius.Inner)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                        alpha = opacity,
                    ),
                    shape = textShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (showGlow) {
                                Modifier.shadow(
                                    elevation = MiruSpace.M,
                                    shape = textShape,
                                    ambientColor = glowColor,
                                    spotColor = glowColor,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (showGlow) {
                                Modifier.border(1.5.dp, glowColor, textShape)
                            } else {
                                Modifier
                            },
                        )
                        // Pinch resizes the panel, a double tap or a long press
                        // hides the controls. Same gesture map as NoveLA: one
                        // pointer stream, branched by how many fingers are down.
                        .pointerInput(Unit) {
                            val toggleControls = {
                                controlsHidden = !controlsHidden
                                if (controlsHidden) {
                                    showActions = false
                                    showTuning = false
                                }
                            }
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                var pinched = false
                                var longPressed = false
                                var previousSpan = 0f
                                var lastPinchAt = 0L
                                var event: PointerEvent
                                do {
                                    event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        pinched = true
                                        val span = (pressed[0].position - pressed[1].position)
                                            .getDistance()
                                        if (previousSpan > 0f && span > 0f) {
                                            val now = event.changes.first().uptimeMillis
                                            if (now - lastPinchAt > 50) {
                                                lastPinchAt = now
                                                // Multiplier applies to the stored width,
                                                // not to the clamped display value: a pinch
                                                // must also be able to grow past it.
                                                val next = pinchPanelWidth(
                                                    current = currentPanelWidth,
                                                    previousSpan = previousSpan,
                                                    span = span,
                                                    range = currentWidthRange,
                                                )
                                                if (next != currentPanelWidth) {
                                                    currentOnPanelWidthChange(next)
                                                }
                                            }
                                        }
                                        previousSpan = span
                                        event.changes.forEach { it.consume() }
                                    } else if (pressed.isNotEmpty() && !pinched &&
                                        !longPressed &&
                                        event.changes.first().uptimeMillis - first.uptimeMillis >=
                                        viewConfiguration.longPressTimeoutMillis
                                    ) {
                                        longPressed = true
                                        toggleControls()
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!pinched && !longPressed) {
                                    val release = event.changes.first().uptimeMillis
                                    val moved = (event.changes.first().position - first.position)
                                        .getDistance()
                                    if (moved <= viewConfiguration.touchSlop) {
                                        val now = release
                                        if (lastTapAt != 0L &&
                                            now - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis
                                        ) {
                                            lastTapAt = 0L
                                            toggleControls()
                                        } else {
                                            lastTapAt = now
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = MiruSpace.M,
                            vertical = MiruSpace.S,
                        ),
                    ) {
                        if (!primaryText.isNullOrBlank()) {
                            Text(
                                text = primaryAnnotated,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!secondaryText.isNullOrBlank()) {
                            Spacer(Modifier.height(MiruSpace.Xs))
                            Text(
                                text = secondaryText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * One compact transport row: collapse, chapter badge, chapter progress, time
 * left and the transport itself. NoveLA's MiniPlayerControls keeps the same
 * order so the panel stays short; tuning stays behind "More".
 */
@Composable
private fun FloatingControlRow(
    chapterLabel: String,
    chapterDescription: String,
    progress: Float,
    timerChip: @Composable () -> Unit,
    playing: Boolean,
    session: Boolean,
    canNext: Boolean,
    actionsExpanded: Boolean,
    onToggleActions: () -> Unit,
    onCollapse: () -> Unit,
    onPreviousChunk: () -> Unit,
    onToggle: () -> Unit,
    onNextChunk: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // At the 220dp minimum a chapter badge would push the transport out of
        // bounds, so the always-visible timer and the controls win there.
        val showChapter = chapterLabel.isNotEmpty() &&
            maxWidth >= FloatingCompactTouch * 8
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FloatingCompactAction(
                icon = Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.rd_tts_floating_collapse),
                onClick = onCollapse,
            )
            if (showChapter) {
                Surface(
                    shape = RoundedCornerShape(MiruRadius.Badge),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = chapterDescription
                    },
                ) {
                    Text(
                        text = chapterLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(
                            horizontal = MiruSpace.Xs,
                            vertical = MiruSpace.Xxs,
                        ),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(MiruSpace.Xs)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            timerChip()
            FloatingCompactAction(
                icon = Icons.AutoMirrored.Rounded.NavigateBefore,
                contentDescription = stringResource(R.string.rd_tts_prev_chunk),
                enabled = session,
                onClick = onPreviousChunk,
            )
            FloatingCompactPlayPause(playing = playing, onClick = onToggle)
            FloatingCompactAction(
                icon = Icons.AutoMirrored.Rounded.NavigateNext,
                contentDescription = stringResource(R.string.rd_tts_next_chunk),
                enabled = canNext,
                onClick = onNextChunk,
            )
            FloatingCompactAction(
                icon = Icons.Filled.MoreHoriz,
                contentDescription = stringResource(
                    if (actionsExpanded) {
                        R.string.rd_tts_less_controls
                    } else {
                        R.string.rd_tts_more_controls
                    },
                ),
                selected = actionsExpanded,
                onClick = onToggleActions,
            )
        }
    }
}

@Composable
private fun FloatingTuneRow(
    label: String,
    value: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(MiruSize.Chip * 2),
        )
        content()
        Spacer(Modifier.width(MiruSpace.Xxs))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(MiruSize.Control),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloatingChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(MiruSize.Chip * 2),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
            verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
            modifier = Modifier.weight(1f),
        ) {
            options.forEach { (id, text) ->
                val active = id == selected
                Surface(
                    shape = RoundedCornerShape(MiruRadius.Badge),
                    color = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(MiruRadius.Badge))
                        .clickable(role = Role.RadioButton) { onSelect(id) },
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(
                            horizontal = MiruSpace.S,
                            vertical = MiruSpace.Xxs,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = FloatingCompactTouch,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MiruSize.Chip)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
                    },
                    CircleShape,
                ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Hit box for a plate control: the 24dp face plus the 8dp ring from the compact
 * ladder, capped at 32dp. A 48dp slot made the panel twice as tall as needed.
 */
internal val FloatingCompactTouch = compactTouchTarget(MiruSize.Chip)

@Composable
private fun FloatingCompactAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(FloatingCompactTouch)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MiruSize.Chip)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
                    },
                    CircleShape,
                ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun FloatingCompactPlayPause(playing: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(FloatingCompactTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MiruSize.Chip)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.rd_tts_action_pause else R.string.rd_tts_action_play,
                ),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
