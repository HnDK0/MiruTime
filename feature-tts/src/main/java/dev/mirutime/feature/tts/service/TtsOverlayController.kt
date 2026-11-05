package dev.mirutime.feature.tts.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.mirutime.core.log.MiruLog
import dev.mirutime.feature.tts.TtsController
import dev.mirutime.feature.tts.TtsSettings
import dev.mirutime.feature.tts.TtsState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.R
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
 * other apps while the foreground service speaks. The in-app Compose player
 * remains the fallback when the overlay permission is not granted.
 *
 * The controller owns the window; the service feeds it chapter metadata and
 * the controller observes the shared TtsController state/settings.
 */
class TtsOverlayController(
    private val context: Context,
    private val controller: TtsController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val chapter = MutableStateFlow(TtsOverlayChapter())
    private val density = context.resources.displayMetrics.density
    private val metrics = context.resources.displayMetrics

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded by mutableStateOf(false)
    private var offsetX = 0
    private var offsetY = 0
    private var started = false

    fun updateChapter(info: TtsOverlayChapter) {
        chapter.value = info
    }

    fun start() {
        if (started) return
        started = true
        owner.create()
        offsetX = metrics.widthPixels - dp(72)
        offsetY = (metrics.heightPixels * 0.62f).toInt()
        scope.launch {
            combine(controller.state, controller.settings) { state, settings ->
                state to settings
            }.collect { (state, settings) ->
                val visible = settings.floatingPlayer && state.status in ACTIVE_STATUSES
                if (visible && Settings.canDrawOverlays(context)) {
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
                val info by chapter.collectAsState()
                MiruTheme(if (isSystemInDarkTheme()) ThemeMode.Night else ThemeMode.Paper) {
                    OverlayContent(
                        state = state,
                        chapter = info,
                        expanded = expanded,
                        onToggleExpand = ::applyExpanded,
                        onDrag = ::moveBy,
                        onToggle = { if (state.status == TtsStatus.Playing) controller.pause() else controller.resume() },
                        onStop = { controller.stop() },
                        onPrevious = { controller.skipPreviousChunk() },
                        onNext = { controller.skipNextChunk() },
                    )
                }
            }
        }
        val layout = layoutParams(if (expanded) dp(PanelWidthDp) else dp(BubbleSizeDp))
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
        expanded = value
        val layout = params ?: return
        layout.width = dp(if (value) PanelWidthDp else BubbleSizeDp)
        offsetX = offsetX.coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
        layout.x = offsetX
        runCatching { windowManager.updateViewLayout(view, layout) }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val layout = params ?: return
        offsetX = (offsetX + dx).toInt()
            .coerceIn(0, (metrics.widthPixels - layout.width).coerceAtLeast(0))
        offsetY = (offsetY + dy).toInt()
            .coerceIn(0, (metrics.heightPixels - dp(BubbleSizeDp)).coerceAtLeast(0))
        layout.x = offsetX
        layout.y = offsetY
        runCatching { windowManager.updateViewLayout(view, layout) }
    }

    private fun layoutParams(width: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = offsetX
            y = offsetY
        }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val BubbleSizeDp = 64
        const val PanelWidthDp = 300
        val ACTIVE_STATUSES = setOf(TtsStatus.Playing, TtsStatus.Loading, TtsStatus.Paused)
    }
}

@Composable
private fun OverlayContent(
    state: TtsState,
    chapter: TtsOverlayChapter,
    expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        shape = if (expanded) RoundedCornerShape(MiruRadius.Plate) else CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = miruCardElevation(),
        modifier = Modifier
            .padding(MiruSpace.Xs)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
    ) {
        if (!expanded) {
            Box(
                modifier = Modifier
                    .size(MiruSize.Touch)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
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
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MiruSpace.M),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = chapter.chapterTitle.ifBlank { chapter.bookTitle },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chapter.chapterCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.rd_tts_chapter_position_fmt,
                                    chapter.chapterNumber + 1,
                                    chapter.chapterCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onToggleExpand(false) }) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.rd_tts_overlay_collapse),
                        )
                    }
                }
                val progress = if (state.chunkCount > 0) {
                    (state.currentChunk.toFloat() / state.chunkCount).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPrevious) {
                        Icon(
                            Icons.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.rd_tts_prev_chunk),
                        )
                    }
                    IconButton(onClick = onToggle) {
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
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.rd_tts_next_chunk),
                        )
                    }
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.rd_tts_stop),
                        )
                    }
                }
            }
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
