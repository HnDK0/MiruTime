package dev.mirutime.app.features.video.data

import dev.mirutime.data.library.bookKeyUrl

/**
 * Download rows store the raw book URL, while the screen route may carry the
 * opaque BookKey. Compare identities, never the string, or every per-chapter
 * progress row disappears.
 */
fun videoDownloadsForBook(
    items: List<VideoDownloadItem>,
    bookUrl: String,
): Map<Int, VideoDownloadItem> {
    val identity = bookKeyUrl(bookUrl)
    return items.filter { bookKeyUrl(it.bookUrl) == identity }.associateBy { it.chapterIndex }
}
