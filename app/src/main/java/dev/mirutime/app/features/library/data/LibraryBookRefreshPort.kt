package dev.mirutime.app.features.library.data

import dev.mirutime.core.api.PluginError

/**
 * Neutral refresh seam for library update checks. The shell adapts the book
 * provider, so this feature never imports book models.
 *
 * @return the refreshed chapter count, or null on failure/empty.
 */
interface LibraryBookRefreshPort {
    suspend fun refreshChapterCount(bookUrl: String): Int?

    /** Typed variant: keeps the plugin failure for the Updates screen. */
    suspend fun refreshChapterCountResult(bookUrl: String): RefreshResult =
        RefreshResult(count = refreshChapterCount(bookUrl))

    /** Cheap plugin chapter-list hash; null when the source has no hash. */
    suspend fun chapterListHash(bookUrl: String): String? = null
}

data class RefreshResult(
    val count: Int? = null,
    val error: PluginError? = null,
)
