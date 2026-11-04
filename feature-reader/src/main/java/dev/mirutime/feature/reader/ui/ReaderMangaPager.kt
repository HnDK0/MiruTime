package dev.mirutime.feature.reader.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Scale
import dev.mirutime.feature.reader.R
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.app.features.reader.document.pageIndexForAnchor
import dev.mirutime.feature.reader.MangaColorFilter
import dev.mirutime.feature.reader.MangaEndAction
import dev.mirutime.feature.reader.MangaFeedPage
import dev.mirutime.feature.reader.MangaFeedPlanner
import dev.mirutime.feature.reader.MangaPageTurn
import dev.mirutime.feature.reader.MangaPagedNavigation
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.MangaReadingDirection
import dev.mirutime.feature.reader.MangaReadingMode
import dev.mirutime.feature.reader.MangaTapSide
import dev.mirutime.feature.reader.MangaWebtoonPadding
import dev.mirutime.feature.reader.mangaEndAction
import dev.mirutime.feature.reader.mangaTapSide
import dev.mirutime.feature.reader.tapTurn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val MinMangaScale = 1f
private const val MaxMangaScale = 4f
private const val DoubleTapZoomScale = 2f

// One stable painter for every loading page: the frame stays black instead of
// flashing an empty surface while Coil swaps painters.
private val MangaPagePlaceholder = ColorPainter(Color.Black)

/**
 * Dedicated manga viewer. Paged mode is one image per screen with optional
 * RTL layout, double-tap zoom and tap zones; Webtoon mode is one continuous
 * vertical strip. Text chapters keep using [ReaderFeed]/[ReaderPaged].
 *
 * Webtoon deliberately has no pinch zoom: vertical scrolling already owns the
 * gesture, so a double tap there toggles chrome instead of zooming. The page
 * counter hides while chrome is open so the chapter dock never covers it.
 */
