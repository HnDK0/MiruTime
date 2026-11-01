package dev.mirutime.core.reader

/** Where a chapter payload came from; drives the debug log, never the UI. */
enum class ChapterSource {
    LOCAL_SAF_DIRECT,
    LOCAL_SAF_CACHE,
    IMPORTED_DB_BODY,
    PLUGIN_NETWORK,
    TRANSLATION_CACHE,
}

/** Raw chapter payload; the reader parses it with the current split mode. */
data class ChapterContent(
    val bookUrl: String,
    val chapterIndex: Int,
    val title: String,
    val html: String,
    // Real chapter address from the plugin; null for fixtures (the reader
    // then falls back to its bookUrl#chapter=N key).
    val chapterUrl: String? = null,
    // Set by the provider that produced the payload; the reader boundary logs
    // it once per open.
    val source: ChapterSource? = null,
    // Known for DB-backed local content, absent for straight network reads.
    val chapterId: Long? = null,
)
