package dev.mirutime.app.features.translation.data

import dev.mirutime.data.library.libraryBookKey

/**
 * A worker gets the stored raw URL, while per-book translation settings are
 * keyed by the route BookKey (installId + URL). The route key is the specific
 * identity and must win: `activeTranslationMode` falls back to plugin/global,
 * so resolving the raw URL first would pick a less specific config and cancel
 * the queued rows.
 */
internal fun translationConfigKeys(bookUrl: String, installId: String): List<String> =
    listOf(libraryBookKey(installId, bookUrl), bookUrl).distinct()
