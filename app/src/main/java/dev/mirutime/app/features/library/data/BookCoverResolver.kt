package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookCoverEntity

/**
 * One cover precedence for Library, Dashboard, Book and local catalogue:
 * the downloaded local file first, then the stored remote URL, then the row's
 * legacy coverUrl. A local file resolves to a file: URI, which Coil accepts.
 */
internal suspend fun resolveStoredCover(
    coverStore: BookCoverStore?,
    bookId: Long,
    fallback: String?,
): String? = coverStore?.let { store ->
    store.localCoverUrl(bookId) ?: store.remoteCoverUrl(bookId)
} ?: fallback

/**
 * Batch variant: `cover` came from one `book_covers` query, so this only stats
 * the file and keeps list screens at a constant number of DB queries.
 */
internal fun resolveStoredCover(
    coverStore: BookCoverStore?,
    bookId: Long,
    cover: BookCoverEntity?,
    fallback: String?,
): String? = coverStore?.let { store ->
    store.localCoverUrl(bookId, cover) ?: store.remoteCoverUrl(cover)
} ?: fallback
