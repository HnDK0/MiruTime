package dev.mirutime.feature.reader

enum class MangaReadingMode { Paged, Webtoon }

enum class MangaReadingDirection { Ltr, Rtl }

enum class MangaColorFilter { None, Grayscale, Sepia, Invert }

enum class MangaWebtoonPadding { None, Small, Medium, Large }

/** How paged mode turns pages: swipe, edge taps, or both. */
enum class MangaPagedNavigation { Swipe, TapEdges, Both }

/** How the viewer turns pages: paged pager or a continuous vertical strip. */
data class MangaReaderStyle(
    val mode: MangaReadingMode = MangaReadingMode.Webtoon,
    val direction: MangaReadingDirection = MangaReadingDirection.Ltr,
    val tapZoneInvert: Boolean = false,
    val showPageCounter: Boolean = true,
    val webtoonPadding: MangaWebtoonPadding = MangaWebtoonPadding.Medium,
    // Double tap opens the menu instead of zooming; mutually exclusive with zoom.
    val doubleTapMenu: Boolean = false,
    val brightness: Float = 1f,
    val colorFilter: MangaColorFilter = MangaColorFilter.None,
    val pagedNavigation: MangaPagedNavigation = MangaPagedNavigation.Both,
    val pageTransition: Boolean = true,
) {
    fun sanitized(): MangaReaderStyle = copy(
        brightness = brightness.coerceIn(MinBrightness, MaxBrightness),
    )

    companion object {
        const val MinBrightness = 0.2f
        const val MaxBrightness = 1f
        val Default = MangaReaderStyle()
    }
}

enum class MangaTapSide { Left, Center, Right }

enum class MangaPageTurn { Previous, Next }

enum class MangaEndAction { Continue, NextChapter, EndOfBook }

/** Left and right quarters turn pages; the middle toggles chrome/zoom. */
fun mangaTapSide(x: Float, width: Float): MangaTapSide = when {
    width <= 0f -> MangaTapSide.Center
    x < width * 0.25f -> MangaTapSide.Left
    x > width * 0.75f -> MangaTapSide.Right
    else -> MangaTapSide.Center
}

/**
 * In RTL the next page sits to the left, so the left quarter advances.
 * [tapZoneInvert] flips whichever side the direction picked.
 */
fun MangaReaderStyle.leftTapAdvances(): Boolean =
    (direction == MangaReadingDirection.Rtl) != tapZoneInvert

fun MangaReaderStyle.tapTurn(side: MangaTapSide): MangaPageTurn? = when (side) {
    MangaTapSide.Center -> null
    MangaTapSide.Left -> if (leftTapAdvances()) MangaPageTurn.Next else MangaPageTurn.Previous
    MangaTapSide.Right -> if (leftTapAdvances()) MangaPageTurn.Previous else MangaPageTurn.Next
}

/** Last page either advances the chapter or ends the book. */
fun mangaEndAction(
    pageIndex: Int,
    lastPageIndex: Int,
    canNextChapter: Boolean,
): MangaEndAction = when {
    pageIndex < lastPageIndex -> MangaEndAction.Continue
    canNextChapter -> MangaEndAction.NextChapter
    else -> MangaEndAction.EndOfBook
}

/** Webtoon progress is the page at the top of the viewport. */
fun webtoonAnchorIndex(firstVisibleItemIndex: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else firstVisibleItemIndex.coerceIn(0, pageCount - 1)

fun parseMangaReadingMode(raw: String?): MangaReadingMode =
    MangaReadingMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: MangaReadingMode.Webtoon

fun parseMangaReadingDirection(raw: String?): MangaReadingDirection =
    MangaReadingDirection.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: MangaReadingDirection.Ltr

fun parseMangaColorFilter(raw: String?): MangaColorFilter =
    MangaColorFilter.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: MangaColorFilter.None

fun parseMangaWebtoonPadding(raw: String?): MangaWebtoonPadding =
    MangaWebtoonPadding.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: MangaWebtoonPadding.Medium

fun parseMangaPagedNavigation(raw: String?): MangaPagedNavigation =
    MangaPagedNavigation.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: MangaPagedNavigation.Both
