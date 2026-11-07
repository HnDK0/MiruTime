package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
private val MaxTextGap = 10.dp
private val MinTextGap = 6.dp
private const val MinFontSizeSp = 8f
private const val MaxFontSizeSp = 10f

private data class SplitTextFit(
    val style: TextStyle,
    val leftWidth: Float,
    val rightWidth: Float,
    /** Space between a number and the diagonal edge on one side. */
    val gapPx: Float,
)

/**
 * Splits the free width into four equal gaps around the two numbers and the
 * two slanted edges, so the divider never touches a digit.
 */
internal fun splitTextGap(
    width: Float,
    leftWidth: Float,
    rightWidth: Float,
    slantWidth: Float,
    maxGap: Float,
): Float = ((width - leftWidth - rightWidth - slantWidth * 2f) / 4f)
    .coerceIn(0f, maxGap)

@Composable
private fun rememberSplitTextFit(
    left: String,
    right: String,
    widthPx: Float,
): SplitTextFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxGapPx = with(density) { MaxTextGap.toPx() }
    val minGapPx = with(density) { MinTextGap.toPx() }
    val slantPx = with(density) { SlantWidth.toPx() }
    return remember(left, right, widthPx, density.fontScale, measurer) {
        var size = MaxFontSizeSp
        var leftWidth = 0f
        var rightWidth = 0f
        var gapPx = 0f
        while (size >= MinFontSizeSp) {
            val style = MiruCardType.Num.copy(fontSize = size.sp)
            leftWidth = measurer.measure(AnnotatedString(left), style = style).size.width.toFloat()
            rightWidth = measurer.measure(AnnotatedString(right), style = style).size.width.toFloat()
            // Four gaps (outer + inner on both sides) and two slanted halves.
            gapPx = splitTextGap(
                width = widthPx,
                leftWidth = leftWidth,
                rightWidth = rightWidth,
                slantWidth = slantPx,
                maxGap = maxGapPx,
            )
            if (gapPx >= minGapPx || size == MinFontSizeSp) break
            size -= 1f
        }
        SplitTextFit(
            style = MiruCardType.Num.copy(fontSize = size.coerceAtLeast(MinFontSizeSp).sp),
            leftWidth = leftWidth,
            rightWidth = rightWidth,
            gapPx = gapPx,
        )
    }
}

@Composable
fun MiruSplitProgress(
    read: Int?,
    total: Int?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val state = splitProgressState(read, total)
    val extra = LocalMiruExtraColors.current
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val shape = RoundedCornerShape(MiruRadius.Split)
    val unknown = state.unknown
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val layoutDirection = LocalLayoutDirection.current
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    val splitHeight = SplitHeight * fontScale
    BoxWithConstraints(
        modifier = modifier
            .heightIn(min = splitHeight)
            .clip(shape)
            .background(trackColor)
            .then(semanticsModifier),
    ) {
        val widthPx = constraints.maxWidth
            .takeIf { it != Constraints.Infinity }
            ?.toFloat()
            ?: 0f
        val fit = rememberSplitTextFit(state.left, state.right, widthPx)
        val density = LocalDensity.current
        val slantPx = with(density) { SlantWidth.toPx() }
        val fraction = if (unknown) 0.5f else state.fraction
        val divider = splitDividerX(
            width = widthPx,
            fraction = fraction,
            minLeft = fit.leftWidth + fit.gapPx * 2f + slantPx,
            minRight = fit.rightWidth + fit.gapPx * 2f + slantPx,
        )
        // Keep the paint and the text segments on the same clamped edge.
        val usableDivider = divider.coerceIn(0f, widthPx)
        val leftWeight = if (widthPx > 0f) {
            (usableDivider / widthPx).coerceIn(0.01f, 0.99f)
        } else {
            0.5f
        }
        val rightWeight = 1f - leftWeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (unknown) {
                        drawRoundRect(
                            color = extra.muted,
                            topLeft = Offset.Zero,
                            size = Size(size.width, size.height),
                            cornerRadius = CornerRadius(
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
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        val startX = if (rtl) size.width else 0f
                        val edge = if (rtl) size.width - usableDivider else usableDivider
                        val direction = if (rtl) -1f else 1f
                        val slant = SlantWidth.toPx() / 2f
                        val path = Path().apply {
                            moveTo(startX, 0f)
                            lineTo(edge + direction * slant, 0f)
                            lineTo(edge - direction * slant, size.height)
                            lineTo(startX, size.height)
                            close()
                        }
                        drawPath(path, color = extra.accent)
                    }
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(leftWeight)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.left,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = fit.style,
                        color = if (unknown) extra.muted else extra.onAccent,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(rightWeight)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.right,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        style = fit.style,
                        color = if (unknown) extra.muted else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
