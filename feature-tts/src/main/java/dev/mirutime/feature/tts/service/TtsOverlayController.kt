package dev.mirutime.feature.tts.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.tts.R
import dev.mirutime.feature.tts.TtsAppVisibility
import dev.mirutime.feature.tts.TtsController
import dev.mirutime.feature.tts.TtsPlayerText
import dev.mirutime.feature.tts.TtsSettings
import dev.mirutime.feature.tts.TtsSleepTimerState
import dev.mirutime.feature.tts.TtsState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.clampFloatingPanelWidth
import dev.mirutime.feature.tts.floatingWidthRange
import dev.mirutime.feature.tts.ttsOverlayVisible
import dev.mirutime.feature.tts.ui.TtsPlayerContent
import dev.mirutime.feature.tts.ui.TtsPlayerUi
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruTheme
import dev.mirutime.uikit.ThemeMode
import dev.mirutime.uikit.miruCardElevation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class TtsOverlayChapter(
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapterNumber: Int = 0,
    val chapterCount: Int = 0,
)

/**
 * A real system overlay for the floating TTS player: it stays visible over
 * other apps while the foreground service speaks. It renders exactly the same
 * surface as the in-app player ([TtsPlayerContent]) and owns only the window:
 * position, drag and snap-to-edge. The position is persisted in the same
 * floating settings the in-app player uses.
 *
 * The window appears only while the app is in the background
 * ([ttsOverlayVisible]): in front, the in-app player is the surface, so the two
 * never show the same session twice.
 */
