package dev.mirutime.app.features.book.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// An 8dp thin line inside a 48dp finger lane. The rail spans the whole list
// height with no top/bottom inset, so the first and last chapter sit flush
// with the rail ends.
internal val ChapterScrollbarLaneWidth = MiruSize.Touch
internal val ChapterScrollbarVisualWidth = MiruSpace.S

// The rail is inset from the right edge so the row's trailing glyphs keep a
// full 16dp gutter: icons end at canvas-16, the rail starts there too and
// fills canvas-8..canvas-8.
internal val ChapterScrollbarEdgeInset = MiruSpace.S

// Row end gutter: trailing status icons stop 16dp before the right edge; the
// rail is inset 8dp and occupies the remaining 8dp, so icons and line never
// overlap. The 48dp lane still extends left for drags but only the track
// claims touch-down.
internal val ChapterRowEndGutter = MiruSpace.L

// Which part of the right edge a touch belongs to. TRACK is the visible 8dp
// line (tap/drag/press); LANE is the rest of the 48dp finger zone (vertical
// drags only, taps fall through to the row and its icons); NONE is content.
internal enum class ChapterScrollbarTouchZone { NONE, LANE, TRACK }

internal fun chapterScrollbarTouchZone(
    x: Float,
    canvasWidthPx: Float,
    laneWidthPx: Float,
    visualWidthPx: Float,
    edgeInsetPx: Float,
): ChapterScrollbarTouchZone {
    val trackStart = canvasWidthPx - edgeInsetPx - visualWidthPx
    if (x >= trackStart) return ChapterScrollbarTouchZone.TRACK
    val laneStart = canvasWidthPx - laneWidthPx
    if (x >= laneStart) return ChapterScrollbarTouchZone.LANE
    return ChapterScrollbarTouchZone.NONE
}

// Right-edge scrollbar for the bounded chapter window. Drawing only: the
// gesture lives on the parent that also hosts the list, otherwise the 48dp
// lane would sit above the row icons and swallow their taps. The 8dp track
// claims touch-down; the rest of the lane only claims a real vertical drag.
@Composable
internal fun ChapterScrollbar(
    state: LazyListState,
    dragThumbTop: State<Float?>,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val density = LocalDensity.current
    val description = stringResource(R.string.book_cd_ch_scrollbar)
    val minThumbPx = with(density) { MiruSize.Control.toPx() }
    val visualWidthPx = with(density) { ChapterScrollbarVisualWidth.toPx() }
    val edgeInsetPx = with(density) { ChapterScrollbarEdgeInset.toPx() }

    // Item count changes only when the filtered list changes; scroll position
    // is read in the draw phase so a drag does not recompose.
    val itemCount by remember(state) {
        derivedStateOf { state.layoutInfo.totalItemsCount }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(ChapterScrollbarLaneWidth)
                .fillMaxHeight()
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = (state.firstVisibleItemIndex + 1).toFloat(),
                        range = 1f..itemCount.coerceAtLeast(1).toFloat(),
                    )
                },
        ) {
            if (itemCount <= 0) return@Canvas
            val thumbHeight = chapterScrollbarThumbHeight(
                trackHeight = size.height,
                visibleItemCount = state.layoutInfo.visibleItemsInfo.size,
                itemCount = itemCount,
                minThumbHeight = minThumbPx,
            )
            if (thumbHeight <= 0f) return@Canvas
            val thumbTop = dragThumbTop.value ?: chapterScrollbarThumbTop(
                trackHeight = size.height,
                thumbHeight = thumbHeight,
                firstVisibleIndex = state.firstVisibleItemIndex,
                itemCount = itemCount,
            )
            val line = chapterScrollbarLine(
                canvasWidthPx = size.width,
                edgeInsetPx = edgeInsetPx,
                visualWidthPx = visualWidthPx,
            )

            drawRoundRect(
                color = extra.muted.copy(alpha = 0.20f),
                topLeft = Offset(line.left, 0f),
                size = Size(line.width, size.height),
                cornerRadius = CornerRadius(line.radius),
            )
            drawRoundRect(
                color = extra.accent.copy(alpha = 0.85f),
                topLeft = Offset(line.left, thumbTop),
                size = Size(line.width, thumbHeight),
                cornerRadius = CornerRadius(line.radius),
            )
        }
    }
}

