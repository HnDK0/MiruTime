package dev.mirutime.feature.tts.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.tts.R
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.feature.tts.TtsFloatingMaxWidth
import dev.mirutime.feature.tts.TtsFloatingMinWidth
import dev.mirutime.feature.tts.TtsSettings
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.formatTtsDuration
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/** Text and the range that belongs to it for one floating paragraph mode. */
internal data class FloatingParagraph(
    val primary: String?,
    val secondary: String?,
    /** Highlight offsets index the whole block ([TtsHighlight.start]..end). */
    val blockRange: Boolean,
    /** Highlight offsets index the sentence preview. */
    val sentenceRange: Boolean,
)

/**
 * Resolves the floating player's text. "Translation" always means the
 * translated language: when TTS already speaks the translation the spoken
 * block is the translation and keeps its word range; otherwise it shows the
 * counterpart translation, whose offsets the spoken range does not describe.
 */
internal fun floatingParagraph(
    mode: String,
    spokenText: String?,
    paragraphText: String?,
    counterpartText: String?,
    spokenIsTranslation: Boolean,
): FloatingParagraph {
    val fullSpoken = paragraphText ?: spokenText
    val counterpart = counterpartText?.takeIf { it.isNotBlank() }
    return when (mode) {
        "inverse" -> if (spokenIsTranslation) {
            FloatingParagraph(
                primary = fullSpoken,
                secondary = null,
                blockRange = !paragraphText.isNullOrBlank(),
                sentenceRange = false,
            )
        } else {
            FloatingParagraph(
                // Keep the old fallback: a persisted inverse mode must not
                // blank the panel when the translation disappears.
                primary = counterpart ?: fullSpoken,
                secondary = null,
                blockRange = false,
                sentenceRange = false,
            )
        }
        "both" -> FloatingParagraph(
            primary = fullSpoken,
            secondary = counterpart?.takeIf { it != fullSpoken },
            blockRange = !paragraphText.isNullOrBlank(),
            sentenceRange = false,
        )
        else -> FloatingParagraph(
            primary = spokenText,
            secondary = null,
            blockRange = false,
            sentenceRange = true,
        )
    }
}

