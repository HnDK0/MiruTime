package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.reader.Autoscroll
import dev.mirutime.feature.reader.R
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSlider
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.uikit.miruPlateBorder
import kotlin.math.roundToInt
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

/**
 * Speed pill under the reader header. It only exists while the chrome is open,
 * and the chrome itself pauses autoscroll, so the slider never races the page
 * it is scrolling. A drag is reported so the header does not auto-hide (and
 * dispose the slider) under the finger.
 */
@Composable
internal fun ReaderAutoscrollPill(
    speed: Int,
    onSpeedChange: (Int) -> Unit,
    onDragChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(MiruRadius.Plate),
        color = miruCardSurface(),
        border = miruPlateBorder(),
        tonalElevation = 0.dp,
        shadowElevation = miruCardElevation(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = MiruSize.ButtonCompact)
                .padding(start = MiruSpace.M, end = MiruSpace.S),
        ) {
            // MiruSlider already reports the value as progress, so the label
            // only has to name the control.
            Text(
                text = stringResource(R.string.rd_autoscroll_speed_value, speed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(min = MiruSize.SliderValueWidth),
            )
            Spacer(Modifier.width(MiruSpace.S))
            MiruSlider(
                value = speed.toFloat(),
                onValueChange = {
                    onDragChange(true)
                    onSpeedChange(it.roundToInt())
                },
                valueRange = Autoscroll.MinSpeed.toFloat()..Autoscroll.MaxSpeed.toFloat(),
                steps = Autoscroll.SliderSteps,
                onValueChangeFinished = { onDragChange(false) },
                contentDescription = stringResource(R.string.rd_autoscroll_speed),
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
