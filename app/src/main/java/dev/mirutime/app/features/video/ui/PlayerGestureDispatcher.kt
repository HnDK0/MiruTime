@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mirutime.app.features.video.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.mirutime.app.features.video.domain.DoubleTapAccumulator
import dev.mirutime.app.features.video.domain.DOUBLE_TAP_WINDOW_MS
import dev.mirutime.app.features.video.domain.GestureAction
import dev.mirutime.app.features.video.domain.GestureConfig
import dev.mirutime.app.features.video.domain.LONG_PRESS_SPEED_FACTOR
import dev.mirutime.app.features.video.domain.brightnessFraction
import dev.mirutime.app.features.video.domain.clampSeekMs
import dev.mirutime.app.features.video.domain.dragSeekTargetMs
import dev.mirutime.app.features.video.domain.isBrightnessSide
import dev.mirutime.app.features.video.domain.isTap
import dev.mirutime.app.features.video.domain.volumeFractionDelta
import kotlin.math.abs
import kotlin.math.roundToInt

/** Short-lived indicator rendered over the video by the player screen. */
sealed interface GestureHud {
    data class Seek(val positionMs: Long, val durationMs: Long) : GestureHud
    data class Brightness(val fraction: Float) : GestureHud
    data class Volume(val fraction: Float) : GestureHud
    data class Speed(val factor: Float) : GestureHud
}

private const val LONG_PRESS_DELAY_MS = 500L
private const val HUD_HIDE_DELAY_MS = 900L

// Center third toggles the controls immediately; the sides feed the double-tap
// seek accumulator (VLC/MX pattern).
private const val CENTER_ZONE_START = 0.35f
private const val CENTER_ZONE_END = 0.65f

private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME, SPEED }

/**
 * Touch layer over [PlayerView].
 *
 * Why not `Modifier.pointerInput` over `AndroidView`: Compose's
 * `PointerInteropFilter` and the View's own touch handling consume the same
 * event stream, so two gesture layers cancel each other. The listener lives
 * inside the View and returns `false` in the header/controller exclusion
 * zones, letting media3's buttons keep working.
 *
 * Horizontal drag only previews in the HUD and commits on ACTION_UP; frequent
 * player.seekTo() calls during movement lag (androidx/media #3403). Brightness
 * and volume apply immediately because they are cheap.
 */