/**
 * Optional over-page TTS player. Deliberately in-app: no overlay permission,
 * no system window. The collapsed bubble and the expanded panel keep their own
 * bounded, persisted positions. The expanded panel also remembers its width,
 * opacity, text visibility, glow and paragraph mode across process death.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsFloatingPlayer(
    status: TtsStatus,
    chapterTitle: String,
    sentencePreview: String?,
    paragraphText: String?,
    counterpartText: String?,
    spokenIsTranslation: Boolean,
    ttsHighlight: TtsHighlight? = null,
    sentenceHighlightStart: Int = 0,
    sentenceHighlightEnd: Int = 0,
    sentenceNumber: Int,
    chunkCount: Int,
    chapterNumber: Int,
    chapterCount: Int,
    chapterPercent: Int,
    remainingSeconds: Int,
    atEnd: Boolean,
    glowColorArgb: Long,
    settings: TtsSettings,
    onSettingsChange: (TtsSettings) -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onStartHere: () -> Unit,
    onFocus: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var controlsHidden by rememberSaveable { mutableStateOf(false) }
    var showActions by rememberSaveable { mutableStateOf(false) }
    var showTuning by rememberSaveable { mutableStateOf(false) }
    var bubbleLeft by remember { mutableFloatStateOf(settings.floatingBubbleX) }
    var bubbleTop by remember { mutableFloatStateOf(settings.floatingBubbleY) }
    var panelLeft by remember { mutableFloatStateOf(settings.floatingPanelX) }
    var panelTop by remember { mutableFloatStateOf(settings.floatingPanelY) }
    var panelHeight by remember { mutableFloatStateOf(190f) }
    var panelWidth by remember { mutableFloatStateOf(settings.floatingPanelWidth) }
    var opacity by remember { mutableFloatStateOf(settings.floatingOpacity) }
    var showText by remember { mutableStateOf(settings.floatingShowText) }
    var glowMode by remember { mutableStateOf(settings.floatingGlow) }
    var paragraphMode by remember { mutableStateOf(settings.floatingParagraphMode) }

    val density = LocalDensity.current
    val bubbleSize = MiruSize.Button.value
    val margin = MiruSpace.M.value
    val playing = status == TtsStatus.Playing || status == TtsStatus.Loading
    val session = playing || status == TtsStatus.Paused
    val progress = if (chunkCount > 0) {
        (sentenceNumber.toFloat() / chunkCount).coerceIn(0f, 1f)
    } else {
        chapterPercent / 100f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300),
        label = "floating-tts-progress",
    )
    val hasCounterpart = !counterpartText.isNullOrBlank()
    val spokenText = sentencePreview?.takeIf { it.isNotBlank() } ?: paragraphText
    val paragraph = floatingParagraph(
        mode = paragraphMode,
        spokenText = spokenText,
        paragraphText = paragraphText,
        counterpartText = counterpartText,
        spokenIsTranslation = spokenIsTranslation,
    )
    val primaryText = paragraph.primary
    // The spoken range belongs to the sentence preview in "tts" mode and to
    // the whole block in "both" mode. "inverse" only has a range when it shows
    // the spoken translation itself.
    val highlight = ttsHighlight?.takeIf { it.rangeEnabled }
    val primaryHighlight: Pair<IntRange, Long>? = highlight?.let { active ->
        val range = when {
            paragraph.blockRange -> active.start..active.end
            paragraph.sentenceRange -> sentenceHighlightStart..sentenceHighlightEnd
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
    val glowColor = remember(glowColorArgb) {
        Color(glowColorArgb).copy(alpha = 1f)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth.value
        val height = maxHeight.value
        val topInset = WindowInsets.statusBars.getTop(density) / density.density
        val bottomInset = WindowInsets.navigationBars.getBottom(density) / density.density
        // Immersive mode can report zero insets; keep a real margin anyway so
        // the bubble never sits under the status bar or gesture handle.
        val topGap = maxOf(topInset, MiruSpace.Xl.value) + margin
        val bottomGap = maxOf(bottomInset, MiruSpace.Xl.value) + margin
        val availableWidth = (width - margin * 2).coerceAtLeast(120f)
        val maxPanelWidth = min(availableWidth, TtsFloatingMaxWidth)
        val minPanelWidth = min(TtsFloatingMinWidth, maxPanelWidth)
        val shownPanelWidth = panelWidth.coerceIn(minPanelWidth, maxPanelWidth)
        val bubbleMaxX = (width - bubbleSize - margin).coerceAtLeast(margin)
        val bubbleMaxY = (height - bubbleSize - bottomGap).coerceAtLeast(topGap)
        val panelMaxX = (width - shownPanelWidth - margin).coerceAtLeast(margin)
        val panelMaxY = (height - panelHeight - bottomGap).coerceAtLeast(topGap)
        val defaultBubbleLeft = width - bubbleSize - margin
        val defaultBubbleTop = height - bubbleSize - bottomGap
        val shownBubbleLeft = if (bubbleLeft < 0f) defaultBubbleLeft else bubbleLeft
        val shownBubbleTop = if (bubbleTop < 0f) defaultBubbleTop else bubbleTop
        val shownPanelLeft = if (panelLeft < 0f) shownBubbleLeft else panelLeft
        val shownPanelTop = if (panelTop < 0f) shownBubbleTop else panelTop
        val currentPanelMaxX by rememberUpdatedState(panelMaxX)
        val currentPanelMaxY by rememberUpdatedState(panelMaxY)

        fun persist() {
            onSettingsChange(
                settings.copy(
                    floatingBubbleX = bubbleLeft,
                    floatingBubbleY = bubbleTop,
                    floatingPanelX = panelLeft,
                    floatingPanelY = panelTop,
                    floatingPanelWidth = shownPanelWidth,
                    floatingOpacity = opacity,
                    floatingShowText = showText,
                    floatingGlow = glowMode,
                    floatingParagraphMode = paragraphMode,
                ),
            )
        }

        fun collapse() {
            expanded = false
            controlsHidden = false
            showActions = false
            showTuning = false
            persist()
        }

        // Resizing the panel re-lays out the slider, which can end its drag
        // callback before it commits. A short debounce persists the settled
        // width (and the other appearance choices) even in that case.
        LaunchedEffect(shownPanelWidth, opacity, showText, glowMode, paragraphMode) {
            delay(400)
            persist()
        }

        // First composition has -1 sentinels; rotation can also make a saved
        // spot invalid. Keep both states inside the visible reader area.
        LaunchedEffect(
            width,
            height,
            shownPanelWidth,
            panelHeight,
            topGap,
            bottomGap,
            expanded,
        ) {
            if (bubbleLeft < 0f) bubbleLeft = defaultBubbleLeft
            if (bubbleTop < 0f) bubbleTop = defaultBubbleTop
            if (expanded && panelLeft < 0f) panelLeft = shownBubbleLeft.coerceIn(margin, panelMaxX)
            if (expanded && panelTop < 0f) panelTop = shownBubbleTop.coerceIn(topGap, panelMaxY)
            bubbleLeft = bubbleLeft.coerceIn(margin, bubbleMaxX)
            bubbleTop = bubbleTop.coerceIn(topGap, bubbleMaxY)
            if (panelLeft >= 0f) panelLeft = panelLeft.coerceIn(margin, panelMaxX)
            if (panelTop >= 0f) panelTop = panelTop.coerceIn(topGap, panelMaxY)
        }

        val bubbleDrag = Modifier.pointerInput(
            width,
            height,
            topGap,
            bottomGap,
        ) {
            detectDragGestures(
                onDrag = { change, amount ->
                    change.consume()
                    bubbleLeft = (bubbleLeft + amount.x / density.density)
                        .coerceIn(margin, bubbleMaxX)
                    bubbleTop = (bubbleTop + amount.y / density.density)
                        .coerceIn(topGap, bubbleMaxY)
                },
                onDragEnd = {
                    // Snap only when the bubble is already near an edge; a
                    // deliberate floating position in the middle stays there.
                    val snapDistance = 36f
                    val leftGap = bubbleLeft - margin
                    val rightGap = bubbleMaxX - bubbleLeft
                    if (leftGap <= snapDistance && leftGap <= rightGap) {
                        bubbleLeft = margin
                    } else if (rightGap <= snapDistance) {
                        bubbleLeft = bubbleMaxX
                    }
                    persist()
                },
                onDragCancel = { persist() },
            )
        }
        // Do not key the drag on panel size: resizing the panel mid-gesture
        // would cancel/restart this pointer loop and its old cancel callback
        // could persist the pre-resize width over the new one.
        val panelDrag = Modifier.pointerInput(width, height, topGap, bottomGap) {
            detectDragGestures(
                onDrag = { change, amount ->
                    change.consume()
                    panelLeft = (panelLeft + amount.x / density.density)
                        .coerceIn(margin, currentPanelMaxX)
                    panelTop = (panelTop + amount.y / density.density)
                        .coerceIn(topGap, currentPanelMaxY)
                },
                onDragEnd = { persist() },
                onDragCancel = { persist() },
            )
        }

        if (!expanded) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .offset(shownBubbleLeft.dp, shownBubbleTop.dp)
                    .size(bubbleSize.dp)
                    .clickable(role = Role.Button) {
                        // Expand where the bubble is, not at a fixed corner.
                        if (panelLeft < 0f) {
                            panelLeft = shownBubbleLeft.coerceIn(margin, panelMaxX)
                        }
                        if (panelTop < 0f) {
                            panelTop = shownBubbleTop.coerceIn(topGap, panelMaxY)
                        }
                        controlsHidden = false
                        showActions = false
                        showTuning = false
                        expanded = true
                        persist()
                    }
                    .then(bubbleDrag),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.rd_tts_floating_open),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            return@BoxWithConstraints
        }

        val chapterLabel = if (chapterNumber > 0 && chapterCount > 0) {
            stringResource(R.string.rd_tts_chapter_number_fmt, chapterNumber, chapterCount)
        } else {
            ""
        }
        val timerLabel = formatTtsDuration(remainingSeconds)
        val timerDescription = stringResource(R.string.rd_tts_time_remaining_fmt, timerLabel)
        val chapterDescription = if (chapterLabel.isNotEmpty()) {
            stringResource(R.string.rd_tts_chapter_position_fmt, chapterNumber, chapterCount)
        } else {
            ""
        }

        Surface(
            shape = RoundedCornerShape(MiruRadius.Plate),
            color = if (!controlsHidden || !hasText) {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = opacity)
            } else {
                Color.Transparent
            },
            tonalElevation = if (controlsHidden) 0.dp else 3.dp,
            shadowElevation = if (controlsHidden) 0.dp else 14.dp,
            modifier = Modifier
                .offset(shownPanelLeft.dp, shownPanelTop.dp)
                .width(shownPanelWidth.dp)
                .onSizeChanged { panelHeight = it.height / density.density }
                .then(panelDrag),
        ) {
            Column(Modifier.padding(MiruSpace.S)) {
                if (controlsHidden) {
                    if (!hasText) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(MiruRadius.Inner))
                                .clickable(role = Role.Button) {
                                    controlsHidden = false
                                }
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
                                text = chapterTitle.ifBlank { stringResource(R.string.tts_ui_label) },
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
                        timerLabel = timerLabel,
                        timerDescription = timerDescription,
                        playing = playing,
                        session = session,
                        canNext = session && !atEnd,
                        actionsExpanded = showActions,
                        onToggleActions = {
                            showActions = !showActions
                            if (!showActions) showTuning = false
                        },
                        onCollapse = ::collapse,
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
                                onClick = {
                                    persist()
                                    onStop()
                                },
                            )
                            FloatingIconAction(
                                icon = Icons.Filled.CenterFocusWeak,
                                contentDescription = stringResource(R.string.rd_tts_start_here),
                                onClick = onStartHere,
                            )
                            FloatingIconAction(
                                icon = Icons.Filled.CenterFocusStrong,
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
                                    showText = !showText
                                    persist()
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
                                contentDescription = stringResource(
                                    R.string.rd_tts_floating_settings,
                                ),
                                onClick = {
                                    persist()
                                    onOpenSettings()
                                },
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
                                    value = "${(opacity * 100).roundToInt()}%",
                                ) {
                                    FloatingSlider(
                                        value = opacity,
                                        onValueChange = { opacity = it },
                                        onValueChangeFinished = ::persist,
                                        valueRange = 0.35f..1f,
                                        contentDescription = stringResource(
                                            R.string.rd_tts_floating_opacity,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                FloatingTuneRow(
                                    label = stringResource(R.string.rd_tts_floating_width),
                                    value = stringResource(
                                        R.string.rd_tts_floating_width_value,
                                        shownPanelWidth.roundToInt(),
                                    ),
                                ) {
                                    FloatingSlider(
                                        value = shownPanelWidth,
                                        onValueChange = {
                                            panelWidth = it.coerceIn(minPanelWidth, maxPanelWidth)
                                        },
                                        onValueChangeFinished = ::persist,
                                        valueRange = minPanelWidth..maxPanelWidth,
                                        contentDescription = stringResource(
                                            R.string.rd_tts_floating_width,
                                        ),
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
                                        glowMode = it
                                        persist()
                                    },
                                )
                                if (hasCounterpart) {
                                    FloatingChipRow(
                                        label = stringResource(R.string.rd_tts_floating_paragraph),
                                        options = listOf(
                                            "tts" to stringResource(
                                                R.string.rd_tts_paragraph_spoken,
                                            ),
                                            "both" to stringResource(R.string.rd_tts_paragraph_both),
                                            "inverse" to stringResource(
                                                R.string.rd_tts_paragraph_translation,
                                            ),
                                        ),
                                        selected = paragraphMode,
                                        onSelect = {
                                            paragraphMode = it
                                            persist()
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
                                .clickable(role = Role.Button) {
                                    controlsHidden = false
                                },
                        ) {
                            Box(
                                Modifier
                                    .width(MiruSize.Chip)
                                    .height(MiruSpace.Xxs)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.5f,
                                        ),
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
                            .pointerInput(Unit) {
                                val toggleControls = {
                                    controlsHidden = !controlsHidden
                                    if (controlsHidden) {
                                        showActions = false
                                        showTuning = false
                                    }
                                }
                                detectTapGestures(
                                    onDoubleTap = { toggleControls() },
                                    onLongPress = { toggleControls() },
                                )
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
    timerLabel: String,
    timerDescription: String,
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
            Surface(
                shape = RoundedCornerShape(MiruRadius.Badge),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = timerDescription
                },
            ) {
                Text(
                    text = timerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(
                        horizontal = MiruSpace.Xs,
                        vertical = MiruSpace.Xxs,
                    ),
                )
            }
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
    size: Dp = MiruSize.Control,
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

/** Compact visual size; the 32dp hit box over the 28dp circle stays tight. */
private val FloatingCompactTouch = MiruSize.Chip + MiruSpace.Xs

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