// === Gesture (parent modifier) ===
// Lives on the Box that hosts both the list and the rail. A tap or drag on the
// 8dp track is claimed immediately in the Initial pass, before the row can
// click and before the list can scroll. The rest of the 48dp lane is claimed
// only after a vertical slop, so a tap there still reaches the row and its
// trailing icons. A cancelled gesture clears the thumb override.
@Composable
internal fun Modifier.chapterScrollbarDrag(
    state: LazyListState,
    enabled: Boolean,
    onDragThumbTopChange: (Float?) -> Unit,
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val laneWidthPx = with(density) { ChapterScrollbarLaneWidth.toPx() }
    val visualWidthPx = with(density) { ChapterScrollbarVisualWidth.toPx() }
    val edgeInsetPx = with(density) { ChapterScrollbarEdgeInset.toPx() }
    val minThumbPx = with(density) { MiruSize.Control.toPx() }
    val dragChange by rememberUpdatedState(onDragThumbTopChange)
    return pointerInput(state, laneWidthPx, visualWidthPx, edgeInsetPx, minThumbPx) {
        var scrollJob: Job? = null
        fun jumpTo(index: Int) {
            scrollJob?.cancel()
            scrollJob = scope.launch { state.scrollToItem(index) }
        }
        try {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                val zone = chapterScrollbarTouchZone(
                    x = down.position.x,
                    canvasWidthPx = size.width.toFloat(),
                    laneWidthPx = laneWidthPx,
                    visualWidthPx = visualWidthPx,
                    edgeInsetPx = edgeInsetPx,
                )
                if (zone == ChapterScrollbarTouchZone.NONE) return@awaitEachGesture

                val trackHeight = size.height.toFloat()
                if (trackHeight <= 0f) return@awaitEachGesture
                val count = state.layoutInfo.totalItemsCount
                if (count <= 0) return@awaitEachGesture

                val layoutAtDown = state.layoutInfo
                val thumbHeight = chapterScrollbarThumbHeight(
                    trackHeight = trackHeight,
                    visibleItemCount = layoutAtDown.visibleItemsInfo.size,
                    itemCount = count,
                    minThumbHeight = minThumbPx,
                )
                val thumbTop = chapterScrollbarThumbTop(
                    trackHeight = trackHeight,
                    thumbHeight = thumbHeight,
                    firstVisibleIndex = state.firstVisibleItemIndex,
                    itemCount = count,
                )
                val grabbedThumb = down.position.y in thumbTop..(thumbTop + thumbHeight)
                val dragOffset = if (grabbedThumb) {
                    down.position.y - thumbTop
                } else {
                    thumbHeight / 2f
                }
                val slop = viewConfiguration.touchSlop
                var dragging = false
                var lastIndex = -1
                var claimed = zone == ChapterScrollbarTouchZone.TRACK
                if (claimed) down.consume()

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (!dragging) {
                        val dy = change.position.y - down.position.y
                        if (abs(dy) <= slop) {
                            if (claimed) change.consume()
                            continue
                        }
                        dragging = true
                        claimed = true
                    }
                    // Claim every drag frame before the list's scrollable sees
                    // it, then drive the list from the rail position.
                    change.consume()
                    val maxTop = (trackHeight - thumbHeight).coerceAtLeast(0f)
                    val top = (change.position.y - dragOffset).coerceIn(0f, maxTop)
                    dragChange(top)
                    val index = chapterScrollbarIndexForThumbTop(
                        thumbTop = top,
                        trackHeight = trackHeight,
                        thumbHeight = thumbHeight,
                        itemCount = count,
                    )
                    if (index != lastIndex) {
                        lastIndex = index
                        // The list reads the latest index; a slow previous move
                        // is cancelled instead of queuing one job per event.
                        jumpTo(index)
                    }
                }

                if (dragging) {
                    dragChange(null)
                } else if (claimed && !grabbedThumb) {
                    val index = chapterScrollbarIndexForTap(
                        tapY = down.position.y,
                        trackHeight = trackHeight,
                        thumbHeight = thumbHeight,
                        itemCount = count,
                    )
                    jumpTo(index)
                }
            }
        } finally {
            // A cancelled gesture (e.g. the system nav swipe) must not leave
            // the thumb frozen where the finger stopped.
            dragChange(null)
        }
    }
}