@Composable
internal fun ReaderMangaPager(
    document: ReaderDocument,
    pages: List<ReaderBlock>,
    chapterIndex: Int,
    anchor: ReadingAnchor?,
    scrollToken: Int,
    style: MangaReaderStyle,
    chromeVisible: Boolean,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onChapterFinished: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    scrubber: MangaScrubberState,
    onVisiblePage: (Int) -> Unit = {},
    nextChapterFirstPage: String? = null,
    onNearEnd: () -> Unit = {},
    // Continuous webtoon strip: loaded chapters around the active one. Empty
    // falls back to the active chapter's pages only.
    feed: List<MangaFeedPage> = emptyList(),
    onVisibleChapter: (Int, ReadingAnchor?) -> Unit = { _, _ -> },
    onNearStart: (Int) -> Unit = {},
    imageHeadersFor: (String) -> Map<String, String> = { emptyMap() },
    // One chapter-pages refresh after a page exhausted its automatic retries.
    onRefreshPages: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val initialPage = remember(document, pages, anchor) {
        document.pageIndexForAnchor(anchor)
    }
    if (pages.isEmpty()) return
    // One policy per chapter: retry budgets are per image URL, but the
    // chapter-pages refresh happens at most once — a refresh re-creates the
    // pages list, so keying on the list would restart the cycle.
    val pageRetryPolicy = remember(document.chapterUrl) { MangaPageRetryPolicy() }
    var visiblePage by remember(pages) { mutableIntStateOf(initialPage) }
    val colorFilter = remember(style.colorFilter) { mangaColorFilter(style.colorFilter) }
    val endAction = mangaEndAction(visiblePage, pages.lastIndex, canNextChapter)
    val currentOnVisiblePage by rememberUpdatedState(onVisiblePage)
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    val context = LocalContext.current
    var readerSize by remember { mutableStateOf(IntSize.Zero) }
    val webtoonPaddingPx = with(LocalDensity.current) { style.webtoonPadding.toDp().roundToPx() }

    LaunchedEffect(visiblePage) {
        currentOnVisiblePage(visiblePage)
    }

    // Leaving the viewer mid-drag must not leave anchor writes suppressed.
    DisposableEffect(scrubber) {
        onDispose { scrubber.finish() }
    }

    // Warm the next chapter's first page before the reader reaches its end.
    val nearEnd by remember(pages) {
        derivedStateOf { pages.isNotEmpty() && visiblePage >= pages.lastIndex - 2 }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) currentOnNearEnd()
    }
    LaunchedEffect(
        nearEnd,
        nextChapterFirstPage,
        readerSize,
        style.mode,
        webtoonPaddingPx,
        imageHeadersFor,
    ) {
        val ref = nextChapterFirstPage?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (!nearEnd) return@LaunchedEffect
        // Same size the first page will request once it is visible; display
        // metrics include areas the reader does not draw into.
        val size = when (style.mode) {
            MangaReadingMode.Paged -> mangaPagedRequestSize(readerSize.width, readerSize.height)
            MangaReadingMode.Webtoon -> mangaWebtoonRequestSize(
                (readerSize.width - webtoonPaddingPx * 2).coerceAtLeast(1),
            )
        } ?: return@LaunchedEffect
        SingletonImageLoader.get(context).enqueue(
            mangaImageRequest(context, ref, size, style.mode.imageScale(), imageHeadersFor),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { readerSize = it }
            .background(Color.Black),
    ) {
        // The request size is chosen before the first decode; an unmeasured
        // viewport would decode unbounded and then again at the real size.
        if (readerSize != IntSize.Zero) {
            when (style.mode) {
                MangaReadingMode.Paged -> MangaPagedView(
                    document = document,
                    pages = pages,
                    chapterIndex = chapterIndex,
                    anchor = anchor,
                    scrollToken = scrollToken,
                    style = style,
                    colorFilter = colorFilter,
                    chromeVisible = chromeVisible,
                    canPrevChapter = canPrevChapter,
                    canNextChapter = canNextChapter,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                    onAnchor = onAnchor,
                    onChapterFinished = onChapterFinished,
                    onToggleChrome = onToggleChrome,
                    onVisiblePage = { visiblePage = it },
                    scrubber = scrubber,
                    imageHeadersFor = imageHeadersFor,
                    retryPolicy = pageRetryPolicy,
                    onRefreshPages = onRefreshPages,
                    viewportSize = readerSize,
                )
                MangaReadingMode.Webtoon -> MangaWebtoonView(
                    document = document,
                    pages = pages,
                    chapterIndex = chapterIndex,
                    anchor = anchor,
                    scrollToken = scrollToken,
                    style = style,
                    colorFilter = colorFilter,
                    canPrevChapter = canPrevChapter,
                    canNextChapter = canNextChapter,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                    onAnchor = onAnchor,
                    onChapterFinished = onChapterFinished,
                    onToggleChrome = onToggleChrome,
                    onVisiblePage = { visiblePage = it },
                    scrubber = scrubber,
                    imageHeadersFor = imageHeadersFor,
                    retryPolicy = pageRetryPolicy,
                    onRefreshPages = onRefreshPages,
                    viewportSize = readerSize,
                    feed = feed,
                    onVisibleChapter = onVisibleChapter,
                    onNearStart = onNearStart,
                    onNearEnd = onNearEnd,
                )
            }

            // App-level dimming only: system brightness is never touched.
            val dim = (1f - style.brightness).coerceIn(0f, 0.8f)
            if (dim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dim)),
                )
            }

            if (!chromeVisible &&
                ((style.showPageCounter && pages.size > 1) ||
                    endAction == MangaEndAction.EndOfBook)
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (endAction == MangaEndAction.EndOfBook) {
                        MangaBadge(stringResource(R.string.rd_manga_last_chapter))
                    }
                    if (style.showPageCounter && pages.size > 1) {
                        MangaBadge(
                            stringResource(
                                R.string.rd_manga_page_fmt,
                                visiblePage + 1,
                                pages.size,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private const val MangaChapterTransitionMs = 200

/** Everything one chapter's pager needs; a new chapter is a new snapshot. */
private data class MangaChapterSnapshot(
    val chapterIndex: Int,
    val document: ReaderDocument,
    val pages: List<ReaderBlock>,
    val anchor: ReadingAnchor?,
    val scrollToken: Int,
)

/**
 * Chapter handoff for paged manga: the outgoing chapter stays composed while
 * the incoming one slides in, instead of a hard cut. Page indices are never
 * shared between chapters, and only the newest chapter may write anchors.
 */
@Composable
private fun MangaPagedView(
    document: ReaderDocument,
    pages: List<ReaderBlock>,
    chapterIndex: Int,
    anchor: ReadingAnchor?,
    scrollToken: Int,
    style: MangaReaderStyle,
    colorFilter: ColorFilter?,
    chromeVisible: Boolean,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onChapterFinished: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onVisiblePage: (Int) -> Unit,
    scrubber: MangaScrubberState,
    imageHeadersFor: (String) -> Map<String, String>,
    retryPolicy: MangaPageRetryPolicy,
    onRefreshPages: () -> Unit,
    viewportSize: IntSize,
) {
    // The newest chapter owns the session; the outgoing one keeps rendering
    // for the transition but is not allowed to report or persist anything.
    val latestChapterUrl by rememberUpdatedState(document.chapterUrl)
    val rtl = style.direction == MangaReadingDirection.Rtl
    AnimatedContent(
        targetState = MangaChapterSnapshot(chapterIndex, document, pages, anchor, scrollToken),
        contentKey = { it.document.chapterUrl },
        transitionSpec = {
            if (!style.pageTransition) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                // Chapter order, not page order: a later chapter arrives from the
                // far edge, an earlier one from the near edge (mirrored for RTL).
                val forward = targetState.chapterIndex >= initialState.chapterIndex
                val sign = if (forward) 1 else -1
                val edge = if (rtl) -sign else sign
                (
                    slideInHorizontally(tween(MangaChapterTransitionMs)) { edge * it / 5 } +
                        fadeIn(tween(MangaChapterTransitionMs))
                    ) togetherWith (
                    slideOutHorizontally(tween(MangaChapterTransitionMs)) { -edge * it / 5 } +
                        fadeOut(tween(MangaChapterTransitionMs))
                    )
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "mangaChapter",
    ) { chapter ->
        MangaChapterPager(
            document = chapter.document,
            pages = chapter.pages,
            chapterIndex = chapter.chapterIndex,
            anchor = chapter.anchor,
            scrollToken = chapter.scrollToken,
            style = style,
            colorFilter = colorFilter,
            chromeVisible = chromeVisible,
            canPrevChapter = canPrevChapter,
            canNextChapter = canNextChapter,
            onPrevChapter = onPrevChapter,
            onNextChapter = onNextChapter,
            onAnchor = onAnchor,
            onChapterFinished = onChapterFinished,
            onToggleChrome = onToggleChrome,
            onVisiblePage = onVisiblePage,
            scrubber = scrubber,
            imageHeadersFor = imageHeadersFor,
            retryPolicy = retryPolicy,
            onRefreshPages = onRefreshPages,
            viewportSize = viewportSize,
            isActiveChapter = { latestChapterUrl == chapter.document.chapterUrl },
        )
    }
}

@Composable
private fun MangaChapterPager(
    document: ReaderDocument,
    pages: List<ReaderBlock>,
    chapterIndex: Int,
    anchor: ReadingAnchor?,
    scrollToken: Int,
    style: MangaReaderStyle,
    colorFilter: ColorFilter?,
    chromeVisible: Boolean,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onChapterFinished: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onVisiblePage: (Int) -> Unit,
    scrubber: MangaScrubberState,
    imageHeadersFor: (String) -> Map<String, String>,
    retryPolicy: MangaPageRetryPolicy,
    onRefreshPages: () -> Unit,
    viewportSize: IntSize,
    // False while this chapter is the outgoing one of a transition: it must
    // not write an anchor, which would switch the session back (applyAnchor
    // sets activeChapterIndex from the anchor's chapter).
    isActiveChapter: () -> Boolean = { true },
) {
    val initialPage = remember(document, pages, anchor) {
        document.pageIndexForAnchor(anchor)
    }
    val context = LocalContext.current
    // Keyed by chapter: a new chapter gets a pager created at the correct page
    // (first page going forward, last page going back) instead of keeping the
    // previous chapter's index (out of range) and jumping with scrollToPage(0).
    val pagerState = key(document.chapterUrl) {
        rememberPagerState(initialPage = initialPage, pageCount = { pages.size })
    }
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(MinMangaScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomed by rememberUpdatedState(scale > MinMangaScale + 0.01f)
    val currentOnVisiblePage by rememberUpdatedState(onVisiblePage)
    val currentChromeVisible by rememberUpdatedState(chromeVisible)

    // Restore on chapter change or a re-entered session. state.anchor is not a
    // key: settling updates it and would fight the pager.
    LaunchedEffect(document, pages, scrollToken) {
        val target = document.pageIndexForAnchor(anchor)
        if (target in pages.indices) pagerState.scrollToPage(target)
    }

    LaunchedEffect(pagerState.settledPage) {
        scale = MinMangaScale
        offset = Offset.Zero
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { pageIndex ->
                if (isActiveChapter()) currentOnVisiblePage(pageIndex)
            }
    }

    LaunchedEffect(pagerState, pages, chapterIndex, document.chapterUrl, scrubber) {
        snapshotFlow { pagerState.settledPage to scrubber.dragging }
            .distinctUntilChanged()
            .collect { (pageIndex, dragging) ->
                if (dragging) return@collect
                if (!isActiveChapter()) return@collect
                val page = pages.getOrNull(pageIndex) ?: return@collect
                onAnchor(ReadingAnchor(document.chapterUrl, page.id, 0), 0)
                if (pageIndex == pages.lastIndex) onChapterFinished(chapterIndex)
            }
    }

    // The scrubber drives the pager directly; the anchor collector above stays
    // silent until the drag ends.
    SideEffect {
        scrubber.bind { target ->
            pagerState.requestScrollToPage(target.coerceIn(0, pages.lastIndex))
        }
    }

    // Warm the 4-6 surrounding pages at the exact size the visible page asks
    // for, so the memory-cache hit is valid instead of decoding twice.
    val pageRequestSize = mangaPagedRequestSize(viewportSize.width, viewportSize.height)
    LaunchedEffect(pagerState.currentPage, pages, pageRequestSize, imageHeadersFor) {
        val requestSize = pageRequestSize ?: return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        mangaPagedPreloadIndices(pagerState.currentPage, pages.size).forEach { index ->
            val ref = pages[index].imageRef?.takeIf { it.isNotBlank() } ?: return@forEach
            loader.enqueue(mangaImageRequest(context, ref, requestSize, Scale.FIT, imageHeadersFor))
        }
    }

    fun turnTo(pageTurn: MangaPageTurn) {
        // Transitions off means an instant page swap, not a shorter animation.
        fun goTo(target: Int) = scope.launch {
            if (style.pageTransition) pagerState.animateScrollToPage(target)
            else pagerState.scrollToPage(target)
        }
        when (pageTurn) {
            MangaPageTurn.Next -> if (pagerState.currentPage < pages.lastIndex) {
                goTo(pagerState.currentPage + 1)
            } else if (canNextChapter) {
                onNextChapter()
            }
            MangaPageTurn.Previous -> if (pagerState.currentPage > 0) {
                goTo(pagerState.currentPage - 1)
            } else if (canPrevChapter) {
                onPrevChapter()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(
                pages,
                style.direction,
                style.tapZoneInvert,
                style.doubleTapZoom,
                style.pagedNavigation,
                canPrevChapter,
                canNextChapter,
            ) {
                // Main-pass taps: the chrome overlay above consumes its own
                // buttons first, so the pager never steals them.
                detectTapGestures(
                    onTap = { position ->
                        if (currentChromeVisible) {
                            onToggleChrome()
                        } else {
                            val side = mangaTapSide(position.x, size.width.toFloat())
                            if (side == MangaTapSide.Center) {
                                onToggleChrome()
                            } else if (style.pagedNavigation != MangaPagedNavigation.Swipe) {
                                style.tapTurn(side)?.let { pageTurn -> turnTo(pageTurn) }
                            }
                        }
                    },
                    onDoubleTap = if (style.doubleTapZoom) {
                        {
                            if (currentChromeVisible) {
                                onToggleChrome()
                            } else if (scale > MinMangaScale + 0.01f) {
                                scale = MinMangaScale
                                offset = Offset.Zero
                            } else {
                                scale = DoubleTapZoomScale
                                offset = Offset.Zero
                            }
                        }
                    } else {
                        null
                    },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = style.direction == MangaReadingDirection.Rtl,
            beyondViewportPageCount = 2,
            userScrollEnabled = style.pagedNavigation != MangaPagedNavigation.TapEdges &&
                scale <= MinMangaScale + 0.01f,
        ) { pageIndex ->
            val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(page.id) {
                        detectMangaGestures(
                            zoomed = { zoomed },
                            onGesture = { zoomChange, panChange ->
                                val nextScale = (scale * zoomChange)
                                    .coerceIn(MinMangaScale, MaxMangaScale)
                                scale = nextScale
                                offset = if (nextScale <= MinMangaScale + 0.01f) {
                                    Offset.Zero
                                } else {
                                    clampMangaOffset(offset + panChange, nextScale, viewportSize)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                MangaPageImage(
                    url = page.imageRef.orEmpty(),
                    contentDescription = page.original.ifBlank {
                        stringResource(
                            R.string.rd_manga_page_fmt,
                            pageIndex + 1,
                            pages.size,
                        )
                    },
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter,
                    imageHeadersFor = imageHeadersFor,
                    retryPolicy = retryPolicy,
                    onRefreshPages = onRefreshPages,
                    requestSize = pageRequestSize,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
    }
}

@Composable
private fun MangaWebtoonView(
    document: ReaderDocument,
    pages: List<ReaderBlock>,
    chapterIndex: Int,
    anchor: ReadingAnchor?,
    scrollToken: Int,
    style: MangaReaderStyle,
    colorFilter: ColorFilter?,
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onAnchor: (ReadingAnchor, Int) -> Unit,
    onChapterFinished: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onVisiblePage: (Int) -> Unit,
    scrubber: MangaScrubberState,
    imageHeadersFor: (String) -> Map<String, String>,
    retryPolicy: MangaPageRetryPolicy,
    onRefreshPages: () -> Unit,
    viewportSize: IntSize,
    feed: List<MangaFeedPage>,
    onVisibleChapter: (Int, ReadingAnchor?) -> Unit,
    onNearStart: (Int) -> Unit,
    onNearEnd: () -> Unit,
) {
    // One endless strip: the active chapter plus any neighbour already loaded.
    val strip = remember(feed, document, pages) {
        feed.ifEmpty { MangaFeedPlanner.single(document) }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = remember {
            strip.indexOfFirst { it.chapterUrl == anchor?.chapterUrl && it.page.id == anchor?.blockId }
                .coerceAtLeast(0)
        },
    )
    val activeChapter by rememberUpdatedState(chapterIndex)
    val currentOnVisiblePage by rememberUpdatedState(onVisiblePage)
    val currentOnVisibleChapter by rememberUpdatedState(onVisibleChapter)
    val currentOnAnchor by rememberUpdatedState(onAnchor)
    val currentOnFinished by rememberUpdatedState(onChapterFinished)
    val currentOnNearStart by rememberUpdatedState(onNearStart)
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    val context = LocalContext.current
    // Measured page heights are cached by URL, so a page keeps its height across
    // chapter stitches and scroll-backs.
    val pageRatios = remember { mutableStateMapOf<String, Float>() }
    val finishedChapters = remember { mutableSetOf<Int>() }

    // An explicit restore (open, or a chapter jump from the paged view) may
    // move the strip; continuous scrolling itself never re-anchors.
    LaunchedEffect(scrollToken) {
        val target = strip.indexOfFirst {
            it.chapterUrl == anchor?.chapterUrl && it.page.id == anchor?.blockId
        }
        if (target >= 0) listState.scrollToItem(target)
    }

    // One collector drives the counter, the anchor and the active chapter.
    LaunchedEffect(listState, strip, scrubber) {
        snapshotFlow { listState.firstVisibleItemIndex to scrubber.dragging }
            .distinctUntilChanged()
            .collect { (index, dragging) ->
                val item = strip.getOrNull(index) ?: return@collect
                currentOnVisiblePage(item.pageIndex)
                if (item.chapterIndex != activeChapter) {
                    currentOnVisibleChapter(
                        item.chapterIndex,
                        ReadingAnchor(item.chapterUrl, item.page.id, 0),
                    )
                }
                if (!dragging) {
                    currentOnAnchor(ReadingAnchor(item.chapterUrl, item.page.id, 0), 0)
                }
            }
    }

    SideEffect {
        scrubber.bind { target ->
            val index = strip.indexOfFirst {
                it.chapterIndex == activeChapter && it.pageIndex == target
            }
            if (index >= 0) listState.requestScrollToItem(index)
        }
    }

    // A chapter counts as read once its last page is on screen.
    LaunchedEffect(listState, strip) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val item = strip.getOrNull(lastVisible) ?: return@collect
                if (item.pageIndex == item.chapterPageCount - 1 &&
                    finishedChapters.add(item.chapterIndex)
                ) {
                    currentOnFinished(item.chapterIndex)
                }
            }
    }

    // Keep the strip fed without moving it: load the chapter before the strip's
    // first one, and warm the next one before the active chapter runs out.
    LaunchedEffect(listState, strip) {
        snapshotFlow {
            val firstIndex = listState.firstVisibleItemIndex
            val lastIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val first = strip.getOrNull(firstIndex)
            val last = strip.getOrNull(lastIndex)
            (if (firstIndex <= 1) first?.chapterIndex else null) to
                (last != null && last.chapterIndex == activeChapter &&
                    last.pageIndex >= last.chapterPageCount - 2)
        }
            .distinctUntilChanged()
            .collect { (firstChapter, nearActiveEnd) ->
                firstChapter?.let { currentOnNearStart(it - 1) }
                if (nearActiveEnd) currentOnNearEnd()
            }
    }

    val horizontalPadding = style.webtoonPadding.toDp()
    val horizontalPaddingPx = with(LocalDensity.current) { horizontalPadding.roundToPx() }
    val pageWidth = (viewportSize.width - horizontalPaddingPx * 2).coerceAtLeast(1)
    val webtoonRequestSize = mangaWebtoonRequestSize(pageWidth)

    // Screen-height budget over the whole strip: only what the viewport can
    // actually reach is warmed, with known heights from decoded pages.
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        strip,
        viewportSize,
        horizontalPaddingPx,
        imageHeadersFor,
    ) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val requestSize = webtoonRequestSize ?: return@LaunchedEffect
        val ratios = strip.map { item -> item.page.imageRef?.let { pageRatios[it] } }
        val loader = SingletonImageLoader.get(context)
        mangaWebtoonPreloadIndices(
            currentPage = listState.firstVisibleItemIndex,
            pageCount = strip.size,
            pageRatios = ratios,
            viewportHeightPx = viewportSize.height.toFloat(),
            pageWidthPx = pageWidth.toFloat(),
        ).forEach { index ->
            val ref = strip.getOrNull(index)?.page?.imageRef?.takeIf { it.isNotBlank() }
                ?: return@forEach
            loader.enqueue(mangaImageRequest(context, ref, requestSize, Scale.FILL, imageHeadersFor))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onToggleChrome() }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = 24.dp,
            ),
        ) {
            itemsIndexed(strip, key = { _, item -> item.id }) { index, item ->
                // Every page of every stitched chapter is one continuous list;
                // a divider marks the seam instead of a chapter boundary card.
                if (item.chapterStart && index > 0) {
                    MangaChapterDivider(item.chapterTitle)
                }
                val ratio = item.page.imageRef?.let { pageRatios[it] }
                // The measured ratio only reserves layout height. The decode
                // size stays the pre-decode fallback so onSuccess cannot
                // recreate the request and flash a second decode.
                val effectiveRatio = ratio ?: MangaWebtoonFallbackRatio
                MangaPageImage(
                    url = item.page.imageRef.orEmpty(),
                    contentDescription = item.page.original.ifBlank {
                        stringResource(
                            R.string.rd_manga_page_fmt,
                            item.pageIndex + 1,
                            item.chapterPageCount,
                        )
                    },
                    contentScale = ContentScale.FillWidth,
                    colorFilter = colorFilter,
                    imageHeadersFor = imageHeadersFor,
                    retryPolicy = retryPolicy,
                    onRefreshPages = onRefreshPages,
                    onImageRatio = { loaded ->
                        item.page.imageRef?.let { pageRatios[it] = loaded }
                    },
                    requestSize = webtoonRequestSize,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / effectiveRatio)
                        .clipToBounds(),
                )
            }
            // The seamless strip only continues when the next chapter is
            // actually stitched. If its load failed (or was never started) the
            // end card stays the explicit way forward instead of a dead strip.
            val nextChapterStitched =
                strip.lastOrNull()?.chapterIndex?.let { it > chapterIndex } == true
            if (mangaStripNeedsEndCard(nextChapterStitched, canNextChapter)) {
                item(key = "manga-strip-end") {
                    MangaChapterEnd(
                        canPrevChapter = canPrevChapter,
                        canNextChapter = canNextChapter,
                        onPrevChapter = onPrevChapter,
                        onNextChapter = onNextChapter,
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaChapterDivider(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The webtoon strip ends with an explicit card unless the next chapter is
 * actually stitched into it: a next chapter that exists but whose load failed
 * must still offer "Next chapter" instead of a dead strip (regression guard).
 */
internal fun mangaStripNeedsEndCard(
    nextChapterStitched: Boolean,
    canNextChapter: Boolean,
): Boolean = !(canNextChapter && nextChapterStitched)

/**
 * The end card is the webtoon's explicit chapter boundary: next/previous
 * chapter actions and a last-chapter label instead of a dead strip.
 */
@Composable
private fun MangaChapterEnd(
    canPrevChapter: Boolean,
    canNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.rd_manga_end_of_chapter),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        if (!canNextChapter) {
            Text(
                text = stringResource(R.string.rd_manga_last_chapter),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        if (canNextChapter) {
            Button(
                onClick = onNextChapter,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.rd_manga_next_chapter))
            }
        }
        if (canPrevChapter) {
            OutlinedButton(
                onClick = onPrevChapter,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.rd_manga_prev_chapter))
            }
        }
    }
}

@Composable
private fun MangaPageImage(
    url: String,
    contentDescription: String,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    imageHeadersFor: (String) -> Map<String, String>,
    retryPolicy: MangaPageRetryPolicy,
    onRefreshPages: () -> Unit,
    requestSize: IntSize? = null,
    onImageRatio: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(false) }
    var autoRetryJob by remember(url) { mutableStateOf<Job?>(null) }
    // Same scale AsyncImage would derive from contentScale, but fixed on the
    // request so prefetch and visible page decode identically.
    val requestScale = if (contentScale == ContentScale.Fit) Scale.FIT else Scale.FILL
    val request = remember(url, attempt, requestSize, requestScale, imageHeadersFor) {
        requestSize?.let { mangaImageRequest(context, url, it, requestScale, imageHeadersFor) }
            ?: ImageRequest.Builder(context)
                .data(url)
                .withMangaHeaders(url, imageHeadersFor)
                .build()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        // key() restarts Coil after a retry: the request differs, but AsyncImage
        // also needs a new painter for a failed previous load.
        key(attempt) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                colorFilter = colorFilter,
                placeholder = MangaPagePlaceholder,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { success ->
                    failed = false
                    val size = success.painter.intrinsicSize
                    if (onImageRatio != null && size.width > 0f && size.height > 0f) {
                        onImageRatio(size.height / size.width)
                    }
                },
                onError = {
                    failed = true
                    when (val decision = retryPolicy.onError(url)) {
                        is MangaPageRetry.Retry -> {
                            autoRetryJob?.cancel()
                            autoRetryJob = scope.launch {
                                delay(decision.delayMs)
                                failed = false
                                attempt++
                            }
                        }
                        // The page list is stale (rotated CDN tokens): ask for
                        // one refresh, the tile stays until it lands.
                        MangaPageRetry.RefreshPages -> onRefreshPages()
                        MangaPageRetry.Stop -> Unit
                    }
                },
            )
        }
        if (failed) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.rd_manga_page_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = {
                        failed = false
                        attempt++
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text(stringResource(R.string.rd_retry))
                }
            }
        }
    }
}

/**
 * One request shape for a visible page and its prefetch: same data, size,
 * scale and headers. The explicit scale stops AsyncImage from deriving FILL
 * from ContentScale.FillWidth (webtoon) while the prefetch used the default
 * FIT, which made the prefetched bitmap fail Coil's size validation.
 */
private fun mangaImageRequest(
    context: Context,
    url: String,
    size: IntSize,
    scale: Scale,
    headersFor: (String) -> Map<String, String>,
): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .size(size.width, size.height)
    .scale(scale)
    .withMangaHeaders(url, headersFor)
    .build()

private fun MangaReadingMode.imageScale(): Scale =
    if (this == MangaReadingMode.Webtoon) Scale.FILL else Scale.FIT

private fun MangaWebtoonPadding.toDp(): Dp = when (this) {
    MangaWebtoonPadding.None -> 0.dp
    MangaWebtoonPadding.Small -> 8.dp
    MangaWebtoonPadding.Medium -> 16.dp
    MangaWebtoonPadding.Large -> 28.dp
}

// Remote pages carry the plugin's per-host Referer/User-Agent; local page
// files (file:/content:) never need request headers.
// shortcut: cookies are overridden in fetchImage now, so this header path is
// redundant and should be removed in a separate change.
private fun ImageRequest.Builder.withMangaHeaders(
    url: String,
    headersFor: (String) -> Map<String, String>,
): ImageRequest.Builder {
    if (!mangaImageNeedsHeaders(url)) return this
    val headers = headersFor(url)
    if (headers.isNotEmpty()) {
        httpHeaders(
            NetworkHeaders.Builder().apply {
                headers.forEach { (key, value) -> set(key, value) }
            }.build(),
        )
    }
    return this
}

@Composable
private fun MangaBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun mangaColorFilter(filter: MangaColorFilter): ColorFilter? = when (filter) {
    MangaColorFilter.None -> null
    MangaColorFilter.Grayscale -> ColorFilter.colorMatrix(
        ColorMatrix().apply { setToSaturation(0f) },
    )
    MangaColorFilter.Sepia -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    MangaColorFilter.Invert -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/**
 * Pinch-to-zoom and pan that only consumes gestures when there are two touches
 * or the page is already zoomed. At 1x a single-finger drag is left alone so
 * HorizontalPager keeps its swipe.
 *
 * A plain detectTransformGestures consumes every drag past touch slop, which
 * killed pager swipes at 1x; this detector needs no separate gesture arena.
 */
private suspend fun PointerInputScope.detectMangaGestures(
    zoomed: () -> Boolean,
    onGesture: (zoomChange: Float, panChange: Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2 || zoomed()) {
                onGesture(event.calculateZoom(), event.calculatePan())
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun clampMangaOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    if (size == IntSize.Zero) return offset
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
}
