package dev.mirutime.app.features.reader.data

import android.content.Context
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.ReadingAnchor
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.ProgressEntity
import dev.mirutime.feature.reader.SavedReading
import dev.mirutime.feature.reader.api.ReaderPositionStore
import org.json.JSONObject

interface ReadingPositionRepository : ReaderPositionStore

class InMemoryReadingPositionRepository : ReadingPositionRepository {
    private val values = mutableMapOf<String, SavedReading>()

    override suspend fun load(bookUrl: String): SavedReading? = values[bookUrl]

    override suspend fun save(bookUrl: String, saved: SavedReading) {
        val current = values[bookUrl]
        if (current == null || saved.seq >= current.seq) values[bookUrl] = saved
    }

    override suspend fun clear(bookUrl: String) {
        values.remove(bookUrl)
    }
}

class PrefsReadingPositionRepository(context: Context) : ReadingPositionRepository {
    private val prefs = context.applicationContext
        .getSharedPreferences("reader_positions", Context.MODE_PRIVATE)

    override suspend fun load(bookUrl: String): SavedReading? {
        val raw = prefs.getString(bookUrl, null) ?: return null
        return runCatching { JSONObject(raw).toSavedReading() }.getOrNull()
    }

    override suspend fun save(bookUrl: String, saved: SavedReading) {
        val current = load(bookUrl)
        if (current != null && current.seq > saved.seq) return
        prefs.edit().putString(bookUrl, saved.toJson().toString()).apply()
    }

    override suspend fun clear(bookUrl: String) {
        prefs.edit().remove(bookUrl).apply()
    }
}

private fun SavedReading.toJson(): JSONObject = JSONObject().apply {
    put("offsetPx", offsetPx)
    put("seq", seq)
    put("updatedAt", updatedAt)
    anchor?.let {
        put("chapterUrl", it.chapterUrl)
        put("blockIndex", it.blockId.index)
        put("charOffset", it.charOffset)
        put("sentenceIndex", it.sentenceIndex ?: JSONObject.NULL)
    }
}

private fun JSONObject.toSavedReading(): SavedReading {
    val chapterUrl = optString("chapterUrl", "")
    val anchor = if (chapterUrl.isBlank()) {
        null
    } else {
        ReadingAnchor(
            chapterUrl = chapterUrl,
            blockId = BlockId(chapterUrl, optInt("blockIndex", 0)),
            charOffset = optInt("charOffset", 0),
            sentenceIndex = if (isNull("sentenceIndex")) null else optInt("sentenceIndex"),
        )
    }
    return SavedReading(
        anchor = anchor,
        offsetPx = optInt("offsetPx", 0),
        seq = optLong("seq", 0),
        updatedAt = optLong("updatedAt", 0),
    )
}


/**
 * Room-backed progress. The in-memory [SavedReading] API stays the same, but
 * the row is keyed by bookId and chapterId; URL changes via rebase do not
 * orphan it.
 */
class RoomReadingPositionRepository(
    private val library: LibraryRepository,
) : ReadingPositionRepository {
    override suspend fun load(bookUrl: String): SavedReading? {
        val book = library.book(bookUrl) ?: return null
        val progress = library.progress(book.bookId) ?: return null
        val chapter = library.chapter(progress.chapterId) ?: return null
        return SavedReading(
            anchor = ReadingAnchor(
                chapterUrl = chapter.url,
                blockId = BlockId(chapter.url, progress.blockIndex),
                charOffset = progress.charOffset,
                sentenceIndex = progress.sentenceIndex,
            ),
            offsetPx = progress.offsetPx,
            seq = progress.seq,
            updatedAt = progress.updatedAt,
        )
    }

    override suspend fun save(bookUrl: String, saved: SavedReading) {
        val book = library.book(bookUrl) ?: return
        val anchor = saved.anchor ?: return
        val chapter = library.chapterByUrl(book.bookId, anchor.chapterUrl) ?: return
        library.saveProgress(
            ProgressEntity(
                bookId = book.bookId,
                chapterId = chapter.chapterId,
                blockIndex = anchor.blockId.index,
                charOffset = anchor.charOffset,
                sentenceIndex = anchor.sentenceIndex,
                offsetPx = saved.offsetPx,
                split = false,
                seq = saved.seq,
                updatedAt = saved.updatedAt,
            ),
        )
    }

    override suspend fun clear(bookUrl: String) {
        library.book(bookUrl)?.let { library.clearProgress(it.bookId) }
    }
}