// === Drag math (pure) ===
// All values are pixels; the composable only supplies density and viewport
// height. Tests exercise these without Compose.

internal data class ChapterScrollbarLine(
    val left: Float,
    val width: Float,
    val radius: Float,
)

// Round-capped line hugging the right edge, inset only horizontally by
// [edgeInsetPx]. Vertical placement is the caller's: the rail is drawn from 0
// to the full canvas height and the thumb from its computed top, so there is
// never a top/bottom inset.
internal fun chapterScrollbarLine(
    canvasWidthPx: Float,
    edgeInsetPx: Float,
    visualWidthPx: Float,
): ChapterScrollbarLine = ChapterScrollbarLine(
    left = canvasWidthPx - edgeInsetPx - visualWidthPx,
    width = visualWidthPx,
    radius = visualWidthPx / 2f,
)

internal fun chapterScrollbarThumbHeight(
    trackHeight: Float,
    visibleItemCount: Int,
    itemCount: Int,
    minThumbHeight: Float,
): Float {
    if (trackHeight <= 0f || itemCount <= 0 || visibleItemCount <= 0) return 0f
    val min = minThumbHeight.coerceIn(0f, trackHeight)
    if (visibleItemCount >= itemCount) return trackHeight
    val proportional = trackHeight * visibleItemCount.toFloat() / itemCount.toFloat()
    return proportional.coerceAtLeast(min).coerceAtMost(trackHeight)
}

internal fun chapterScrollbarThumbTop(
    trackHeight: Float,
    thumbHeight: Float,
    firstVisibleIndex: Int,
    itemCount: Int,
): Float {
    if (trackHeight <= 0f || thumbHeight <= 0f || itemCount <= 1) return 0f
    if (thumbHeight >= trackHeight) return 0f
    val maxTop = trackHeight - thumbHeight
    val maxIndex = itemCount - 1
    val fraction = firstVisibleIndex.coerceIn(0, maxIndex).toFloat() / maxIndex.toFloat()
    return maxTop * fraction
}

internal fun chapterScrollbarIndexForThumbTop(
    thumbTop: Float,
    trackHeight: Float,
    thumbHeight: Float,
    itemCount: Int,
): Int {
    if (itemCount <= 1) return 0
    val maxTop = (trackHeight - thumbHeight).coerceAtLeast(0f)
    if (maxTop <= 0f) return 0
    val fraction = thumbTop.coerceIn(0f, maxTop) / maxTop
    return (fraction * (itemCount - 1)).roundToInt().coerceIn(0, itemCount - 1)
}

internal fun chapterScrollbarIndexForTap(
    tapY: Float,
    trackHeight: Float,
    thumbHeight: Float,
    itemCount: Int,
): Int {
    if (itemCount <= 1) return 0
    val maxTop = (trackHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (tapY - thumbHeight / 2f).coerceIn(0f, maxTop)
    return chapterScrollbarIndexForThumbTop(
        thumbTop = thumbTop,
        trackHeight = trackHeight,
        thumbHeight = thumbHeight,
        itemCount = itemCount,
    )
}
