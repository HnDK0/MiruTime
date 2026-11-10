package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.SentenceSpan
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.tts.api.TtsHighlight

internal const val SentenceTintAlpha = 0.10f
internal const val WordTintAlpha = 0.30f
internal val SentenceTintCorner = 6.dp

/**
 * One visual paragraph made of several sentence spans (a quoted paragraph or a
 * punctuation-merged one). It is still a single [Text], so spans never become
 * separate paragraphs; the spoken sentence is tinted by line range instead.
 */
@Composable
internal fun GroupTtsText(
    raw: String,
    groupStart: Int,
    groupSpans: List<SentenceSpan>,
    block: ReaderBlock,
    style: ReaderStyle,
    highlight: TtsHighlight?,
    isTranslated: Boolean,
    bleed: Dp,
    textStyle: TextStyle,
) {
    val text = style.paragraphText(raw, applyFirstLetterBold = false)
    val wordRange = ttsHighlightRange(raw.length, block.id, highlight, isTranslated, groupStart)
    val sentence = activeSentenceRange(groupSpans, block.id, highlight, isTranslated)
    val color = highlight?.let { Color(it.colorArgb) }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val highlightModifier = if (color != null && (wordRange != null || sentence != null)) {
        Modifier.drawBehind {
            val result = layout ?: return@drawBehind
            val bleedPx = bleed.toPx()
            if (sentence != null) {
                drawFullWidthRangeHighlight(
                    layout = result,
                    start = sentence.first - groupStart,
                    endExclusive = sentence.last + 1 - groupStart,
                    color = color.copy(alpha = SentenceTintAlpha),
                    bleedPx = bleedPx,
                )
            }
            if (wordRange != null) {
                drawRoundedRangeHighlight(result, wordRange, color.copy(alpha = WordTintAlpha))
            }
        }
    } else {
        Modifier
    }
    Text(
        text = text,
        style = textStyle,
        modifier = Modifier.fillMaxWidth().then(highlightModifier),
        onTextLayout = { result -> if (layout != result) layout = result },
    )
}

/**
 * Full-width sentence tint, one rounded rectangle per visual line the sentence
 * covers. Used inside a display group so the tint keeps the old box look.
 */
private fun DrawScope.drawFullWidthRangeHighlight(
    layout: TextLayoutResult,
    start: Int,
    endExclusive: Int,
    color: Color,
    bleedPx: Float,
) {
    val length = layout.layoutInput.text.length
    val from = start.coerceIn(0, length)
    val to = endExclusive.coerceIn(from, length)
    if (from >= to) return
    val firstLine = layout.getLineForOffset(from)
    val lastLine = layout.getLineForOffset(to - 1)
    val corner = SentenceTintCorner.toPx()
    for (line in firstLine..lastLine) {
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        drawRoundRect(
            color = color,
            topLeft = Offset(-bleedPx, top),
            size = Size(size.width + bleedPx * 2f, (bottom - top).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(corner),
        )
    }
}
