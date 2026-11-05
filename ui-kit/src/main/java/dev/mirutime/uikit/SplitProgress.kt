package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Approved split-progress: one 56x16 element, read on the left, total on the
// right, diagonal fill edge. It replaces "bar + raw 24/120 text" and never
// adds a second progress line.
internal data class SplitProgressState(
    val fraction: Float,
    val left: String,
    val right: String,
    val unknown: Boolean,
)

internal fun splitProgressState(read: Int?, total: Int?): SplitProgressState {
    if (total == null || total <= 0) {
        return SplitProgressState(fraction = 0f, left = "—", right = "—", unknown = true)
    }
    val readValue = (read ?: 0).coerceIn(0, total)
    return SplitProgressState(
        fraction = readValue.toFloat() / total,
        left = readValue.toString(),
        right = total.toString(),
        unknown = false,
    )
}

// Keeps both numbers inside the pill even at 2% or 90% progress: the diagonal
// edge is clamped so the left and right segments always fit their labels.
internal fun splitDividerX(
    width: Float,
    fraction: Float,
    minLeft: Float,
    minRight: Float,
): Float {
    val maxLeft = (width - minRight).coerceAtLeast(minLeft)
    return (width * fraction.coerceIn(0f, 1f)).coerceIn(minLeft, maxLeft)
}

private val SplitHeight = MiruSize.SplitProgressHeight
internal val SplitProgressWidth = MiruSize.SplitProgress
private val SlantWidth = 3.dp
private val MinLeft = 18.dp
private val MinRight = 26.dp

@Composable
fun MiruSplitProgress(
    read: Int?,
    total: Int?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val state = splitProgressState(read, total)
    val extra = LocalMiruExtraColors.current
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val shape = RoundedCornerShape(MiruRadius.Split)
    val unknown = state.unknown
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    val splitHeight = SplitHeight * fontScale
    Box(
        modifier = modifier
            .heightIn(min = splitHeight)
            .clip(shape)
            .background(track)
            .then(semanticsModifier)
            .drawBehind {
                if (unknown) {
                    drawRoundRect(
                        color = extra.muted,
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            MiruRadius.Split.toPx(),
                            MiruRadius.Split.toPx(),
                        ),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(3.dp.toPx(), 3.dp.toPx()),
                            ),
                        ),
                    )
                } else {
                    val divider = splitDividerX(
                        width = size.width,
                        fraction = state.fraction,
                        minLeft = MinLeft.toPx() * fontScale,
                        minRight = MinRight.toPx() * fontScale,
                    )
                    val slant = SlantWidth.toPx() / 2f
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(divider + slant, 0f)
                        lineTo(divider - slant, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, color = extra.accent)
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.left,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                style = MiruCardType.Num,
                color = if (unknown) extra.muted else extra.onAccent,
            )
            Text(
                text = state.right,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                style = MiruCardType.Num,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}
