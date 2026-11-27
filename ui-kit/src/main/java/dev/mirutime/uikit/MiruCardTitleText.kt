package dev.mirutime.uikit

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.isSpecified
import kotlin.math.ceil
import kotlin.math.max

private class CardTitleLayoutCache {
    var lastFormatWidthPx: Int = -1
    var formattedTitle: String = ""
    var lastMinWidthPx: Int = -1
    var lastMaxWidthPx: Int = -1
    var layoutResult: TextLayoutResult? = null
    var drawnLayout: TextLayoutResult? = null
}

/**
 * Renders a novel/book card title across every script/language and Android API
 * level (26+) using [formatCardTitleLines] before layout so first lines never
 * strand one short word next to a large empty gap, while keeping accessibility
 * semantics clean ([title] without inserted hyphen breaks).
 */
@Composable
fun MiruCardTitleText(
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MiruCardType.Title,
    color: Color = Color.Unspecified,
    maxLines: Int = 2,
    minLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
    stroke: Stroke? = null,
    strokeColor: Color = Color.Unspecified,
) {
    // Width probes are memoized in CardTitleFormatCache, so the measurer keeps
    // only the tiny default cache instead of a 64-entry one per card.
    val textMeasurer = rememberTextMeasurer(cacheSize = 8)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val appLocaleTag = LocalMiruLocale.current.toLanguageTag()
    val mergedStyle = LocalTextStyle.current.merge(style)
    val resolvedColor = color.takeOrElse {
        mergedStyle.color.takeOrElse { LocalContentColor.current }
    }
    val resolvedStrokeColor = strokeColor.takeOrElse { resolvedColor }

    val layoutStyle = remember(mergedStyle, textAlign, title, appLocaleTag) {
        mergedStyle.copy(
            textAlign = textAlign,
            localeList = cardTitleLocaleList(
                title = title,
                fallback = mergedStyle.localeList,
                appLocaleTag = appLocaleTag,
            ),
            lineBreak = LineBreak.Simple,
            // Pre-fitted lines already insert explicit `-` / compound wraps; disabling
            // platform Minikin hyphens stops OEM `.hyb` rules from double-hyphenating.
            hyphens = Hyphens.None,
            drawStyle = null,
        )
    }

    val safeMinLines = minLines.coerceAtLeast(1)
    val safeMaxLines = maxLines.coerceAtLeast(safeMinLines)
    val styleKey = remember(layoutStyle) { TitleStyleKey.of(layoutStyle) }
    val cache = remember(title, safeMaxLines, layoutStyle, density, layoutDirection) {
        CardTitleLayoutCache()
    }

    Layout(
        modifier = modifier
            .semantics { text = AnnotatedString(title) }
            .drawBehind {
                val layout = cache.drawnLayout ?: cache.layoutResult ?: return@drawBehind
                if (stroke != null) {
                    drawText(
                        textLayoutResult = layout,
                        color = resolvedStrokeColor,
                        drawStyle = stroke,
                    )
                }
                drawText(
                    textLayoutResult = layout,
                    color = resolvedColor,
                )
            },
        measurePolicy = { _, constraints ->
            val maxWidthPx = if (constraints.hasBoundedWidth) {
                constraints.maxWidth.coerceAtLeast(1)
            } else {
                Int.MAX_VALUE
            }

            if (cache.lastFormatWidthPx != maxWidthPx) {
                cache.formattedTitle = if (maxWidthPx == Int.MAX_VALUE) {
                    title
                } else {
                    CardTitleFormatCache.format(
                        title = title,
                        maxLines = safeMaxLines,
                        widthPx = maxWidthPx,
                        style = styleKey,
                    ) { candidate ->
                        val probe = textMeasurer.measure(
                            text = candidate,
                            style = layoutStyle,
                            maxLines = 1,
                            softWrap = false,
                        )
                        val lineWidth = ceil(probe.getLineRight(0) - probe.getLineLeft(0)).toInt()
                        max(probe.size.width, lineWidth)
                    }
                }
                cache.lastFormatWidthPx = maxWidthPx
                cache.lastMaxWidthPx = -1
            }

            if (cache.layoutResult == null ||
                cache.lastMaxWidthPx != constraints.maxWidth ||
                cache.lastMinWidthPx != constraints.minWidth
            ) {
                val measureConstraints = Constraints(
                    minWidth = constraints.minWidth,
                    maxWidth = constraints.maxWidth,
                )
                cache.layoutResult = textMeasurer.measure(
                    text = cache.formattedTitle,
                    style = layoutStyle,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = true,
                    maxLines = safeMaxLines,
                    constraints = measureConstraints,
                )
                cache.lastMaxWidthPx = constraints.maxWidth
                cache.lastMinWidthPx = constraints.minWidth
            }

            val measured = cache.layoutResult!!
            val width = constraints.constrainWidth(measured.size.width)
            val minLinesHeightPx = if (safeMinLines > 1 && measured.lineCount < safeMinLines) {
                val linePx = if (layoutStyle.lineHeight.isSpecified) {
                    with(density) { layoutStyle.lineHeight.toPx() }
                } else {
                    measured.size.height.toFloat() / measured.lineCount.coerceAtLeast(1)
                }
                ceil(linePx * safeMinLines).toInt()
            } else {
                0
            }
            val height = constraints.constrainHeight(max(measured.size.height, minLinesHeightPx))
            layout(width, height) {
                cache.drawnLayout = measured
            }
        },
    )
}
