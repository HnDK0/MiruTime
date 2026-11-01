package dev.mirutime.app.features.book.data

import dev.mirutime.core.api.PluginError

/**
 * Typed chapter-fetch failure for the download queue. The message is written
 * to `download_tasks.error` and the log, so it must never contain a chapter
 * URL, query string or body text.
 *
 * [retryable] is the queue's automatic-retry decision, not a UI hint:
 * auth/CF/rate-limit/not-found responses are final until the user retries
 * explicitly; transport/server errors may use the bounded WorkManager retries.
 */
enum class ChapterFailureKind {
    AUTH_REQUIRED,
    CLOUDFLARE,
    RATE_LIMITED,
    NOT_FOUND,
    SERVER,
    NETWORK,
    SOURCE,
}

class ChapterDownloadException(
    val kind: ChapterFailureKind,
    val retryable: Boolean,
    message: String,
    // Typed source failure (login/CF/HTTP/class) for the reader and UI detail;
    // null for failures raised before a plugin outcome exists.
    val pluginError: PluginError? = null,
) : Exception(message)
