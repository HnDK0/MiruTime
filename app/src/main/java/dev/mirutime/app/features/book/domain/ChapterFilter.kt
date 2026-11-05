package dev.mirutime.app.features.book.domain

// Read-state filter for the chapter list. Client-side only: search and sort
// stay independent and compose with it.
enum class ChapterFilter {
    All,
    Read,
    Unread,
}

fun ChapterFilter.matches(isRead: Boolean): Boolean = when (this) {
    ChapterFilter.All -> true
    ChapterFilter.Read -> isRead
    ChapterFilter.Unread -> !isRead
}
