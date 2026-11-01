package dev.mirutime.app.features.translation.data

import dev.mirutime.data.library.BookTranslationEntity
import dev.mirutime.data.library.LibraryRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persistence seam for FULL-mode metadata. The controller only needs these
 * three operations, which keeps it JVM-testable without Room.
 */
interface MetadataTranslationStore {
    suspend fun load(bookUrl: String, key: MetadataTranslationKey): StoredMetadataTranslation?

    suspend fun loadTitles(
        bookUrls: List<String>,
        key: MetadataTranslationKey,
    ): Map<String, StoredMetadataTranslation>

    suspend fun save(bookUrl: String, key: MetadataTranslationKey, patch: MetadataTranslationPatch)
}

/** Full translation identity: no cross-language or cross-prompt collisions. */
data class MetadataTranslationKey(
    val source: String,
    val target: String,
    val provider: String,
    val promptHash: String,
    val pipelineVersion: String,
)

data class StoredMetadataTranslation(
    val title: String? = null,
    val description: String? = null,
    val chapterTitles: Map<Int, String> = emptyMap(),
)

/** Null fields mean "leave the stored value alone" (partial merge). */
data class MetadataTranslationPatch(
    val title: String? = null,
    val description: String? = null,
    val chapterTitles: Map<Int, String> = emptyMap(),
)

/**
 * Room-backed store on top of `book_translations`. One key maps to exactly one
 * row; [save] merges into it so a title-only batch cannot wipe a synopsis or
 * chapter titles written by another batch.
 */
class RoomMetadataTranslationStore(
    private val library: LibraryRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : MetadataTranslationStore {

    override suspend fun load(
        bookUrl: String,
        key: MetadataTranslationKey,
    ): StoredMetadataTranslation? {
        val bookId = library.bookIdByUrl(bookUrl) ?: return null
        val row = library.bookTranslation(
            bookId = bookId,
            source = key.source,
            target = key.target,
            provider = key.provider,
            promptHash = key.promptHash,
            pipelineVersion = key.pipelineVersion,
        ) ?: return null
        return row.toStored()
    }

    override suspend fun loadTitles(
        bookUrls: List<String>,
        key: MetadataTranslationKey,
    ): Map<String, StoredMetadataTranslation> {
        if (bookUrls.isEmpty()) return emptyMap()
        val idByUrl = library.bookIdsByUrls(bookUrls)
        if (idByUrl.isEmpty()) return emptyMap()
        val rows = library.bookTranslations(
            bookIds = idByUrl.values.toList(),
            source = key.source,
            target = key.target,
            provider = key.provider,
            promptHash = key.promptHash,
            pipelineVersion = key.pipelineVersion,
        )
        val urlByBookId = idByUrl.entries.associate { (url, id) -> id to url }
        return rows.mapNotNull { (bookId, row) ->
            urlByBookId[bookId]?.let { it to row.toStored() }
        }.toMap()
    }

    override suspend fun save(
        bookUrl: String,
        key: MetadataTranslationKey,
        patch: MetadataTranslationPatch,
    ) {
        val bookId = library.bookIdByUrl(bookUrl) ?: return
        val existing = library.bookTranslation(
            bookId = bookId,
            source = key.source,
            target = key.target,
            provider = key.provider,
            promptHash = key.promptHash,
            pipelineVersion = key.pipelineVersion,
        )
        library.saveBookTranslation(
            mergeMetadataTranslation(
                existing = existing,
                bookId = bookId,
                key = key,
                patch = patch,
                now = now(),
            ),
        )
    }

    private fun BookTranslationEntity.toStored() = StoredMetadataTranslation(
        title = title?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        chapterTitles = decodeChapterTitles(chapterTitlesJson),
    )
}

/**
 * Partial merge: a null patch field keeps the stored value, and chapter titles
 * are unioned with the existing map. Pure so the merge contract is unit-tested
 * without Room.
 */
internal fun mergeMetadataTranslation(
    existing: BookTranslationEntity?,
    bookId: Long,
    key: MetadataTranslationKey,
    patch: MetadataTranslationPatch,
    now: Long,
): BookTranslationEntity = BookTranslationEntity(
    translationId = existing?.translationId ?: 0,
    bookId = bookId,
    source = key.source,
    target = key.target,
    provider = key.provider,
    model = existing?.model,
    promptHash = key.promptHash,
    sourceHash = existing?.sourceHash.orEmpty(),
    pipelineVersion = key.pipelineVersion,
    // Only provider output is stored here; the original title never is.
    title = patch.title ?: existing?.title,
    description = patch.description ?: existing?.description,
    altTitlesJson = existing?.altTitlesJson ?: "[]",
    chapterTitlesJson = encodeChapterTitles(
        decodeChapterTitles(existing?.chapterTitlesJson) + patch.chapterTitles,
    ),
    status = StatusComplete,
    updatedAt = now,
)

internal fun decodeChapterTitles(json: String?): Map<Int, String> =
    runCatching {
        MetadataJson.decodeFromString(TitlesSerializer, json.orEmpty().ifBlank { "{}" })
    }.getOrDefault(emptyMap())

private fun encodeChapterTitles(titles: Map<Int, String>): String =
    MetadataJson.encodeToString(TitlesSerializer, titles)

private const val StatusComplete = "COMPLETE"
private val TitlesSerializer = MapSerializer(Int.serializer(), String.serializer())
private val MetadataJson = Json { ignoreUnknownKeys = true }
