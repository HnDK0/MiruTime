package dev.mirutime.feature.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.tts.R
import dev.mirutime.feature.tts.clampFloatingPanelWidth
import dev.mirutime.feature.tts.floatingWidthRange
import dev.mirutime.feature.tts.TtsSettings
import dev.mirutime.feature.tts.TtsSleepTimerState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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
 *
 * The panel body is [TtsPlayerContent]: the same surface the system overlay
 * shows, so leaving the app never downgrades the player to a bare transport.
 */
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
    sleepTimer: StateFlow<TtsSleepTimerState?>? = null,
    onSleepTimerClick: (() -> Unit)? = null,
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth.value
        val height = maxHeight.value
        val topInset = WindowInsets.statusBars.getTop(density) / density.density
        val bottomInset = WindowInsets.navigationBars.getBottom(density) / density.density
        // Immersive mode can report zero insets; keep a real margin anyway so
        // the bubble never sits under the status bar or gesture handle.
        val topGap = maxOf(topInset, MiruSpace.Xl.value) + margin
        val bottomGap = maxOf(bottomInset, MiruSpace.Xl.value) + margin
        // The slider range comes from the screen so resizing the panel cannot
        // move its own max edge (that made the handle chase the finger).
        val widthRange = floatingWidthRange(width, margin)
        val shownPanelWidth = clampFloatingPanelWidth(panelWidth, widthRange)
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
        // The debounce below does not restart when speed/voice change, so it
        // must write on top of the LATEST settings, not the snapshot captured
        // when it launched.
        val currentSettings by rememberUpdatedState(settings)

        fun persist() {
            onSettingsChange(
                currentSettings.copy(
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

        TtsPlayerContent(
            ui = TtsPlayerUi(
                status = status,
                chapterTitle = chapterTitle,
                sentencePreview = sentencePreview,
                paragraphText = paragraphText,
                counterpartText = counterpartText,
                spokenIsTranslation = spokenIsTranslation,
                ttsHighlight = ttsHighlight,
                sentenceHighlightStart = sentenceHighlightStart,
                sentenceHighlightEnd = sentenceHighlightEnd,
                sentenceNumber = sentenceNumber,
                chunkCount = chunkCount,
                chapterNumber = chapterNumber,
                chapterCount = chapterCount,
                chapterPercent = chapterPercent,
                remainingSeconds = remainingSeconds,
                atEnd = atEnd,
                glowColorArgb = glowColorArgb,
            ),
            settings = settings,
            onToggle = onToggle,
            onStop = {
                persist()
                onStop()
            },
            onStartHere = onStartHere,
            onFocus = onFocus,
            onPreviousChunk = onPreviousChunk,
            onNextChunk = onNextChunk,
            onOpenSettings = {
                persist()
                onOpenSettings()
            },
            onCollapse = {
                persist()
                expanded = false
            },
            modifier = Modifier
                .offset(shownPanelLeft.dp, shownPanelTop.dp)
                .width(shownPanelWidth.dp)
                .onSizeChanged {
                    panelHeight = it.height / density.density
                    MiruLog.event(
                        "tts",
                        "floating-panel-size",
                        "width" to it.width / density.density,
                        "height" to panelHeight,
                        "left" to shownPanelLeft,
                        "top" to shownPanelTop,
                    )
                }
                .then(panelDrag),
            sleepTimer = sleepTimer,
            onSleepTimerClick = onSleepTimerClick,
            panelWidth = panelWidth,
            onPanelWidthChange = { panelWidth = clampFloatingPanelWidth(it, widthRange) },
            screenWidthDp = width,
            marginDp = margin,
            opacity = opacity,
            onOpacityChange = { opacity = it },
            showText = showText,
            onShowTextChange = { showText = it },
            glowMode = glowMode,
            onGlowModeChange = { glowMode = it },
            paragraphMode = paragraphMode,
            onParagraphModeChange = { paragraphMode = it },
            onAppearanceCommit = ::persist,
        )
    }
}
