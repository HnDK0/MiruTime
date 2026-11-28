package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.reader.R
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.feature.reader.feedItemKey
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.FeedEntry
import dev.mirutime.feature.reader.session.ReaderSessionState
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.api.ReaderChapterTranslation
import dev.mirutime.feature.reader.ui.ReaderBlockView
import dev.mirutime.feature.reader.ui.ReaderChapterHeader
import dev.mirutime.feature.tts.api.TtsHighlight
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Anchor-scroll triggers are explicit requests, not feed contents. Loading a
 * previous chapter prepends entries and shifts every index, so the entry list
 * must stay out of the key: otherwise the new chapter is scrolled away again.
 */
internal data class FeedScrollTrigger(
    val scrollToken: Int,
    val topPaddingPx: Float,
)

internal fun feedScrollTrigger(
    state: ReaderSessionState,
    topPaddingPx: Float,
): FeedScrollTrigger = FeedScrollTrigger(
    scrollToken = state.scrollToken,
    topPaddingPx = topPaddingPx,
)

/**
 * QuickNovel-style follow rule: the page stays put while the spoken block is
 * fully inside the reading area. It only advances once the block has moved
 * off-screen (or is cut by the bottom edge and would fit on one screen).
 * A block taller than the screen is left alone while any part of it shows.
 */
internal fun ttsFollowNeedsScroll(
    visible: Boolean,
    offsetPx: Int,
    sizePx: Int,
    viewportTopPx: Int,
    viewportBottomPx: Int,
): Boolean {
    if (!visible) return true
    if (sizePx > viewportBottomPx - viewportTopPx) return false
    return offsetPx < viewportTopPx || offsetPx + sizePx > viewportBottomPx
}

/**
 * The follow only continues while the reader is "attached" to the voice: the
 * previously spoken block was still on screen (or there was none, or it has
 * left the feed). When the user scrolled away from it, the screen is theirs and
 * is never yanked back; scrolling back to the voice (or the Focus button)
 * re-attaches.
 */
internal fun ttsFollowAttached(
    hadPrevious: Boolean,
    previousInFeed: Boolean,
    previousVisible: Boolean,
): Boolean = !hadPrevious || !previousInFeed || previousVisible

/** Small jumps animate; far chapter jumps should land immediately. */
internal fun ttsFollowUsesAnimation(distance: Int): Boolean = distance <= 5

/**
 * Backwards loading is explicit. Composition only decides whether the top gap
 * renders a button; the request itself happens on tap, so loading a chapter
 * never auto-requests the one before it and walks the whole book back.
 */
internal fun topGapShowsLoadButton(entry: FeedEntry.Gap): Boolean =
    entry.top && entry.status == ChapterLoadStatus.NotLoaded

internal fun topGapOnTap(entry: FeedEntry.Gap, onLoadPrevious: (Int) -> Unit) {
    if (topGapShowsLoadButton(entry)) onLoadPrevious(entry.chapterIndex)
}