class TtsOverlayController(
    private val context: Context,
    private val controller: TtsController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val chapter = MutableStateFlow(TtsOverlayChapter())
    private val emptySleepTimer = MutableStateFlow<TtsSleepTimerState?>(null)
    private val density = context.resources.displayMetrics.density
    private val metrics = context.resources.displayMetrics
    /** Same screen-edge margin the in-app panel uses (MiruSpace.M). */
    private val marginDp = MiruSpace.M.value

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded by mutableStateOf(false)
    // The bubble and the panel keep independent positions, like NoveLA: moving
    // one must never teleport the other. Each is persisted to its own setting.
    private var bubbleX = 0
    private var bubbleY = 0
    private var panelX = 0
    private var panelY = 0
    private var started = false

    private val offsetX: Int get() = if (expanded) panelX else bubbleX
    private val offsetY: Int get() = if (expanded) panelY else bubbleY

    private fun setOffset(x: Int, y: Int) {
        if (expanded) {
            panelX = x
            panelY = y
        } else {
            bubbleX = x
            bubbleY = y
        }
    }

    fun updateChapter(info: TtsOverlayChapter) {
        chapter.value = info
    }

    fun start() {
        if (started) return
        started = true
        owner.create()
        val settings = controller.settings.value
        bubbleX = if (settings.floatingBubbleX >= 0f) {
            dp(settings.floatingBubbleX.toInt())
        } else {
            metrics.widthPixels - dp(BubbleSizeDp)
        }
        bubbleY = if (settings.floatingBubbleY >= 0f) {
            dp(settings.floatingBubbleY.toInt())
        } else {
            (metrics.heightPixels * 0.62f).toInt()
        }
        // A saved panel spot wins; otherwise the panel opens where the bubble
        // was, exactly once, and then keeps its own position.
        panelX = if (settings.floatingPanelX >= 0f) dp(settings.floatingPanelX.toInt()) else bubbleX
        panelY = if (settings.floatingPanelY >= 0f) dp(settings.floatingPanelY.toInt()) else bubbleY
        scope.launch {
            combine(
                controller.state,
                controller.settings,
                TtsAppVisibility.foreground,
            ) { state, settings, foreground ->
                Triple(state, settings, foreground)
            }.collect { (state, settings, foreground) ->
                val visible = ttsOverlayVisible(
                    floatingEnabled = settings.floatingPlayer,
                    permissionGranted = Settings.canDrawOverlays(context),
                    appForeground = foreground,
                    status = state.status,
                )
                if (visible) {
                    show()
                } else {
                    hide()
                }
            }
        }
        MiruLog.event("tts", "overlay-start")
    }

    fun destroy() {
        scope.cancel()
        hide()
        owner.destroy()
        MiruLog.event("tts", "overlay-destroy")
    }

    private fun show() {
        if (view != null) return
        owner.resume()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val state by controller.state.collectAsState()
                val settings by controller.settings.collectAsState()
                val playerText by controller.playerText.collectAsState()
                val info by chapter.collectAsState()
                MiruTheme(if (isSystemInDarkTheme()) ThemeMode.Night else ThemeMode.Paper) {
                    OverlayContent(
                        state = state,
                        settings = settings,
                        chapter = info,
                        playerText = playerText,
                        sleepTimer = emptySleepTimer,
                        expanded = expanded,
                        onToggleExpand = ::applyExpanded,
                        onDrag = ::moveBy,
                        onDragEnd = ::persistPosition,
                        onToggle = {
                            if (state.status == TtsStatus.Playing) {
                                controller.pause()
                            } else {
                                controller.resume()
                            }
                        },
                        onStop = { controller.stop() },
                        onPreviousChunk = { controller.skipPreviousChunk() },
                        onNextChunk = { controller.skipNextChunk() },
                        onSettingsChange = { controller.updateSettings(it) },
                        onOpenSettings = { context.startActivity(overlayAppIntent(context)) },
                        screenWidthDp = metrics.widthPixels / density,
                        marginDp = marginDp,
                        onWidthApplied = ::applyPanelWidth,
                    )
                }
            }
        }
        val layout = layoutParams(windowWidth(settingsWidth(controller.settings.value)))
        val added = runCatching { windowManager.addView(composeView, layout) }
            .onFailure { MiruLog.e("tts", "overlay add failed", it) }
            .isSuccess
        if (!added) {
            owner.pause()
            return
        }
        params = layout
        view = composeView
    }

    private fun hide() {
        val current = view ?: return
        runCatching { windowManager.removeView(current) }
        view = null
        params = null
        owner.pause()
    }

    private fun applyExpanded(value: Boolean) {
        // Keep the current surface's position before switching, so the other one
        // opens exactly where it was last left.
        expanded = value
        val layout = params ?: return
        layout.width = windowWidth(settingsWidth(controller.settings.value))
        val x = offsetX.coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
        setOffset(x, offsetY)
        layout.x = x
        layout.y = offsetY
        runCatching { windowManager.updateViewLayout(view, layout) }
    }

    /**
     * A settled panel width has to be applied to the window itself: the overlay
     * renders in a WRAP_CONTENT-height window whose width is fixed at creation,
     * so saving the setting alone changed nothing on screen.
     */
    private fun applyPanelWidth(widthDp: Float) {
        val layout = params ?: return
        if (!expanded) return
        layout.width = dp(widthDp.toInt())
        panelX = panelX.coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
        layout.x = panelX
        runCatching { windowManager.updateViewLayout(view, layout) }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val layout = params ?: return
        val x = (offsetX + dx).toInt()
            .coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
        val y = (offsetY + dy).toInt()
            .coerceIn(0, (metrics.heightPixels - layout.height).coerceAtLeast(0))
        setOffset(x, y)
        layout.x = x
        layout.y = y
        runCatching { windowManager.updateViewLayout(view, layout) }
    }

    /**
     * Snap to the nearest edge within the same 36dp band the in-app bubble uses,
     * then persist: an overlay the user positioned must stay where they left it
     * across sessions. The bubble and the panel keep separate positions.
     */
    private fun persistPosition() {
        val layout = params ?: return
        val snapDistance = dp(36).toFloat()
        val margin = dp(MiruSpace.M.value.toInt())
        val rightEdge = metrics.widthPixels - layout.width
        val x = offsetX
        val leftGap = x.toFloat() - margin
        val rightGap = rightEdge.toFloat() - x
        val snappedX = when {
            leftGap <= snapDistance && leftGap <= rightGap -> margin
            rightGap <= snapDistance -> rightEdge
            else -> x
        }
        setOffset(snappedX, offsetY)
        layout.x = snappedX
        runCatching { windowManager.updateViewLayout(view, layout) }
        controller.updateSettings(
            if (expanded) {
                controller.settings.value.copy(
                    floatingPanelX = panelX / density,
                    floatingPanelY = panelY / density,
                )
            } else {
                controller.settings.value.copy(
                    floatingBubbleX = bubbleX / density,
                    floatingBubbleY = bubbleY / density,
                )
            },
        )
    }

    /** A stored width may exceed a narrower screen; clamp before sizing a window. */
    private fun settingsWidth(settings: TtsSettings): Int = dp(
        clampFloatingPanelWidth(
            settings.floatingPanelWidth,
            floatingWidthRange(metrics.widthPixels / density, marginDp),
        ).toInt(),
    )

    private fun windowWidth(panelWidth: Int): Int =
        if (expanded) panelWidth else dp(BubbleSizeDp)

    private fun layoutParams(width: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // x is measured from the left in the drag math; START would mirror it.
            gravity = Gravity.TOP or Gravity.LEFT
            x = offsetX
            y = offsetY
        }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val BubbleSizeDp = 64
    }
}

