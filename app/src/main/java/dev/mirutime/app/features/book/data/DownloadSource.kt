package dev.mirutime.app.features.book.data

import dev.mirutime.data.library.bookKeyInstallId

/**
 * Resolves the plugin that serves a downloaded chapter. The download worker
 * carries the persisted `BookEntity`, so its `installId` wins over any
 * in-memory/prefs index that may be cold in a fresh WorkManager process. A
 * route BookKey still resolves through its embedded install id.
 */
internal fun resolveDownloadSource(
    bookUrl: String,
    persisted: String?,
    indexed: () -> String?,
): String? = persisted?.takeIf { it.isNotBlank() }
    ?: indexed()?.takeIf { it.isNotBlank() }
    ?: bookKeyInstallId(bookUrl)?.takeIf { it.isNotBlank() }
