package dev.mirutime.app.features.video.domain

/**
 * Video player gesture math. Pure Kotlin so the thresholds and side rules are
 * unit-tested without a device; the Android touch layer lives in
 * `PlayerGestureDispatcher`.
 *
 * Model ported from NoveLA `VideoGestures.kt` (itself a port of mpv-android
 * TouchGestures, MIT): the caller supplies the pixel drag threshold, taps and
 * swipes become a [GestureAction].
 */

/** A release shorter than this is a tap; longer is a drag or a long-press. */
const val TAP_MAX_DURATION_MS = 300L

/** Window that groups taps into a double/triple sequence. */
const val DOUBLE_TAP_WINDOW_MS = 300L

/** Playback factor while the finger is held down. */
const val LONG_PRESS_SPEED_FACTOR = 2.0f

data class GestureConfig(
    /** Seek step per tap in a double/triple sequence, milliseconds. */
    val seekStepMs: Int,
    /** Horizontal drag start threshold in pixels (fraction × width by caller). */
    val dragThresholdPx: Float,
    /** true: left half controls volume, right half brightness. */
    val swappedSides: Boolean,
    val doubleTapEnabled: Boolean,
    val verticalSwipeEnabled: Boolean,
    val longPressSpeedEnabled: Boolean,
) {
    /** N taps in the window → N × step; the caller applies the sign. */
    fun seekDeltaMs(tapCount: Int): Long = tapCount * seekStepMs.toLong()
}

/** Result of a gesture; [None] lets the event fall through. */
sealed interface GestureAction {
    data class Seek(val deltaMs: Long) : GestureAction
    data class SeekTo(val ms: Long) : GestureAction
    /** Absolute screen brightness 0..1. */
    data class Brightness(val fraction: Float) : GestureAction
    /** Volume change as a fraction of the maximum, -1..1. */
    data class VolumeDelta(val fraction: Float) : GestureAction
    data class Speed(val factor: Float) : GestureAction
    data object ToggleControls : GestureAction
    data object None : GestureAction
}

/**
 * Accumulates taps inside [windowMs]. The clock is injected so tests use a
 * fake instead of sleeping.
 */
class DoubleTapAccumulator(
    private val windowMs: Long = DOUBLE_TAP_WINDOW_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var lastTapMs = 0L
    private var count = 0

    val tapCount: Int get() = count

    /** Sequence length after this tap; a tap outside the window starts at 1. */
    fun onTap(): Int {
        val now = nowMs()
        count = if (count > 0 && now - lastTapMs <= windowMs) count + 1 else 1
        lastTapMs = now
        return count
    }

    fun isExpired(): Boolean = count == 0 || nowMs() - lastTapMs > windowMs

    fun reset() {
        count = 0
    }
}

/** A release without movement shorter than [TAP_MAX_DURATION_MS]. */
fun isTap(durationMs: Long): Boolean = durationMs in 0L until TAP_MAX_DURATION_MS

/**
 * Seek target = current + delta clamped to [0, durationMs].
 * Unknown duration (media3 `C.TIME_UNSET` included) disables the seek gesture.
 */
fun clampSeekMs(currentMs: Long, deltaMs: Long, durationMs: Long): Long? {
    if (durationMs <= 0) return null
    return (currentMs + deltaMs).coerceIn(0L, durationMs)
}

/**
 * Horizontal drag: dx/width of the duration clamped to [0, duration].
 * shortcut: the seek window equals the whole video; a configurable window can
 * be added if full-width swipes feel too coarse.
 */
fun dragSeekTargetMs(
    dxPx: Float,
    screenWidthPx: Float,
    currentMs: Long,
    durationMs: Long,
): Long? {
    if (durationMs <= 0 || screenWidthPx <= 0f) return null
    val deltaMs = ((dxPx / screenWidthPx) * durationMs).toLong()
    return (currentMs + deltaMs).coerceIn(0L, durationMs)
}

/** Left half is brightness by default; [swappedSides] mirrors the halves. */
fun isBrightnessSide(startXFraction: Float, swappedSides: Boolean): Boolean =
    (startXFraction < 0.5f) != swappedSides

/**
 * Absolute brightness 0..1 from the start value and vertical movement.
 * Upward movement (negative deltaYpx) increases brightness.
 */
fun brightnessFraction(startFraction: Float, deltaYpx: Float, screenHeightPx: Float): Float {
    if (screenHeightPx <= 0f) return startFraction.coerceIn(0f, 1f)
    return (startFraction - deltaYpx / screenHeightPx).coerceIn(0f, 1f)
}

/** Volume delta as a fraction of maximum (-1..1), same sign as brightness. */
fun volumeFractionDelta(deltaYpx: Float, screenHeightPx: Float): Float {
    if (screenHeightPx <= 0f) return 0f
    return (-deltaYpx / screenHeightPx).coerceIn(-1f, 1f)
}

/** Hold → 2x; release → the speed captured before the gesture. */
fun longPressSpeed(pressed: Boolean, originalSpeed: Float): GestureAction =
    GestureAction.Speed(if (pressed) LONG_PRESS_SPEED_FACTOR else originalSpeed)
