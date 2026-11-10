package dev.mirutime.app.features.book.data

/**
 * A manga page is fetched outside the plugin HTML call, so its HTTP status must
 * become the same typed failure the rest of the engine classifies. Status 0 is
 * the image client's transport failure.
 */
internal fun pageFailureFor(status: Int): ChapterDownloadException? = when {
    status in 200..299 -> null
    status == 0 -> ChapterDownloadException(
        ChapterFailureKind.NETWORK,
        retryable = true,
        message = "Manga page network error",
    )
    status == 401 || status == 403 -> ChapterDownloadException(
        ChapterFailureKind.AUTH_REQUIRED,
        retryable = false,
        message = "Manga page HTTP $status",
    )
    status == 429 -> ChapterDownloadException(
        ChapterFailureKind.RATE_LIMITED,
        retryable = false,
        message = "Manga page HTTP 429",
    )
    status == 404 || status == 410 -> ChapterDownloadException(
        ChapterFailureKind.NOT_FOUND,
        retryable = false,
        message = "Manga page HTTP $status",
    )
    status in 500..599 -> ChapterDownloadException(
        ChapterFailureKind.NETWORK,
        retryable = true,
        message = "Manga page HTTP $status",
    )
    else -> ChapterDownloadException(
        ChapterFailureKind.SOURCE,
        retryable = false,
        message = "Manga page HTTP $status",
    )
}

/**
 * A 2xx with a zero-length body (204, truncated CDN response) is a transport
 * failure, not a valid page: retry it, never persist an empty image.
 */
internal fun pageBytesFor(status: Int, bytes: ByteArray?): ByteArray {
    pageFailureFor(status)?.let { throw it }
    return bytes?.takeIf { it.isNotEmpty() } ?: throw ChapterDownloadException(
        ChapterFailureKind.NETWORK,
        retryable = true,
        message = "Manga page is empty",
    )
}
