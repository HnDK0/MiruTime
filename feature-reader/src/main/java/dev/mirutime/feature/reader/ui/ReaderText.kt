package dev.mirutime.feature.reader.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mirutime.feature.reader.R
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderSplit
import dev.mirutime.app.features.reader.document.SentenceSplitter
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.paged.parallelPairs
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.uikit.MiruRadius
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.min

@Composable
fun ReaderBlockView(
    block: ReaderBlock,
    style: ReaderStyle,
    translated: String? = null,
    parallel: Boolean = false,
    translationFirst: Boolean = true,
    ttsHighlight: TtsHighlight? = null,
    onSearchSelection: ((String) -> Unit)? = null,
    bleed: Dp = 0.dp,
) {
    val searchable = SelectionSearch.isSearchable(block.kind)
    val selectionState = rememberSelectionState()
    val searchLabel = stringResource(R.string.rd_selection_search)
    val body: @Composable () -> Unit = {
        when {
            translated != null && searchable && parallel ->
                ParallelTranslation(block, translated, style, translationFirst, ttsHighlight, bleed)
            translated != null && searchable ->
                TranslatedText(block.kind, translated, style, block, ttsHighlight, bleed)
            else -> OriginalText(block, style, ttsHighlight, bleed)
        }
    }
    // Long press selects everywhere; short taps are handled at the Initial
    // pass, so they never reach selection as taps or double taps.
    // The active TTS paragraph gets a soft block background even when the
    // optional word-range highlight is disabled. Split paragraphs instead tint
    // only the sentence being spoken (see sentenceHighlightModifier), so one
    // pre-split paragraph never lights up as several.
    val splitParagraph = ReaderSplit.shouldSplit(block, style.splitSentences)
    val activeBlock = ttsHighlight?.blockId == block.id
    val blockTint = activeBlock && !splitParagraph
    val content: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (blockTint) {
                        Modifier.drawBehind {
                            val bleedPx = bleed.toPx()
                            drawRoundRect(
                                color = Color(ttsHighlight.colorArgb).copy(alpha = 0.10f),
                                topLeft = Offset(-bleedPx, 0f),
                                size = Size(size.width + bleedPx * 2f, size.height),
                                cornerRadius = CornerRadius(6.dp.toPx()),
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 2.dp),
        ) { body() }
    }
    if (style.selectableText) {
        val menuModifier = if (searchable && onSearchSelection != null) {
            Modifier.selectionSearchMenu(selectionState, searchLabel, onSearchSelection)
        } else {
            Modifier
        }
        SelectionContainer(state = selectionState, modifier = menuModifier) { content() }
    } else {
        content()
    }
}

private object SelectionSearchMenuKey

/**
 * Adds Search web next to the system Copy/Select all items. Search is useful
 * for both original and already-translated text; an empty selection builds no
 * custom item at all.
 */
fun Modifier.selectionSearchMenu(
    state: SelectionState,
    label: String,
    onSearch: (String) -> Unit,
): Modifier = appendTextContextMenuComponents {
    val selected = SelectionSearch.normalize(state.selectedTexts)
    if (selected.isNotEmpty()) {
        item(key = SelectionSearchMenuKey, label = label) {
            val text = SelectionSearch.normalize(state.selectedTexts)
            close()
            state.clear()
            if (text.isNotEmpty()) onSearch(text)
        }
    }
}