internal class PlayerGestureDispatcher(
    private val activity: Activity,
    private val playerProvider: () -> Player?,
    // The drag threshold is stored as a fraction; only the View knows its
    // width at touch time.
    private val configProvider: (viewWidthPx: Float) -> GestureConfig,
    // Header/status-bar and media3 controller areas are not ours.
    private val exclusionTopPx: (View) -> Int,
    private val exclusionBottomPx: (View) -> Int,
    private val onToggleControls: (View) -> Unit,
    private val onHud: (GestureHud?) -> Unit,
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tapAccumulator = DoubleTapAccumulator()

    private var handled = false
    private var mode = GestureMode.NONE
    private var gesturePlayer: Player? = null

    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f
    // The pointer that started the gesture: lifting the primary finger
    // renumbers pointers and event.x/y would jump to another finger.
    private var pointerId = 0
    private var startBrightness = 0.5f
    private var startVolumeStream = 0
    private var originalSpeed = 1f
    private var seekBaseMs = 0L
    private var seekTargetMs = 0L

    private var pendingSingleTap: Runnable? = null
    private var pendingLongPress: Runnable? = null
    private var pendingHudHide: Runnable? = null

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val player = playerProvider()
                if (player == null || inExclusion(view, event)) {
                    handled = false
                    return false
                }
                handled = true
                onDown(view, event, player)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!handled) return false
                onMove(view, event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!handled) return false
                onUp(view, event)
                handled = false
                return true
            }
            // The system owns the DOWN, so consume its cancel too.
            MotionEvent.ACTION_CANCEL -> {
                if (handled) cancelAll()
                handled = false
                return true
            }
            else -> return handled
        }
    }

    private fun inExclusion(view: View, event: MotionEvent): Boolean =
        event.y < exclusionTopPx(view) || event.y > view.height - exclusionBottomPx(view)

    private fun onDown(view: View, event: MotionEvent, player: Player) {
        gesturePlayer = player
        downTimeMs = event.eventTime
        pointerId = event.getPointerId(event.actionIndex)
        downX = event.x
        downY = event.y
        mode = GestureMode.NONE
        originalSpeed = player.playbackParameters.speed
        seekBaseMs = player.currentPosition
        seekTargetMs = seekBaseMs
        startBrightness = currentScreenBrightness()
        startVolumeStream = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // The accumulator itself owns the double-tap window; a drag breaks the
        // sequence in enter().
        cancelPendingTaps()

        if (configProvider(view.width.toFloat()).longPressSpeedEnabled) {
            pendingLongPress = Runnable {
                if (mode == GestureMode.NONE) {
                    mode = GestureMode.SPEED
                    applyAction(
                        GestureAction.Speed(LONG_PRESS_SPEED_FACTOR),
                        gesturePlayer,
                    )
                }
            }.also { handler.postDelayed(it, LONG_PRESS_DELAY_MS) }
        }
    }

    private fun onMove(view: View, event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(pointerId)
        if (pointerIndex < 0) return
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val config = configProvider(view.width.toFloat())
        val dx = x - downX
        val dy = y - downY
        val threshold = config.dragThresholdPx
        val player = gesturePlayer ?: return

        if (mode == GestureMode.NONE) {
            // The dominant axis wins so a diagonal swipe does not become both.
            val horizontal = abs(dx) > threshold && abs(dx) > abs(dy)
            val vertical = abs(dy) > threshold && abs(dy) > abs(dx)
            when {
                horizontal -> enter(GestureMode.SEEK)
                vertical && config.verticalSwipeEnabled -> {
                    enter(
                        if (isBrightnessSide(
                                startXFraction = downX / view.width,
                                swappedSides = config.swappedSides,
                            )
                        ) GestureMode.BRIGHTNESS else GestureMode.VOLUME
                    )
                }
            }
        }

        when (mode) {
            GestureMode.SEEK -> {
                dragSeekTargetMs(
                    dxPx = dx,
                    screenWidthPx = view.width.toFloat(),
                    currentMs = seekBaseMs,
                    durationMs = player.duration,
                )?.let { target ->
                    seekTargetMs = target
                    // Preview only; commit on ACTION_UP.
                    showHud(GestureHud.Seek(target, player.duration))
                }
            }
            GestureMode.BRIGHTNESS -> applyAction(
                GestureAction.Brightness(
                    brightnessFraction(
                        startFraction = startBrightness,
                        deltaYpx = y - downY,
                        screenHeightPx = view.height.toFloat(),
                    ),
                ),
                player,
            )
            GestureMode.VOLUME -> applyAction(
                GestureAction.VolumeDelta(
                    volumeFractionDelta(
                        deltaYpx = y - downY,
                        screenHeightPx = view.height.toFloat(),
                    ),
                ),
                player,
            )
            else -> Unit
        }
    }

    private fun enter(newMode: GestureMode) {
        mode = newMode
        // Movement breaks the tap sequence: a tap within the window after a
        // drag must not count as a second tap. Cancel the delayed tap so the
        // panel does not flash in the middle of a drag.
        tapAccumulator.reset()
        cancelPendingTaps()
        cancelPendingLongPress()
    }

    private fun onUp(view: View, event: MotionEvent) {
        cancelPendingLongPress()
        val config = configProvider(view.width.toFloat())
        val previousMode = mode
        mode = GestureMode.NONE
        val player = gesturePlayer
        gesturePlayer = null
        // Same coordinate logic as onMove; fall back to pointer 0 if needed.
        val pointerIndex = event.findPointerIndex(pointerId)
        val x = if (pointerIndex >= 0) event.getX(pointerIndex) else event.x
        val y = if (pointerIndex >= 0) event.getY(pointerIndex) else event.y

        when (previousMode) {
            GestureMode.SPEED -> applyAction(
                GestureAction.Speed(originalSpeed),
                player,
            )
            // Commit only on release: movement showed one preview HUD.
            GestureMode.SEEK -> applyAction(GestureAction.SeekTo(seekTargetMs), player)
            GestureMode.BRIGHTNESS -> showHud(
                GestureHud.Brightness(
                    brightnessFraction(startBrightness, y - downY, view.height.toFloat()),
                ),
            )
            GestureMode.VOLUME -> showHud(
                GestureHud.Volume(
                    startVolumeFraction(volumeFractionDelta(y - downY, view.height.toFloat())),
                ),
            )
            GestureMode.NONE -> onTap(event, x, view, config, player)
        }
    }

    /** Center tap toggles immediately; edge taps wait for a possible second. */
    private fun onTap(
        event: MotionEvent,
        x: Float,
        view: View,
        config: GestureConfig,
        player: Player?,
    ) {
        if (!isTap(event.eventTime - downTimeMs)) return
        if (!config.doubleTapEnabled) {
            onToggleControls(view)
            return
        }

        val xFraction = x / view.width
        if (xFraction in CENTER_ZONE_START..CENTER_ZONE_END) {
            // Waiting for the double-tap window here feels like a hang.
            tapAccumulator.reset()
            onToggleControls(view)
            return
        }

        if (tapAccumulator.onTap() == 1) {
            // First edge tap: wait for a second; without one it is a normal
            // tap that shows the controls.
            pendingSingleTap = Runnable {
                tapAccumulator.reset()
                onToggleControls(view)
            }.also { handler.postDelayed(it, DOUBLE_TAP_WINDOW_MS) }
        } else {
            pendingSingleTap?.let(handler::removeCallbacks)
            pendingSingleTap = null
            val stepMs = config.seekStepMs.toLong() * if (xFraction < 0.5f) -1 else 1
            applyAction(GestureAction.Seek(stepMs), player)
        }
    }

    /**
     * The only place a gesture reaches the player/system and the HUD; the
     * methods above only compute [GestureAction].
     */
    private fun applyAction(action: GestureAction, player: Player?) {
        when (action) {
            is GestureAction.Seek -> {
                if (player == null) return
                clampSeekMs(player.currentPosition, action.deltaMs, player.duration)
                    ?.let { target ->
                        player.seekTo(target)
                        showHud(GestureHud.Seek(target, player.duration))
                    }
            }
            is GestureAction.SeekTo -> {
                if (player == null) return
                clampSeekMs(seekBaseMs, action.ms - seekBaseMs, player.duration)
                    ?.let { target ->
                        player.seekTo(target)
                        showHud(GestureHud.Seek(target, player.duration))
                    }
            }
            is GestureAction.Brightness -> {
                activity.window.attributes = activity.window.attributes
                    .apply { screenBrightness = action.fraction }
                showHud(GestureHud.Brightness(action.fraction))
            }
            is GestureAction.VolumeDelta -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max <= 0) return
                val target = (startVolumeStream + action.fraction * max)
                    .roundToInt()
                    .coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                showHud(GestureHud.Volume(target / max.toFloat()))
            }
            is GestureAction.Speed -> {
                player?.playbackParameters = PlaybackParameters(action.factor)
                showHud(GestureHud.Speed(action.factor))
            }
            GestureAction.ToggleControls -> Unit
            GestureAction.None -> Unit
        }
    }

    private fun startVolumeFraction(delta: Float): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return ((startVolumeStream + delta * max) / max).coerceIn(0f, 1f)
    }

    private fun showHud(hud: GestureHud?) {
        pendingHudHide?.let(handler::removeCallbacks)
        pendingHudHide = null
        onHud(hud)
        if (hud != null) {
            pendingHudHide = Runnable { onHud(null) }
                .also { handler.postDelayed(it, HUD_HIDE_DELAY_MS) }
        }
    }

    private fun currentScreenBrightness(): Float {
        val fromWindow = activity.window.attributes.screenBrightness
        if (fromWindow >= 0f) return fromWindow.coerceIn(0f, 1f)
        // System brightness is readable without a permission.
        return runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            ) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    private fun cancelPendingTaps() {
        pendingSingleTap?.let(handler::removeCallbacks)
        pendingSingleTap = null
        pendingHudHide?.let(handler::removeCallbacks)
        pendingHudHide = null
        onHud(null)
    }

    private fun cancelPendingLongPress() {
        pendingLongPress?.let(handler::removeCallbacks)
        pendingLongPress = null
    }

    private fun cancelAll() {
        cancelPendingTaps()
        cancelPendingLongPress()
        if (mode == GestureMode.SPEED) {
            gesturePlayer?.playbackParameters = PlaybackParameters(originalSpeed)
        }
        mode = GestureMode.NONE
        gesturePlayer = null
    }

    /** Drops delayed callbacks when the player view leaves composition. */
    fun dispose() {
        // The player is released together with this view, so there is nothing
        // to restore; only the handler must not keep the Activity alive.
        cancelPendingTaps()
        cancelPendingLongPress()
        mode = GestureMode.NONE
        gesturePlayer = null
        handler.removeCallbacksAndMessages(null)
    }
}

/**
 * Manual controller toggle: our listener consumes taps, so
 * [PlayerView.performClick] never reaches the default behavior.
 */
internal fun PlayerView.toggleController() {
    if (isControllerShown()) hideController() else showController()
}

/** Actual controller visibility, not `PlayerView.isControllerVisible` state. */
internal fun PlayerView.isControllerShown(): Boolean =
    findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.visibility == View.VISIBLE