private fun overlayAppIntent(context: Context): Intent =
    context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

@Composable
private fun OverlayContent(
    state: TtsState,
    settings: TtsSettings,
    chapter: TtsOverlayChapter,
    playerText: TtsPlayerText,
    sleepTimer: StateFlow<TtsSleepTimerState?>,
    expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSettingsChange: (TtsSettings) -> Unit,
    onOpenSettings: () -> Unit,
    /** Screen width in dp; the panel's own width must never define its range. */
    screenWidthDp: Float,
    marginDp: Float,
    /** Applies a settled width to the window (the overlay has to resize itself). */
    onWidthApplied: (Float) -> Unit,
) {
    val dragModifier = Modifier
        .padding(MiruSpace.Xs)
        .pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                },
                onDragEnd = onDragEnd,
            )
        }

    if (!expanded) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = miruCardElevation(),
            modifier = dragModifier,
        ) {
            Box(
                modifier = Modifier
                    .size(MiruSize.Touch)
                    .clip(CircleShape)
                    .clickable { onToggleExpand(true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.status == TtsStatus.Playing) {
                        Icons.Filled.Pause
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = stringResource(
                        if (state.status == TtsStatus.Playing) {
                            R.string.rd_tts_overlay_pause
                        } else {
                            R.string.rd_tts_overlay_play
                        },
                    ),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(MiruRadius.Plate),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = miruCardElevation(),
        modifier = dragModifier,
    ) {
        Column {
            TtsPlayerContent(
                ui = TtsPlayerUi(
                    status = state.status,
                    chapterTitle = chapter.chapterTitle.ifBlank { chapter.bookTitle },
                    sentencePreview = playerText.sentence,
                    paragraphText = playerText.paragraph,
                    counterpartText = playerText.counterpart,
                    spokenIsTranslation = state.translated,
                    sentenceHighlightStart = playerText.sentenceHighlightStart,
                    sentenceHighlightEnd = playerText.sentenceHighlightEnd,
                    sentenceNumber = state.currentChunk,
                    chunkCount = state.chunkCount,
                    chapterNumber = chapter.chapterNumber,
                    chapterCount = chapter.chapterCount,
                    chapterPercent = playerText.chapterPercent,
                    remainingSeconds = state.estimatedRemainingSeconds,
                    atEnd = state.atEnd,
                    glowColorArgb = playerText.glowColorArgb,
                ),
                settings = settings,
                onToggle = onToggle,
                onStop = onStop,
                onStartHere = {},
                onFocus = {},
                onPreviousChunk = onPreviousChunk,
                onNextChunk = onNextChunk,
                onOpenSettings = onOpenSettings,
                onCollapse = { onToggleExpand(false) },
                sleepTimer = sleepTimer,
                onSleepTimerClick = null,
                panelWidth = settings.floatingPanelWidth,
                onPanelWidthChange = { width ->
                    onSettingsChange(settings.copy(floatingPanelWidth = width))
                    onWidthApplied(width)
                },
                screenWidthDp = screenWidthDp,
                marginDp = marginDp,
                opacity = settings.floatingOpacity,
                onOpacityChange = {
                    onSettingsChange(settings.copy(floatingOpacity = it))
                },
                showText = settings.floatingShowText,
                onShowTextChange = {
                    onSettingsChange(settings.copy(floatingShowText = it))
                },
                glowMode = settings.floatingGlow,
                onGlowModeChange = {
                    onSettingsChange(settings.copy(floatingGlow = it))
                },
                paragraphMode = settings.floatingParagraphMode,
                onParagraphModeChange = {
                    onSettingsChange(settings.copy(floatingParagraphMode = it))
                },
            )
        }
    }
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun create() {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
