package dev.mirutime.feature.reader.ui

import androidx.compose.ui.unit.IntSize
import dev.mirutime.feature.reader.MangaPagedNavigation
import kotlin.math.roundToInt

/**
 * Manga prefetch window. Both modes keep 4-6 pages warm: paged around the
 * current page, webtoon by estimated page height (actual viewport, not ±1).
 */
internal const val MangaPreloadWindow = 6
internal const val MangaPreloadBehind = 2

internal const val MangaWebtoonFallbackRatio = 3f
internal const val MangaWebtoonPreloadScreensAhead = 2f
internal const val MangaWebtoonPreloadScreensBehind = 1f
internal const val MangaWebtoonPreloadAheadPages = 4
internal const val MangaWebtoonPreloadBehindPages = 2

/** Pages around [currentPage] to warm; the current page is already displayed. */
internal fun mangaPagedPreloadIndices(currentPage: Int, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    var start = (currentPage - MangaPreloadBehind).coerceAtLeast(0)
    var end = (start + MangaPreloadWindow - 1).coerceAtMost(pageCount - 1)
    start = (end - MangaPreloadWindow + 1).coerceAtLeast(0)
    return (start..end).filter { it != currentPage }
}

/**
 * Webtoon pages to warm, measured in screen heights: taller pages consume the
 * budget sooner, so a tall-page chapter does not enqueue the same six full
 * images as a short-page one. [pageRatios] is height / width per page; null
 * means "not loaded yet" and uses a typical webtoon aspect.
 */
internal fun mangaWebtoonPreloadIndices(
    currentPage: Int,
    pageCount: Int,
    pageRatios: List<Float?>,
    viewportHeightPx: Float,
    pageWidthPx: Float,
): List<Int> {
    if (pageCount <= 0 || pageWidthPx <= 0f || viewportHeightPx <= 0f) return emptyList()

    fun pageHeight(index: Int): Float =
        (pageRatios.getOrNull(index) ?: MangaWebtoonFallbackRatio)
            .coerceAtLeast(0.1f) * pageWidthPx

    val ahead = mutableListOf<Int>()
    var covered = 0f
    val aheadTarget = viewportHeightPx * MangaWebtoonPreloadScreensAhead
    var index = currentPage + 1
    while (
        index < pageCount &&
        ahead.size < MangaWebtoonPreloadAheadPages &&
        (covered < aheadTarget || ahead.size < 3)
    ) {
        ahead += index
        covered += pageHeight(index)
        index++
    }

    val behind = mutableListOf<Int>()
    covered = 0f
    val behindTarget = viewportHeightPx * MangaWebtoonPreloadScreensBehind
    index = currentPage - 1
    while (
        index >= 0 &&
        behind.size < MangaWebtoonPreloadBehindPages &&
        (covered < behindTarget || behind.size < 2)
    ) {
        behind += index
        covered += pageHeight(index)
        index--
    }

    return ahead + behind
}

internal fun mangaScrubberPage(value: Float, pageCount: Int): Int =
    if (pageCount <= 0) 0 else value.roundToInt().coerceIn(0, pageCount - 1)
