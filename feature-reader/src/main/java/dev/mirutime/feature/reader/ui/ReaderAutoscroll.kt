package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import dev.mirutime.feature.reader.Autoscroll
import kotlinx.coroutines.isActive

/**
 * Feed autoscroll. The caller owns the pause conditions (touch, chrome, TTS,
 * lifecycle); this loop only turns elapsed time into pixels and stops when the
 * loaded feed runs out.
 */
@Composable
fun ReaderAutoscrollEffect(
    enabled: Boolean,
    listState: LazyListState,
    speed: Int,
    onReachedEnd: () -> Unit,
) {
    val density = LocalDensity.current.density
    val currentOnReachedEnd by rememberUpdatedState(onReachedEnd)

    LaunchedEffect(enabled, listState, speed, density) {
        if (!enabled) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val elapsedMs = (now - lastFrame) / 1_000_000L
            lastFrame = now
            val requested = Autoscroll.frameDistancePx(speed, elapsedMs, density)
            if (requested <= 0f) continue
            // The feed may be Ready before its first layout; do not treat that
            // as the end of the chapter.
            if (listState.layoutInfo.totalItemsCount == 0) continue
            val consumed = listState.scrollBy(requested)
            if (Autoscroll.reachedEnd(requested, consumed, listState.canScrollForward)) {
                currentOnReachedEnd()
                break
            }
        }
    }
}