@Composable
internal fun ReaderFeed(
    state: ReaderSessionState,
    style: ReaderStyle,
    listState: LazyListState,
    contentPadding: PaddingValues,
    translationLabelFor: (Int) -> String?,
    translations: Map<String, ReaderChapterTranslation>,
    chapterTitles: Map<Int, String> = emptyMap(),
    parallel: Boolean = false,
    translationFirst: Boolean = true,
    ttsHighlight: TtsHighlight? = null,
    /** Bumped by the player's Focus button; forces a scroll to the TTS block. */
    focusToken: Int = 0,
    selectionActions: SelectionActions? = null,
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onRetry: (Int) -> Unit,
    onLoadPrevious: (Int) -> Unit,
    /** Asks for the chapter after the loaded window; keeps reading continuous. */
    onLoadNext: (Int) -> Unit = {},
    onOpenAuth: (String) -> Unit = {},
    onChapterFinished: (Int) -> Unit,
    onStartOver: () -> Unit,
    onUserScrollChange: (Boolean) -> Unit = {},
) {
    val entries = state.feed.entries
    val textColor = Color(style.effectiveTextColor())
    val currentOnUserScrollChange by rememberUpdatedState(onUserScrollChange)
    val currentState by rememberUpdatedState(state)
    // Only layout-affecting fields should restore the anchor after a reflow;
    // TTS-highlight/keep-screen-on changes must not snap-scroll.
    val layoutStyle = remember(style) {
        listOf(
            style.fontFamily,
            style.customFontPath,
            style.customFontName,
            style.fontSizeSp,
            style.lineHeight,
            style.paragraphSpacingDp,
            style.marginStartDp,
            style.marginEndDp,
            style.marginTopDp,
            style.marginBottomDp,
            style.letterSpacingEm,
            style.justify,
            style.hyphenation,
            style.paragraphIndent,
            style.firstLetterBold,
            style.splitSentences,
            style.bold,
            style.italic,
            style.underline,
            style.shadow,
        )
    }
    val initialLayoutStyle = remember { layoutStyle }

    val density = LocalDensity.current
    // The floating chrome header covers the top of the list. A negative scroll
    // offset parks the spoken block below it instead of under the title.
    val ttsFocusInsetPx = with(density) { 72.dp.toPx().toInt() }
    // Advancing parks the spoken block just under the top edge, like turning
    // to the next screen of text.
    val ttsAdvanceInsetPx = with(density) { 16.dp.toPx().toInt() }
    // Block id spoken before the current one; lets the follow tell "the voice
    // moved off-screen" apart from "the user scrolled away".
    var previousTtsBlockId by remember { mutableStateOf<BlockId?>(null) }
    fun ttsBlockIndex(): Int {
        val blockId = ttsHighlight?.blockId ?: return -1
        return entries.indexOfFirst {
            it is FeedEntry.Block && it.block.id == blockId
        }
    }
    // The page stays stationary while the spoken block is on screen and the
    // reader scrolls freely. Only when TTS moves the spoken block off-screen
    // does the view advance, parking it at the top. Per-sentence range updates
    // never trigger this; only block changes do.
    suspend fun followTtsBlock(previous: BlockId?) {
        val index = ttsBlockIndex()
        if (index < 0) return
        val info = listState.layoutInfo
        val previousIndex = if (previous == null) {
            -1
        } else {
            entries.indexOfFirst { it is FeedEntry.Block && it.block.id == previous }
        }
        val previousVisible = previousIndex >= 0 &&
            info.visibleItemsInfo.any { it.index == previousIndex }
        if (!ttsFollowAttached(
                hadPrevious = previous != null,
                previousInFeed = previousIndex >= 0,
                previousVisible = previousVisible,
            )
        ) {
            return
        }
        val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (!ttsFollowNeedsScroll(
                visible = visible != null,
                offsetPx = visible?.offset ?: 0,
                sizePx = visible?.size ?: 0,
                viewportTopPx = 0,
                viewportBottomPx = info.viewportEndOffset - info.afterContentPadding,
            )
        ) {
            return
        }
        val distance = abs(index - (visible?.index ?: listState.firstVisibleItemIndex))
        if (ttsFollowUsesAnimation(distance)) {
            listState.animateScrollToItem(index, scrollOffset = -ttsAdvanceInsetPx)
        } else {
            listState.scrollToItem(index, scrollOffset = -ttsAdvanceInsetPx)
        }
    }
    LaunchedEffect(ttsHighlight?.blockId) {
        val current = ttsHighlight?.blockId
        val previous = previousTtsBlockId
        previousTtsBlockId = current
        if (current != null) followTtsBlock(previous)
    }
    // First content only: prepending a previous chapter must not re-follow.
    LaunchedEffect(entries.isEmpty()) {
        if (entries.isNotEmpty()) followTtsBlock(null)
    }
    // Explicit Focus action: retrigger even when the active block has not
    // changed since the last auto-follow.
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        val index = ttsBlockIndex()
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index, scrollOffset = -ttsFocusInsetPx)
    }

    suspend fun scrollToAnchor(
        source: ReaderSessionState,
        anchor: ReadingAnchor,
        offsetPx: Int,
    ) {
        val chapterIndex = source.chapters.firstOrNull {
            it.chapterUrl == anchor.chapterUrl
        }?.index
        val blocks = chapterIndex?.let { source.documents[it]?.blocks }
        val atChapterStart = blocks?.firstOrNull()?.id == anchor.blockId
        val currentEntries = source.feed.entries
        val index = if (atChapterStart) {
            currentEntries.indexOfFirst {
                it is FeedEntry.Header && it.chapterIndex == chapterIndex
            }
        } else {
            currentEntries.indexOfFirst {
                it is FeedEntry.Block && it.block.id == anchor.blockId
            }
        }
        if (index >= 0) {
            listState.scrollToItem(index, if (atChapterStart) 0 else offsetPx)
        }
    }

    LaunchedEffect(feedScrollTrigger(state, contentPadding.calculateTopPadding().value)) {
        val anchor = state.anchor ?: return@LaunchedEffect
        scrollToAnchor(state, anchor, state.offsetPx)
    }

    // Style preview reflows the text without changing the logical anchor.
    // Coalesce slider ticks and restore the pre-preview position.
    LaunchedEffect(layoutStyle) {
        if (layoutStyle == initialLayoutStyle) return@LaunchedEffect
        val anchor = currentState.anchor ?: return@LaunchedEffect
        val offsetPx = currentState.offsetPx
        delay(80)
        scrollToAnchor(currentState, anchor, offsetPx)
    }

    LaunchedEffect(listState, entries) {
        var lastReportedIndex = -1
        var lastReportedOffset = -1
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }
            .distinctUntilChanged()
            .collect { (index, offset, scrolling) ->
                val indexChanged = index != lastReportedIndex
                val settledOffsetChanged = !scrolling && offset != lastReportedOffset
                if (!indexChanged && !settledOffsetChanged) return@collect
                lastReportedIndex = index
                lastReportedOffset = offset
                val entry = entries.getOrNull(index) ?: return@collect
                when (entry) {
                    is FeedEntry.Block -> onAnchor(
                        ReadingAnchor(entry.block.id.chapterUrl, entry.block.id, 0),
                        offset,
                    )
                    is FeedEntry.Header -> {
                        val block = state.documents[entry.chapterIndex]?.blocks?.firstOrNull()
                        if (block != null) onAnchor(
                            ReadingAnchor(block.id.chapterUrl, block.id, 0),
                            offset,
                        )
                    }
                    is FeedEntry.Gap -> Unit
                }
            }
    }

    LaunchedEffect(listState, entries) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val entry = entries.getOrNull(lastVisible) ?: return@collect
                val range = state.feed.chapterEntryRanges[entry.chapterIndex] ?: return@collect
                if (lastVisible >= range.last) onChapterFinished(entry.chapterIndex)
            }
    }

    // Reading forward never stops at a boundary: approaching the end of the
    // loaded window asks for the next chapter. The store deduplicates the
    // request, so repeated emissions cost nothing.
    val nextChapterIndex = remember(state.chapters, state.feed.lastChapterIndex) {
        val last = state.feed.lastChapterIndex ?: return@remember null
        val position = state.chapters.indexOfFirst { it.index == last }
        if (position < 0) null else state.chapters.getOrNull(position + 1)?.index
    }
    LaunchedEffect(listState, entries, nextChapterIndex) {
        val next = nextChapterIndex ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= entries.lastIndex - 1) onLoadNext(next)
            }
    }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = textColor)
        }
        return
    }

    val topPadding = contentPadding.calculateTopPadding()
    val startPadding = contentPadding.calculateLeftPadding(LocalLayoutDirection.current)
    val endPadding = contentPadding.calculateRightPadding(LocalLayoutDirection.current)
    val bottomPadding = contentPadding.calculateBottomPadding()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // Autoscroll pauses for the whole gesture, not only for drags: a
            // long press or a tap must never fight a moving page.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    currentOnUserScrollChange(true)
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    currentOnUserScrollChange(false)
                }
            }
            .padding(top = topPadding),
        contentPadding = PaddingValues(
            start = startPadding,
            end = endPadding,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(style.paragraphSpacingDp.dp),
    ) {
        itemsIndexed(
            items = entries,
            key = { _, entry -> feedItemKey(entry.id) },
            contentType = { _, entry ->
                when (entry) {
                    is FeedEntry.Header -> "header"
                    is FeedEntry.Block -> "block"
                    is FeedEntry.Gap -> "gap"
                }
            },
        ) { index, entry ->
            when (entry) {
                is FeedEntry.Header -> ReaderChapterHeader(
                    title = chapterTitles[entry.chapterIndex] ?: entry.title,
                    style = style,
                    translationLabel = translationLabelFor(entry.chapterIndex),
                    showDivider = index > 0 && entries[index - 1] !is FeedEntry.Gap,
                )
                is FeedEntry.Block -> {
                    // A provider may return an empty string for a block; paged
                    // and TTS already fall back to the original there, and feed
                    // must not paint a blank paragraph either.
                    val translated = translations[entry.block.id.chapterUrl]
                        ?.translations
                        ?.get(entry.block.id.index)
                        ?.takeIf { it.isNotBlank() }
                    ReaderBlockView(
                        block = entry.block,
                        style = style,
                        translated = translated,
                        parallel = parallel,
                        translationFirst = translationFirst,
                        ttsHighlight = ttsHighlight,
                        selectionActions = selectionActions,
                        bleed = maxOf(startPadding, endPadding),
                    )
                }
                is FeedEntry.Gap -> {
                    FeedGap(
                        entry = entry,
                        style = style,
                        title = chapterTitles[entry.chapterIndex] ?: entry.title,
                        onRetry = { onRetry(entry.chapterIndex) },
                        onLoadPrevious = { topGapOnTap(entry, onLoadPrevious) },
                        onOpenAuth = onOpenAuth,
                    )
                }
            }
        }
        if (state.endOfBook) {
            item(key = feedItemKey("end-of-book")) {
                EndOfBook(onStartOver = onStartOver, style = style)
            }
        }
    }
}

