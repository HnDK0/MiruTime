package dev.mirutime.app.core

// Stable LazyList identities shared by feature screens. Kept pure (no
// Compose/Android imports) so JVM tests can assert uniqueness directly.
internal fun libraryRowKey(firstBookUrl: String): String = "library-row:$firstBookUrl"

internal fun libraryListKey(bookUrl: String): String = "library:$bookUrl"

internal fun chapterRowKey(chapterIndex: Int): String = "chapter:$chapterIndex"

internal fun catalogBookKey(bookUrl: String): String = "catalog:$bookUrl"

internal fun globalSourceKey(sourceId: String): String = "gs-source:$sourceId"

internal fun globalBookKey(bookUrl: String): String = "gs-book:$bookUrl"

internal fun feedItemKey(entryId: String): String = "feed:$entryId"