/**
 * Compact track used inside the floating tuning strip. It updates the value
 * live (so the panel resizes/dims while dragging) and commits once on release,
 * which keeps SharedPreferences writes out of the drag loop.
 */
@Composable
private fun FloatingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val trackHeightPx = with(density) { MiruSpace.Xxs.toPx() }
    val thumbRadiusPx = with(density) { MiruSpace.Xs.toPx() }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    val activeColor = MaterialTheme.colorScheme.primary
    var localValue by remember(value) { mutableFloatStateOf(value) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)

    fun valueAt(x: Float, widthPx: Int): Float {
        val fraction = (x / widthPx.coerceAtLeast(1)).coerceIn(0f, 1f)
        return valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
    }

    Box(
        modifier = modifier
            .height(MiruSize.Chip)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange,
                )
                setProgress { target ->
                    localValue = target.coerceIn(valueRange.start, valueRange.endInclusive)
                    currentOnValueChange(localValue)
                    currentOnFinished()
                    true
                }
            }
            .pointerInput(valueRange) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var active = valueAt(down.position.x, size.width)
                    localValue = active
                    currentOnValueChange(active)
                    drag(down.id) { change ->
                        change.consume()
                        val next = valueAt(change.position.x, size.width)
                        if (next != active) {
                            active = next
                            localValue = next
                            currentOnValueChange(next)
                        }
                    }
                    currentOnFinished()
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val centerY = size.height / 2
            val fraction = ((localValue - valueRange.start) /
                (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f))
                .coerceIn(0f, 1f)
            val activeWidth = width * fraction
            val trackTop = centerY - trackHeightPx / 2

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, trackTop),
                size = Size(width, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2),
            )
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(0f, trackTop),
                size = Size(activeWidth, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2),
            )
            drawCircle(
                color = activeColor,
                radius = thumbRadiusPx,
                center = Offset(activeWidth.coerceIn(0f, width), centerY),
            )
        }
    }
}
