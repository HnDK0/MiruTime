package dev.mirutime.feature.reader

// The parser lives in :core-reader-model, so its block model moved with it.
// The alias keeps the reader-domain name available to app call sites.
data class ReaderProgress(
    val chapterIndex: Int,
    val itemIndex: Int,
    val offsetPx: Int,
    val splitSentences: Boolean,
)
