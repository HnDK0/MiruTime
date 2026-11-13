package dev.mirutime.data.library

/** One playable track of a remote audiobook; [url] is the catalogue URL. */
data class RemoteAudioTrack(val url: String, val title: String)

/**
 * A remote catalogue audiobook. Only metadata and tracks are persisted: the
 * audio bytes stay on the catalogue, so the book owns no `local_book_files`,
 * body or page row and never enters a backup.
 */
data class RemoteAudioBook(
    val installId: String,
    /** Stable catalogue identity (the entry URL), not the manifest URL. */
    val url: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String?,
    val tracks: List<RemoteAudioTrack>,
    /** Catalogue provenance kept in `books.extrasJson` (name/URL for restore). */
    val extrasJson: String = "{}",
    val at: Long,
)

/** Persistence seam for catalogue audiobooks; the controller never writes DB rows itself. */
interface RemoteAudioBookStore {
    /** Creates or merges the row and reconciles tracks; returns the book id. */
    suspend fun save(book: RemoteAudioBook): Long
}

/**
 * Writes the book + one chapter row per track. Re-saving the same identity
 * merges metadata and reconciles chapters by URL, so chapter ids and playback
 * progress (`video_progress`) survive a re-add.
 */
class LibraryRemoteAudioBookStore(
    private val library: LibraryRepository,
) : RemoteAudioBookStore {
    override suspend fun save(book: RemoteAudioBook): Long {
        require(book.tracks.isNotEmpty()) { "an audiobook needs at least one track" }
        val existing = library.book(book.installId, book.url)
        val entity = BookEntity(
            bookId = existing?.bookId ?: 0,
            installId = book.installId,
            url = book.url,
            title = book.title,
            author = book.author,
            coverUrl = book.coverUrl,
            contentType = LibraryContentType.Audio.stored,
            rating = 0.0,
            shelfId = existing?.shelfId ?: "reading",
            totalChapters = book.tracks.size,
            inLibrary = true,
            addedAt = existing?.addedAt ?: book.at,
            updatedAt = book.at,
            lastReadAt = existing?.lastReadAt,
            lastChapterId = existing?.lastChapterId,
            lastChapterIndex = existing?.lastChapterIndex,
            chapterListHash = null,
            lastUpdateAt = null,
            genresJson = "[]",
            description = book.description,
            extrasJson = book.extrasJson.takeIf { it.isNotBlank() } ?: "{}",
        )
        val bookId = library.saveBook(entity)
        library.replaceChapters(
            bookId = bookId,
            seeds = book.tracks.mapIndexed { position, track ->
                ChapterSeed(index = position + 1, url = track.url, title = track.title)
            },
            at = book.at,
        )
        if (existing?.inLibrary != true) library.addToLibrary(bookId, book.at)
        return bookId
    }
}