@Composable
private fun OriginalText(
    block: ReaderBlock,
    style: ReaderStyle,
    highlight: TtsHighlight?,
    bleed: Dp,
) {
    when (block.kind) {
        BlockKind.Heading -> TtsHighlightedText(
            raw = block.original,
            block = block,
            style = style,
            highlight = highlight,
            applyFirstLetterBold = false,
            textStyle = style.headingTextStyle(),
        )
        BlockKind.Divider -> Text("· · ·", style = style.toTextStyle())
        BlockKind.Image -> {
            val ref = block.imageRef
            if (ref.isNullOrBlank()) {
                Text(block.original.ifBlank { "Image" }, style = style.toTextStyle())
            } else {
                AsyncImage(
                    model = ref,
                    contentDescription = block.original.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 520.dp)
                        .clip(RoundedCornerShape(MiruRadius.Chip)),
                )
            }
        }
        BlockKind.Paragraph -> {
            val spans = remember(block.sentences, block.original) { ReaderSplit.originalSpans(block) }
            if (ReaderSplit.shouldSplit(block, style.splitSentences) && spans.isNotEmpty()) {
                // Keep one Text per sentence even while TTS highlights a range.
                // Collapsing to a single paragraph Text on highlight start made
                // the whole block re-layout and blink at every chunk boundary.
                Column(verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp)) {
                    spans.forEach { span ->
                        val rawStart = span.start.coerceIn(0, block.original.length)
                        val rawEnd = span.end.coerceIn(rawStart, block.original.length)
                        val sentence = block.original.substring(rawStart, rawEnd)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    sentenceHighlightModifier(
                                        blockId = block.id,
                                        start = rawStart,
                                        end = rawEnd,
                                        isTranslated = false,
                                        highlight = highlight,
                                        bleed = bleed,
                                    ),
                                ),
                        ) {
                            TtsHighlightedText(
                                raw = sentence,
                                block = block,
                                style = style,
                                highlight = highlight,
                                applyFirstLetterBold = false,
                                baseOffset = rawStart,
                                textStyle = style.toTextStyle(),
                            )
                        }
                    }
                }
            } else if (highlight != null) {
                TtsHighlightedText(
                    raw = block.original,
                    block = block,
                    style = style,
                    highlight = highlight,
                    textStyle = style.toTextStyle(),
                )
            } else {
                Text(
                    text = style.paragraphText(block.original),
                    style = style.toTextStyle(),
                )
            }
        }
    }
}

@Composable
private fun TranslatedText(
    kind: BlockKind,
    text: String,
    style: ReaderStyle,
    block: ReaderBlock? = null,
    highlight: TtsHighlight? = null,
    bleed: Dp = 0.dp,
) {
    val textStyle = if (kind == BlockKind.Heading) style.headingTextStyle() else style.toTextStyle()
    // Only paragraph splitting gets the per-sentence tint; other kinds keep the
    // whole-block tint from the caller.
    if (block != null && ReaderSplit.shouldSplit(block, style.splitSentences)) {
        val spans = remember(text) { SentenceSplitter.spans(text) }
        Column(verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp)) {
            spans.forEach { span ->
                val sentence = text.substring(span.start, span.end)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            sentenceHighlightModifier(
                                blockId = block.id,
                                start = span.start,
                                end = span.end,
                                isTranslated = true,
                                highlight = highlight,
                                bleed = bleed,
                            ),
                        ),
                ) {
                    TtsHighlightedText(
                        raw = sentence,
                        block = block,
                        style = style,
                        highlight = highlight,
                        applyFirstLetterBold = false,
                        isTranslated = true,
                        baseOffset = span.start,
                        textStyle = textStyle,
                    )
                }
            }
        }
    } else if (block != null) {
        TtsHighlightedText(
            raw = text,
            block = block,
            style = style,
            highlight = highlight,
            applyFirstLetterBold = false,
            isTranslated = true,
            textStyle = textStyle,
        )
    } else {
        Text(
            text = style.paragraphText(text, applyFirstLetterBold = false),
            style = textStyle,
        )
    }
}

/** One painted copy of a Feed parallel pair; start offsets point into the full strings. */
internal data class FeedPairCopy(
    val translated: String,
    val translatedStart: Int,
    val original: String,
    val originalStart: Int,
)

/**
 * Feed parallel plan. Split paragraphs use the very same sentence pairing as
 * paged mode, so original and translated keep joint boundaries; every other
 * block stays one whole-block pair. Both-blank pairs are dropped and blank
 * sides are skipped when painting, matching paged mode.
 */
internal fun feedPairPlan(
    block: ReaderBlock,
    translated: String,
    splitSentences: Boolean,
): List<FeedPairCopy> =
    parallelPairs(block, translated, splitSentences).mapNotNull { pair ->
        val primary = translated.sliceSpan(pair.translated.start, pair.translated.end)
        val secondary = block.original.sliceSpan(pair.original.start, pair.original.end)
        if (primary.isBlank() && secondary.isBlank()) {
            null
        } else {
            FeedPairCopy(primary, pair.translated.start, secondary, pair.original.start)
        }
    }

