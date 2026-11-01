package dev.mirutime.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// MiruTime's compact slider: a 2dp pill track, a 15dp thumb and a 48dp touch
// lane. Material's thumb and overlay are much heavier than the rest of the
// settings rows, so this keeps the same progress semantics without the bulk.
@Composable
fun MiruSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val density = LocalDensity.current
    val thumbRadius = with(density) { 7.5.dp.toPx() }
    val trackHeight = with(density) { 2.dp.toPx() }
    val activeColor = if (enabled) extra.accent else extra.muted
    val trackColor = extra.muted.copy(alpha = 0.30f)
    var dragValue by remember { mutableStateOf<Float?>(null) }

    fun valueAt(x: Float, width: Float): Float {
        val usableWidth = (width - thumbRadius * 2f).coerceAtLeast(1f)
        val fraction = ((x - thumbRadius) / usableWidth).coerceIn(0f, 1f)
        return snapToStep(
            value = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start),
            range = valueRange,
            steps = steps,
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(MiruSize.Touch)
            .semantics {
                if (!enabled) disabled()
                if (contentDescription != null) this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange,
                )
                setProgress { target ->
                    currentOnValueChange(
                        snapToStep(
                            value = target.coerceIn(valueRange.start, valueRange.endInclusive),
                            range = valueRange,
                            steps = steps,
                        ),
                    )
                    currentOnValueChangeFinished?.invoke()
                    true
                }
            }
            .pointerInput(valueRange, steps, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Never commit on touch-down. A tap must not move the
                    // thumb, and a scroll that starts on the 48dp lane must
                    // reach the parent untouched. The gesture turns into a
                    // slider drag only when horizontal movement crosses touch
                    // slop and is larger than the vertical movement; from that
                    // change on we consume, so the scroll never starts.
                    val slop = viewConfiguration.touchSlop
                    var start: PointerInputChange? = null
                    while (start == null) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (change.isConsumed) break
                        when (
                            sliderGesture(
                                dx = change.position.x - down.position.x,
                                dy = change.position.y - down.position.y,
                                slop = slop,
                            )
                        ) {
                            SliderGesture.Drag -> start = change
                            SliderGesture.Parent -> break
                            SliderGesture.Pending -> if (!change.pressed) {
                                // A tap (no slop crossed) sets the value
                                // under the finger; a scroll still goes to
                                // the parent untouched.
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (abs(dx) <= slop && abs(dy) <= slop) {
                                    val tapped = valueAt(
                                        down.position.x,
                                        size.width.toFloat(),
                                    )
                                    currentOnValueChange(tapped)
                                    currentOnValueChangeFinished?.invoke()
                                }
                                break
                            }
                        }
                    }
                    val first = start ?: return@awaitEachGesture
                    first.consume()
                    var active = valueAt(first.position.x, size.width.toFloat())
                    dragValue = active
                    currentOnValueChange(active)
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            val next = valueAt(change.position.x, size.width.toFloat())
                            if (next != active) {
                                active = next
                                dragValue = next
                                currentOnValueChange(next)
                            }
                        }
                    } finally {
                        dragValue = null
                        currentOnValueChangeFinished?.invoke()
                    }
                }
            },
    ) {
        val trackLeft = thumbRadius
        val trackWidth = (size.width - thumbRadius * 2f).coerceAtLeast(0f)
        val centerY = size.height / 2f
        val trackTop = centerY - trackHeight / 2f
        val span = valueRange.endInclusive - valueRange.start
        val fraction = if (span == 0f) 0f else ((dragValue ?: value) - valueRange.start) / span
        val activeWidth = trackWidth * fraction.coerceIn(0f, 1f)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(trackLeft, trackTop),
                size = Size(activeWidth, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f),
            )
        }
        drawCircle(
            color = activeColor,
            radius = thumbRadius,
            center = Offset(trackLeft + activeWidth, centerY),
        )
    }
}

/**
 * What a touch that started on the slider lane should do, decided once per
 * movement: [Pending] until an axis crosses touch slop, [Drag] when the
 * movement is horizontal and dominates, [Parent] when it is vertical (or
 * diagonal with a vertical lead) and must be left to the scroll.
 */
internal enum class SliderGesture { Pending, Drag, Parent }

internal fun sliderGesture(dx: Float, dy: Float, slop: Float): SliderGesture = when {
    abs(dx) > slop && abs(dx) > abs(dy) -> SliderGesture.Drag
    abs(dx) > slop || abs(dy) > slop -> SliderGesture.Parent
    else -> SliderGesture.Pending
}

private fun snapToStep(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    if (steps <= 0) return value.coerceIn(range.start, range.endInclusive)
    val intervals = steps + 1
    val stepSize = (range.endInclusive - range.start) / intervals
    if (stepSize <= 0f) return range.start
    val snapped = range.start + ((value - range.start) / stepSize).roundToInt() * stepSize
    return snapped.coerceIn(range.start, range.endInclusive)
}
