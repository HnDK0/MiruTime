package dev.mirutime.app.features.video.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.video.domain.VideoChapterSource
import dev.mirutime.app.features.video.domain.VideoEpisode
import dev.mirutime.app.features.video.domain.VideoLoadResult
import dev.mirutime.app.features.video.domain.videoRequestHeaders
import dev.mirutime.app.features.video.domain.videoResumePositionMs
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.VideoStream
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.LibraryRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Video data lane. Streams come only from [BookDataProvider.videoStreams] —
 * the plugin resolves its own episode page, so the host never fetches chapter
 * HTML here (unlike the text reader).
 *
 * Progress is the existing `video_progress` table: milliseconds, one row per
 * book+chapter, 95% marks the chapter read. No offline manifest yet.
 */
class VideoSourceRepository(
    private val provider: BookDataProvider,
    private val library: LibraryRepository?,
    private val pluginHeaders: suspend (String) -> Map<String, String> = { emptyMap() },
    private val now: () -> Long = System::currentTimeMillis,
    // A completed media3 download is already on disk; do not re-resolve it
    // through the plugin, or offline playback fails before the cache is read.
    private val offlineStream: suspend (bookUrl: String, chapterIndex: Int) -> VideoStream? =
        { _, _ -> null },
) {
    suspend fun load(bookUrl: String, chapterIndex: Int): VideoLoadResult {
        val book = provider.book(bookUrl)
        val libraryBook = library?.book(bookUrl)
        // Opening a video is a history open, same as opening the text reader:
        // the row must exist before progress can reference bookId/chapterId.
        // Only the first episode pays for it; later episodes reuse the row.
        if (library == null || libraryBook == null) {
            try {
                provider.ensureHistoryBook(bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // playback can continue without a history row; progress then
                // stays unsaved rather than failing the open
            }
        }

        val chapters = provider.chapters(bookUrl)
        val offline = offlineStream(bookUrl, chapterIndex)
        val streams = offline?.let(::listOf)
            ?: when (val result = provider.videoStreams(bookUrl, chapterIndex)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> return VideoLoadResult.Error(result.error.videoMessage())
            }
        if (streams.isEmpty()) return VideoLoadResult.Empty

        try {
            persistChapters(bookUrl, chapterIndex, chapters)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // same: missing rows only mean progress cannot attach yet
        }
        val episodes = chapters.map { chapter ->
            VideoEpisode(
                index = chapter.index,
                title = chapter.title,
                isRead = chapter.isRead,
            )
        }
        return VideoLoadResult.Ready(
            VideoChapterSource(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                bookTitle = book?.title ?: libraryBook?.title
                    ?: bookUrl.substringAfterLast('/').ifBlank { bookUrl },
                chapterTitle = episodes.firstOrNull { it.index == chapterIndex }?.title.orEmpty(),
                streams = streams,
                episodes = episodes,
                startPositionMs = savedPosition(bookUrl, chapterIndex),
                offline = offline != null,
            ),
        )
    }

    /** Plugin host defaults first, then the stream's own per-source headers. */
    suspend fun headersFor(stream: VideoStream): Map<String, String> =
        videoRequestHeaders(pluginHeaders(stream.url), stream.headers)

    /** Persists a position and lets the data layer apply the 95% read rule. */
    suspend fun saveProgress(
        bookUrl: String,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
    ) {
        val repo = library ?: return
        val book = repo.book(bookUrl) ?: return
        val chapter = repo.chapterByIndex(book.bookId, chapterIndex) ?: return
        val position = positionMs.coerceAtLeast(0)
        val duration = durationMs.coerceAtLeast(0)
        if (position == 0L && duration == 0L) return
        // Progress persistence is best-effort on the playback path: a closed
        // DB must not crash the player. Cancellation still propagates.
        try {
            repo.saveVideoProgress(
                bookId = book.bookId,
                chapterId = chapter.chapterId,
                positionMs = position,
                durationMs = duration,
                at = now(),
            )
            // History/Continue is book-level; a watched episode must move it.
            repo.touchHistory(book.bookId, chapter.chapterId, chapterIndex, now())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep playing
        }
    }

    private suspend fun savedPosition(bookUrl: String, chapterIndex: Int): Long {
        val repo = library ?: return 0
        return try {
            val book = repo.book(bookUrl) ?: return 0
            val chapter = repo.chapterByIndex(book.bookId, chapterIndex) ?: return 0
            val saved = repo.videoProgress(book.bookId, chapter.chapterId) ?: return 0
            videoResumePositionMs(saved.positionMs, saved.durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }

    /**
     * History-only books may have no chapter rows yet; progress keys need the
     * surrogate ids, so persist the fetched list once (same shape the book
     * provider uses for its cached list).
     */
    private suspend fun persistChapters(
        bookUrl: String,
        chapterIndex: Int,
        chapters: List<dev.mirutime.app.features.book.domain.BookChapter>,
    ) {
        val repo = library ?: return
        if (chapters.isEmpty()) return
        val book = repo.book(bookUrl) ?: return
        // O(1) existence check for the current episode: only the first open
        // writes the whole chapter list.
        if (repo.chapterByIndex(book.bookId, chapterIndex) != null) return
        repo.replaceChapters(
            bookId = book.bookId,
            seeds = chapters.map { chapter ->
                ChapterSeed(
                    index = chapter.index,
                    url = chapter.url ?: "$bookUrl#chapter=${chapter.index}",
                    title = chapter.title,
                )
            },
            at = now(),
        )
    }
}

/** Plugin-owned text only; infrastructure failures get the localized generic. */
private fun MiruError.videoMessage(): String? = when (this) {
    is PluginError.UserMessage -> message.ifBlank { null }
    is PluginError.ScriptError -> detail.ifBlank { null }
    else -> null
}