private fun String.sliceSpan(start: Int, end: Int): String {
    val from = start.coerceIn(0, length)
    val to = end.coerceIn(from, length)
    return substring(from, to)
}

/**
 * Translation and original stacked vertically, translated first or second.
 * The original is secondary: smaller and dimmed. Split paragraphs pair each
 * translated sentence with its original sentence (paged pairing); every other
 * block keeps the whole-block stack.
 */
@Composable
private fun ParallelTranslation(
    block: ReaderBlock,
    translated: String,
    style: ReaderStyle,
    translationFirst: Boolean,
    highlight: TtsHighlight? = null,
    bleed: Dp = 0.dp,
) {
    val translatedStyle = if (block.kind == BlockKind.Heading) {
        style.headingTextStyle()
    } else {
        style.toTextStyle()
    }
    val originalStyle = style.pagedSecondaryTextStyle(block.kind)
    // shortcut: mirrors paged PagedAltGap; share a token if it ever changes.
    val copyGap = 6.dp
    val pairMode = ReaderSplit.shouldSplit(block, style.splitSentences)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp),
    ) {
        if (pairMode) {
            val pairPlan = remember(block.sentences, translated, style.splitSentences) {
                feedPairPlan(block, translated, style.splitSentences)
            }
            pairPlan.forEach { copy ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(parallelPairHighlightModifier(block.id, copy, highlight, bleed)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(copyGap)) {
                        ParallelPairCopy(
                            copy = copy,
                            block = block,
                            style = style,
                            translatedStyle = translatedStyle,
                            originalStyle = originalStyle,
                            translationFirst = translationFirst,
                            highlight = highlight,
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(copyGap)) {
                val translatedBlock: @Composable () -> Unit = {
                    SplitTtsText(
                        text = translated,
                        block = block,
                        style = style,
                        textStyle = translatedStyle,
                        highlight = highlight,
                        isTranslated = true,
                    )
                }
                val originalBlock: @Composable () -> Unit = {
                    SplitTtsText(
                        text = block.original,
                        block = block,
                        style = style,
                        textStyle = originalStyle,
                        highlight = highlight,
                        isTranslated = false,
                    )
                }
                if (translationFirst) {
                    translatedBlock()
                    originalBlock()
                } else {
                    originalBlock()
                    translatedBlock()
                }
            }
        }
    }
}

@Composable
private fun ParallelPairCopy(
    copy: FeedPairCopy,
    block: ReaderBlock,
    style: ReaderStyle,
    translatedStyle: TextStyle,
    originalStyle: TextStyle,
    translationFirst: Boolean,
    highlight: TtsHighlight?,
) {
    val translatedCopy: @Composable () -> Unit = {
        if (copy.translated.isNotBlank()) {
            TtsHighlightedText(
                raw = copy.translated,
                block = block,
                style = style,
                highlight = highlight,
                applyFirstLetterBold = false,
                isTranslated = true,
                baseOffset = copy.translatedStart,
                textStyle = translatedStyle,
            )
        }
    }
    val originalCopy: @Composable () -> Unit = {
        if (copy.original.isNotBlank()) {
            TtsHighlightedText(
                raw = copy.original,
                block = block,
                style = style,
                highlight = highlight,
                applyFirstLetterBold = false,
                isTranslated = false,
                baseOffset = copy.originalStart,
                textStyle = originalStyle,
            )
        }
    }
    if (translationFirst) {
        translatedCopy()
        originalCopy()
    } else {
        originalCopy()
        translatedCopy()
    }
}

/**
 * Metrics of the chapter header shared by every reader mode.
 *
 * The paged mode reserves the header height before it paints anything, so the
 * numbers that decide the look live here and nowhere else: the paginator
 * measures the very same title with the very same style and width, then the
 * composable is drawn inside the space that was measured.
 */
object ChapterHeaderMetrics {
    val topPadding = 28.dp
    val ornamentSize = 26.dp
    val ornamentGap = 12.dp
    val badgeGap = 8.dp
    val badgeHeight = 24.dp
    val bottomPadding = 12.dp
    const val titleFontSizeSp = 22
    const val titleLineHeightSp = 30
    const val ornamentAlpha = 0.55f
    const val badgeAlpha = 0.07f
}

/** Chapter title: reader font and colour, but a fixed display size. */
fun ReaderStyle.chapterTitleStyle(): TextStyle = toTextStyle().copy(
    fontSize = ChapterHeaderMetrics.titleFontSizeSp.sp,
    lineHeight = ChapterHeaderMetrics.titleLineHeightSp.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Start,
    textIndent = null,
)

/**
 * The one chapter header of the reader: ornament, title and, when translation
 * is on, the active-mode badge. Feed, button and paged mode all draw it, so
 * the chapter title is never repeated. The chapter position is not repeated
 * here either - the chrome already shows "Chapter X / Y".
 */
@Composable
fun ReaderChapterHeader(
    title: String,
    style: ReaderStyle,
    translationLabel: String?,
    showDivider: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tint = Color(style.effectiveTextColor())
    val dividerModifier = if (showDivider) {
        Modifier.drawBehind {
            val lineWidth = min(72.dp.toPx(), size.width * 0.25f)
            val startX = (size.width - lineWidth) / 2f
            val y = 10.dp.toPx()
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        tint.copy(alpha = 0.2f),
                        Color.Transparent,
                    ),
                    startX = startX,
                    endX = startX + lineWidth,
                ),
                start = Offset(startX, y),
                end = Offset(startX + lineWidth, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(dividerModifier)
            .padding(
                top = ChapterHeaderMetrics.topPadding,
                bottom = ChapterHeaderMetrics.bottomPadding,
            ),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                painter = painterResource(R.drawable.ic_reader_title_book),
                contentDescription = null,
                tint = tint.copy(alpha = ChapterHeaderMetrics.ornamentAlpha),
                modifier = Modifier
                    .size(ChapterHeaderMetrics.ornamentSize)
                    .offset(y = 3.dp),
            )
            Spacer(Modifier.width(ChapterHeaderMetrics.ornamentGap))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = style.chapterTitleStyle(),
                )
                if (translationLabel != null) {
                    Spacer(Modifier.height(ChapterHeaderMetrics.badgeGap))
                    TranslationBadge(
                        label = translationLabel,
                        style = style,
                        description = stringResource(R.string.rd_translation_badge_desc),
                    )
                }
            }
        }
    }
}

