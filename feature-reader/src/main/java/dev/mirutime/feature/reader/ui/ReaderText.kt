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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mirutime.feature.reader.R
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.paged.parallelPairs
import dev.mirutime.feature.tts.TtsChunker
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.feature.tts.ui.highlightedRange
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
) {
    val searchable = SelectionSearch.isSearchable(block.kind)
    val selectionState = rememberSelectionState()
    val searchLabel = stringResource(R.string.rd_selection_search)
    val body: @Composable () -> Unit = {
        when {
            translated != null && searchable && parallel ->
                ParallelTranslation(block, translated, style, translationFirst, ttsHighlight)
            translated != null && searchable ->
                TranslatedText(block.kind, translated, style, block, ttsHighlight)
            else -> OriginalText(block, style, ttsHighlight)
        }
    }
    // Long press selects everywhere; short taps are handled at the Initial
    // pass, so they never reach selection as taps or double taps.
    // The active TTS paragraph gets a soft block background even when the
    // optional word-range highlight is disabled. Split paragraphs instead tint
    // only the sentence being spoken (see sentenceHighlightModifier), so one
    // pre-split paragraph never lights up as several.
    val splitParagraph = style.splitSentences && block.kind == BlockKind.Paragraph
    val activeBlock = ttsHighlight?.blockId == block.id
    val blockTint = activeBlock && !splitParagraph
    val content: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (blockTint) {
                        Modifier.background(
                            Color(ttsHighlight.colorArgb).copy(alpha = 0.10f),
                            RoundedCornerShape(6.dp),
                        )
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
private fun OriginalText(block: ReaderBlock, style: ReaderStyle, highlight: TtsHighlight?) {
    when (block.kind) {
        BlockKind.Heading -> Text(
            text = highlightedText(block.original, block, style, highlight, applyFirstLetterBold = false),
            style = style.headingTextStyle(),
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
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
        BlockKind.Paragraph -> if (style.splitSentences && block.sentences.isNotEmpty()) {
            // Keep one Text per sentence even while TTS highlights a range.
            // Collapsing to a single paragraph Text on highlight start made
            // the whole block re-layout and blink at every chunk boundary.
            Column(verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp)) {
                block.sentences.forEach { span ->
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
                                ),
                            ),
                    ) {
                        Text(
                            text = highlightedSentence(
                                sentence = sentence,
                                sentenceStart = rawStart,
                                block = block,
                                highlight = highlight,
                            ),
                            style = style.toTextStyle(),
                        )
                    }
                }
            }
        } else if (highlight != null) {
            Text(
                text = highlightedText(block.original, block, style, highlight),
                style = style.toTextStyle(),
            )
        } else {
            Text(
                text = style.paragraphText(block.original),
                style = style.toTextStyle(),
            )
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
) {
    val textStyle = if (kind == BlockKind.Heading) style.headingTextStyle() else style.toTextStyle()
    // Only paragraph splitting gets the per-sentence tint; other kinds keep the
    // whole-block tint from the caller.
    if (block != null && style.splitSentences && kind == BlockKind.Paragraph) {
        Column(verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp)) {
            TtsChunker.sentenceSpans(text).forEach { span ->
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
                            ),
                        ),
                ) {
                    Text(
                        text = highlightedText(
                            raw = sentence,
                            block = block,
                            style = style,
                            highlight = highlight,
                            applyFirstLetterBold = false,
                            isTranslated = true,
                            baseOffset = span.start,
                        ),
                        style = textStyle,
                    )
                }
            }
        }
    } else {
        Text(
            text = if (block != null) {
                highlightedText(
                    text,
                    block,
                    style,
                    highlight,
                    applyFirstLetterBold = false,
                    isTranslated = true,
                )
            } else {
                style.paragraphText(text, applyFirstLetterBold = false)
            },
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
internal fun feedPairPlan(block: ReaderBlock, translated: String): List<FeedPairCopy> =
    parallelPairs(block, translated, splitSentences = true).mapNotNull { pair ->
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
) {
    val translatedStyle = if (block.kind == BlockKind.Heading) {
        style.headingTextStyle()
    } else {
        style.toTextStyle()
    }
    val originalStyle = style.pagedSecondaryTextStyle(block.kind)
    // shortcut: mirrors paged PagedAltGap; share a token if it ever changes.
    val copyGap = 6.dp
    val pairMode = style.splitSentences && block.kind == BlockKind.Paragraph
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp),
    ) {
        if (pairMode) {
            feedPairPlan(block, translated).forEach { copy ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(parallelPairHighlightModifier(block.id, copy, highlight)),
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
            Text(
                text = highlightedText(
                    raw = copy.translated,
                    block = block,
                    style = style,
                    highlight = highlight,
                    applyFirstLetterBold = false,
                    isTranslated = true,
                    baseOffset = copy.translatedStart,
                ),
                style = translatedStyle,
            )
        }
    }
    val originalCopy: @Composable () -> Unit = {
        if (copy.original.isNotBlank()) {
            Text(
                text = highlightedText(
                    raw = copy.original,
                    block = block,
                    style = style,
                    highlight = highlight,
                    applyFirstLetterBold = false,
                    isTranslated = false,
                    baseOffset = copy.originalStart,
                ),
                style = originalStyle,
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
    if (!style.splitSentences) {
        Text(
            text = highlightedText(
                raw = text,
                block = block,
                style = style,
                highlight = highlight,
                applyFirstLetterBold = false,
                isTranslated = isTranslated,
            ),
            style = textStyle,
        )
        return
    }
    val spans = if (!isTranslated && block.sentences.isNotEmpty()) {
        block.sentences
    } else {
        TtsChunker.sentenceSpans(text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp)) {
        spans.forEach { span ->
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end <= start) return@forEach
            Text(
                text = highlightedText(
                    raw = text.substring(start, end),
                    block = block,
                    style = style,
                    highlight = highlight,
                    applyFirstLetterBold = false,
                    isTranslated = isTranslated,
                    baseOffset = start,
                ),
                style = textStyle,
            )
        }
    }
}

private fun highlightedText(
    raw: String,
    block: ReaderBlock,
    style: ReaderStyle,
    highlight: TtsHighlight?,
    applyFirstLetterBold: Boolean = style.firstLetterBold,
    isTranslated: Boolean = false,
    baseOffset: Int = 0,
): AnnotatedString {
    val base = style.paragraphText(raw, applyFirstLetterBold)
    if (
        highlight == null ||
        highlight.blockId != block.id ||
        !highlight.rangeEnabled ||
        highlight.translated != isTranslated ||
        highlight.start >= highlight.end ||
        highlight.end <= baseOffset
    ) return base
    val start = (highlight.start - baseOffset).coerceIn(0, raw.length)
    val end = (highlight.end - baseOffset).coerceIn(start, raw.length)
    if (start == end) return base
    return buildAnnotatedString {
        append(base)
        addStyle(SpanStyle(background = Color(highlight.colorArgb).copy(alpha = 0.35f)), start, end)
    }
}

/**
 * Maps the spoken range, which is relative to the whole block, onto one
 * sentence Text. Offsets that fall outside the sentence are dropped, so only
 * the sentence being spoken gets the range background.
 */
internal fun highlightedSentence(
    sentence: String,
    sentenceStart: Int,
    block: ReaderBlock,
    highlight: TtsHighlight?,
): AnnotatedString {
    if (
        highlight == null ||
        highlight.blockId != block.id ||
        !highlight.rangeEnabled ||
        highlight.translated ||
        highlight.start >= highlight.end
    ) return AnnotatedString(sentence)
    val start = (highlight.start - sentenceStart).coerceIn(0, sentence.length)
    val end = (highlight.end - sentenceStart).coerceIn(0, sentence.length)
    if (start >= end) return AnnotatedString(sentence)
    return highlightedRange(sentence, start, end, highlight.colorArgb)
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
): Modifier {
    if (highlight == null ||
        highlight.blockId != blockId ||
        highlight.translated != isTranslated
    ) {
        return Modifier
    }
    if (!containsCursor(start, end, highlight.start)) return Modifier
    return Modifier.background(
        Color(highlight.colorArgb).copy(alpha = 0.10f),
        RoundedCornerShape(6.dp),
    )
}

/** Tints one parallel pair when the cursor is inside either of its copies. */
private fun parallelPairHighlightModifier(
    blockId: BlockId,
    copy: FeedPairCopy,
    highlight: TtsHighlight?,
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
            RoundedCornerShape(6.dp),
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
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.Serif
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
