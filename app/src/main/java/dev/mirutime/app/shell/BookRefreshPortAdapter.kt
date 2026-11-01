package dev.mirutime.app.shell

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.library.data.LibraryBookRefreshPort
import dev.mirutime.app.features.library.data.RefreshResult
import dev.mirutime.core.api.Outcome

/** Composition-root adapter: the shell may import both features. */
class BookRefreshPortAdapter(
    private val provider: BookDataProvider,
) : LibraryBookRefreshPort {
    override suspend fun refreshChapterCount(bookUrl: String): Int? =
        refreshChapterCountResult(bookUrl).count

    override suspend fun refreshChapterCountResult(bookUrl: String): RefreshResult =
        when (val outcome = provider.refreshChapterCountOutcome(bookUrl)) {
            is Outcome.Ok -> RefreshResult(count = outcome.value)
            is Outcome.Err -> RefreshResult(
                count = null,
                error = outcome.error as? dev.mirutime.core.api.PluginError,
            )
        }

    override suspend fun chapterListHash(bookUrl: String): String? =
        provider.chapterListHash(bookUrl)
}