/**
 * Small pill under the title that states the active translation mode and the
 * chapter's translation status.
 */
@Composable
private fun TranslationBadge(
    label: String,
    style: ReaderStyle,
    description: String,
) {
    val textColor = Color(style.effectiveTextColor())
    Row(
        modifier = Modifier
            // Grow with the (scaled) label instead of clipping at 200% font.
            .heightIn(min = ChapterHeaderMetrics.badgeHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(textColor.copy(alpha = ChapterHeaderMetrics.badgeAlpha))
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$description: $label"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Translate,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = textColor.copy(alpha = 0.8f),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

fun ReaderStyle.toTextStyle(): TextStyle = TextStyle(
    color = Color(effectiveTextColor()),
    fontFamily = resolvedFontFamily(),
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * lineHeight).sp,
    letterSpacing = letterSpacingSp().sp,
    textAlign = if (justify) TextAlign.Justify else TextAlign.Start,
    textDecoration = if (underline) TextDecoration.Underline else null,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    textIndent = if (paragraphIndent) TextIndent(firstLine = (fontSizeSp * 2).sp) else null,
    shadow = textShadow(),
)

fun ReaderStyle.headingTextStyle(): TextStyle = toTextStyle().copy(
    fontSize = 25.sp,
    lineHeight = 32.sp,
    fontWeight = FontWeight.SemiBold,
)

private fun ReaderStyle.textShadow(): Shadow? =
    if (shadow) {
        Shadow(
            color = Color(effectiveTextColor()).copy(alpha = 0.5f),
            offset = Offset(1.5f, 1.5f),
            blurRadius = 3f,
        )
    } else {
        null
    }

@Composable
private fun SplitTtsText(
    text: String,
    block: ReaderBlock,
    style: ReaderStyle,
    textStyle: androidx.compose.ui.text.TextStyle,
    highlight: TtsHighlight?,
    isTranslated: Boolean,
) {
    if (!ReaderSplit.shouldSplit(block, style.splitSentences)) {
        TtsHighlightedText(
            raw = text,
            block = block,
            style = style,
            highlight = highlight,
            applyFirstLetterBold = false,
            isTranslated = isTranslated,
            textStyle = textStyle,
        )
        return
    }
    val spans = remember(text, isTranslated, block.sentences) {
        if (!isTranslated) ReaderSplit.originalSpans(block) else SentenceSplitter.spans(text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp)) {
        spans.forEach { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end <= start) return@forEach
            TtsHighlightedText(
                raw = text.substring(start, end),
                block = block,
                style = style,
                highlight = highlight,
                applyFirstLetterBold = false,
                isTranslated = isTranslated,
                baseOffset = start,
                textStyle = textStyle,
            )
        }
    }
}

/**
 * Range of the spoken words inside one raw text slice, or null when the
 * highlight does not belong to this copy/sentence.
 */
internal fun ttsHighlightRange(
    textLength: Int,
    blockId: BlockId,
    highlight: TtsHighlight?,
    isTranslated: Boolean,
    baseOffset: Int = 0,
): IntRange? {
    if (highlight == null ||
        highlight.blockId != blockId ||
        !highlight.rangeEnabled ||
        highlight.translated != isTranslated ||
        highlight.start >= highlight.end
    ) return null
    val start = (highlight.start - baseOffset).coerceIn(0, textLength)
    val end = (highlight.end - baseOffset).coerceIn(start, textLength)
    return if (start < end) start until end else null
}

/**
 * Renders one text slice and draws the spoken range as rounded per-line pills.
 * A wrapped sentence gets one pill per visual line instead of one hard
 * rectangle around the whole block.
 */
@Composable
private fun TtsHighlightedText(
    raw: String,
    block: ReaderBlock,
    style: ReaderStyle,
    highlight: TtsHighlight?,
    textStyle: TextStyle,
    applyFirstLetterBold: Boolean = style.firstLetterBold,
    isTranslated: Boolean = false,
    baseOffset: Int = 0,
    modifier: Modifier = Modifier,
) {
    val text = style.paragraphText(raw, applyFirstLetterBold)
    val range = ttsHighlightRange(raw.length, block.id, highlight, isTranslated, baseOffset)
    val color = highlight?.let { Color(it.colorArgb).copy(alpha = 0.30f) }
    var layout by remember(text, range) { mutableStateOf<TextLayoutResult?>(null) }
    val highlightModifier = if (range != null && color != null) {
        Modifier.drawBehind {
            val result = layout ?: return@drawBehind
            drawRoundedRangeHighlight(result, range, color)
        }
    } else {
        Modifier
    }
    Text(
        text = text,
        style = textStyle,
        modifier = modifier.then(highlightModifier),
        onTextLayout = { result -> if (layout != result) layout = result },
    )
}

private fun DrawScope.drawRoundedRangeHighlight(
    layout: TextLayoutResult,
    range: IntRange,
    color: Color,
) {
    val length = layout.layoutInput.text.length
    val start = range.first.coerceIn(0, length)
    // `ttsHighlightRange` is a half-open range: include the last character.
    val endExclusive = (range.last + 1).coerceIn(start, length)
    if (start >= endExclusive) return
    val firstLine = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(endExclusive - 1)
    val padH = 1.dp.toPx()
    val corner = 4.dp.toPx()
    for (line in firstLine..lastLine) {
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line, visibleEnd = true)
        val from = maxOf(start, lineStart)
        val to = minOf(endExclusive, lineEnd)
        if (to <= from) continue
        // Union the glyph boxes of every character on this line. Line boxes
        // include leading and would paint the gaps between lines.
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (offset in from until to) {
            val box = layout.getBoundingBox(offset)
            left = minOf(left, box.left)
            right = maxOf(right, box.right)
            top = minOf(top, box.top)
            bottom = maxOf(bottom, box.bottom)
        }
        if (!left.isFinite() || !top.isFinite()) continue
        drawRoundRect(
            color = color,
            topLeft = Offset(left - padH, top),
            size = Size(
                (right - left + padH * 2f).coerceAtLeast(1f),
                (bottom - top).coerceAtLeast(1f),
            ),
            cornerRadius = CornerRadius(corner),
        )
    }
}

