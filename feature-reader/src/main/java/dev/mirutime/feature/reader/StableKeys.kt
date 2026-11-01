package dev.mirutime.feature.reader

// Stable LazyList identities owned by the reader feature.
internal fun readerBlockKey(blockIndex: Int): String = "block:$blockIndex"

internal fun feedItemKey(entryId: String): String = "feed:$entryId"