@Composable
private fun FeedGap(
    entry: FeedEntry.Gap,
    style: ReaderStyle,
    title: String,
    onRetry: () -> Unit,
    onLoadPrevious: () -> Unit,
    onOpenAuth: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (entry.top) {
                stringResource(R.string.rd_gap_previous_fmt, title)
            } else {
                stringResource(R.string.rd_gap_next_fmt, title)
            },
            color = Color(style.effectiveTextColor()),
            style = MaterialTheme.typography.titleSmall,
        )
        when (val status = entry.status) {
            // NotLoaded means nothing was requested yet: no spinner until the
            // load actually starts, otherwise the gap looks stuck forever.
            ChapterLoadStatus.NotLoaded -> {
                if (topGapShowsLoadButton(entry)) {
                    TextButton(
                        onClick = onLoadPrevious,
                        modifier = Modifier.heightIn(min = MiruSize.Touch),
                    ) {
                        Text(stringResource(R.string.rd_gap_load_previous))
                    }
                }
            }
            ChapterLoadStatus.Loading -> {
                Text(
                    text = stringResource(
                        if (entry.top) R.string.rd_gap_loading_previous else R.string.rd_gap_loading,
                    ),
                    color = Color(style.effectiveTextColor()).copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 12.dp),
                    color = Color(style.effectiveTextColor()),
                )
            }
            ChapterLoadStatus.Ready -> Unit
            is ChapterLoadStatus.Failed -> {
                status.title?.takeIf { it.isNotBlank() }?.let { failureTitle ->
                    Text(
                        text = failureTitle,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = MiruSpace.S),
                    )
                }
                Text(
                    text = if (
                        status.message.isBlank() ||
                        status.message == "Could not load chapter"
                    ) {
                        stringResource(R.string.rd_chapter_error)
                    } else {
                        status.message
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (status.authUrl != null) {
                    TextButton(onClick = { onOpenAuth(status.authUrl) }) {
                        Text(stringResource(R.string.rd_open_login))
                    }
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.rd_retry))
                }
            }
        }
    }
}

@Composable
private fun EndOfBook(onStartOver: () -> Unit, style: ReaderStyle) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.rd_all_read),
            color = Color(style.effectiveTextColor()),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onStartOver) {
            Text(stringResource(R.string.rd_start_over))
        }
    }
}