/**
 * True when the spoken cursor [cursor] falls inside a text span [start, end).
 * Guards against empty spans so the last sentence still matches its end.
 */
internal fun containsCursor(start: Int, end: Int, cursor: Int): Boolean =
    cursor >= start && cursor < end.coerceAtLeast(start + 1)

/**
 * Tints one split sentence/paragraph only when the spoken cursor falls inside
 * it. Without this, one pre-split paragraph lit up as several (FR-027).
 */
private fun sentenceHighlightModifier(
    blockId: BlockId,
    start: Int,
    end: Int,
    isTranslated: Boolean,
    highlight: TtsHighlight?,
    bleed: Dp,
): Modifier {
    if (highlight == null ||
        highlight.blockId != blockId ||
        highlight.translated != isTranslated
    ) {
        return Modifier
    }
    if (!containsCursor(start, end, highlight.start)) return Modifier
    return Modifier.drawBehind {
        val bleedPx = bleed.toPx()
        drawRoundRect(
            color = Color(highlight.colorArgb).copy(alpha = 0.10f),
            topLeft = Offset(-bleedPx, 0f),
            size = Size(size.width + bleedPx * 2f, size.height),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
    }
}

/** Tints one parallel pair when the cursor is inside either of its copies. */
private fun parallelPairHighlightModifier(
    blockId: BlockId,
    copy: FeedPairCopy,
    highlight: TtsHighlight?,
    bleed: Dp,
): Modifier {
    if (highlight == null || highlight.blockId != blockId) return Modifier
    val active = if (highlight.translated) {
        copy.translated.isNotBlank() &&
            containsCursor(copy.translatedStart, copy.translatedStart + copy.translated.length, highlight.start)
    } else {
        copy.original.isNotBlank() &&
            containsCursor(copy.originalStart, copy.originalStart + copy.original.length, highlight.start)
    }
    return if (active) {
        Modifier.background(
            Color(highlight.colorArgb).copy(alpha = 0.10f),
            RoundedCornerShape(MiruRadius.Badge),
        )
    } else {
        Modifier
    }
}

fun ReaderStyle.paragraphText(
    raw: String,
    applyFirstLetterBold: Boolean = firstLetterBold,
): AnnotatedString =
    if (!applyFirstLetterBold || raw.isEmpty()) {
        AnnotatedString(raw)
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.first()) }
            append(raw.substring(1))
        }
    }

private fun ReaderStyle.resolvedFontFamily(): FontFamily =
    customFontPath?.let(::loadCustomFontFamily) ?: fontFamily.toFontFamily()

private fun String.toFontFamily(): FontFamily = when (this) {
    "sans", "sans-serif" -> FontFamily.SansSerif
    "mono", "monospace" -> FontFamily.Monospace
    "serif" -> FontFamily.Serif
    else -> ReaderFontCatalog.systemName(this)?.let(::systemFontFamily) ?: FontFamily.Serif
}

// Typeface.create() silently returns the default face for an unknown family,
// so a saved key that is not a system key falls back to Serif instead.
private val systemFontCache = mutableMapOf<String, FontFamily>()

private fun systemFontFamily(name: String): FontFamily = synchronized(systemFontCache) {
    if (systemFontCache.size >= 8) systemFontCache.clear()
    systemFontCache.getOrPut(name) {
        FontFamily(Typeface.create(name, Typeface.NORMAL))
    }
}

// Cache the loaded file font by path plus file stats: replacing the picked
// font invalidates the entry, and an unreadable file falls back to the chosen
// built-in family instead of crashing the reader.
private val customFontCache = mutableMapOf<String, FontFamily?>()

private fun loadCustomFontFamily(path: String): FontFamily? {
    return synchronized(customFontCache) {
        val file = File(path)
        if (!file.isFile) return@synchronized null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        if (customFontCache.containsKey(key)) return@synchronized customFontCache[key]
        val family = runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrNull()
        if (customFontCache.size >= 8) customFontCache.clear()
        customFontCache[key] = family
        family
    }
}
