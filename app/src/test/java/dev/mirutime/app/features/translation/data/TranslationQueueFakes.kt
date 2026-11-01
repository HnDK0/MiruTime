package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.downloads.data.DownloadFileStore
import dev.mirutime.app.features.downloads.data.StoredText
import dev.mirutime.app.features.translation.domain.ChapterTranslationResult
import dev.mirutime.app.features.translation.domain.MetadataRequest
import dev.mirutime.app.features.translation.domain.MetadataTranslationResult
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.api.Outcome
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.ChapterTranslationQueueRow
import dev.mirutime.data.library.TranslationBlockEntity
import dev.mirutime.translator.PromptSpec
import java.security.MessageDigest

internal fun translationTestBook(
    bookId: Long = 1,
    url: String = "https://example.test/book/1",
) = BookEntity(
    bookId = bookId,
    installId = "test",
    url = url,
    title = "Test book",
    author = "Author",
    coverUrl = null,
    contentType = "Novel",
    rating = 0.0,
    shelfId = "reading",
    totalChapters = 3,
    inLibrary = true,
    addedAt = 1,
    updatedAt = 1,
    lastReadAt = null,
    lastChapterId = null,
    lastChapterIndex = null,
    chapterListHash = null,
    lastUpdateAt = null,
    genresJson = "[]",
)

internal fun translationTestChapter(
    chapterId: Long,
    idx: Int,
    bookId: Long = 1,
) = ChapterEntity(
    chapterId = chapterId,
    bookId = bookId,
    idx = idx,
    url = "https://example.test/book/1/$idx",
    title = "Chapter $idx",
    updatedAt = 1,
)

internal class FakeTranslationQueueStore(
    var bookRow: BookEntity? = translationTestBook(),
    chapters: List<ChapterEntity> = (1..3).map { translationTestChapter(it.toLong(), it) },
) : TranslationQueueStore {
    val chapterRows = chapters.toMutableList()
    val bodies = HashMap<Long, ChapterBodyEntity>()
    val pageChapterIds = HashSet<Long>()
    val downloadStates = HashMap<Long, String>()
    val rows = mutableListOf<ChapterTranslationEntity>()
    val blocks = HashMap<Long, MutableList<TranslationBlockEntity>>()
    private var nextId = 1L

    fun addRow(
        chapterId: Long,
        status: String,
        source: String = "en",
        target: String = "ru",
        provider: String = "GOOGLE_FREE",
        promptHash: String = ChapterTranslationPlanner.promptHash(prompt()),
        sourceHash: String = ChapterTranslationPlanner.PENDING_SOURCE_HASH,
        pipelineVersion: String = ChapterTranslationPlanner.PIPELINE_VERSION,
    ): ChapterTranslationEntity {
        val row = ChapterTranslationEntity(
            translationId = nextId++,
            chapterId = chapterId,
            source = source,
            target = target,
            provider = provider,
            model = null,
            promptHash = promptHash,
            sourceHash = sourceHash,
            pipelineVersion = pipelineVersion,
            status = status,
            updatedAt = nextId,
        )
        rows += row
        return row
    }

    override suspend fun book(bookId: Long): BookEntity? = bookRow?.takeIf { it.bookId == bookId }

    override suspend fun chapter(chapterId: Long): ChapterEntity? =
        chapterRows.firstOrNull { it.chapterId == chapterId }

    override suspend fun body(chapterId: Long): ChapterBodyEntity? = bodies[chapterId]

    override suspend fun hasPages(chapterId: Long): Boolean = chapterId in pageChapterIds

    override suspend fun downloadState(chapterId: Long): String? = downloadStates[chapterId]

    override suspend fun queueRows(bookId: Long): List<ChapterTranslationQueueRow> =
        rows.asSequence()
            .mapNotNull { row ->
                val chapter = chapterRows.firstOrNull { it.chapterId == row.chapterId }
                    ?: return@mapNotNull null
                if (chapter.bookId != bookId) return@mapNotNull null
                ChapterTranslationQueueRow(
                    translationId = row.translationId,
                    chapterId = row.chapterId,
                    idx = chapter.idx,
                    url = chapter.url,
                    bookId = chapter.bookId,
                    source = row.source,
                    target = row.target,
                    provider = row.provider,
                    promptHash = row.promptHash,
                    sourceHash = row.sourceHash,
                    pipelineVersion = row.pipelineVersion,
                    status = row.status,
                    updatedAt = row.updatedAt,
                )
            }
            .sortedByDescending { it.updatedAt }
            .toList()

    override suspend fun latest(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity? = rows
        .filter {
            it.chapterId == chapterId &&
                it.source == source &&
                it.target == target &&
                it.provider == provider &&
                it.promptHash == promptHash &&
                it.pipelineVersion == pipelineVersion
        }
        .maxByOrNull { it.updatedAt }

    override suspend fun save(row: ChapterTranslationEntity): Long {
        val existing = rows.firstOrNull {
            it.chapterId == row.chapterId &&
                it.source == row.source &&
                it.target == row.target &&
                it.provider == row.provider &&
                it.promptHash == row.promptHash &&
                it.pipelineVersion == row.pipelineVersion
        }
        val saved = row.copy(translationId = existing?.translationId ?: row.translationId)
        if (saved.translationId == 0L) {
            val id = nextId++
            rows += saved.copy(translationId = id)
            return id
        }
        val index = rows.indexOfFirst { it.translationId == saved.translationId }
        if (index >= 0) rows[index] = saved else rows += saved
        return saved.translationId
    }

    override suspend fun blocks(translationId: Long): List<TranslationBlockEntity> =
        blocks[translationId].orEmpty()

    override suspend fun delete(translationId: Long) {
        rows.removeAll { it.translationId == translationId }
        blocks.remove(translationId)
    }

    override suspend fun exact(
        chapterId: Long,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
        sourceHash: String,
        pipelineVersion: String,
    ): ChapterTranslationEntity? = rows.firstOrNull {
        it.chapterId == chapterId &&
            it.source == source &&
            it.target == target &&
            it.provider == provider &&
            it.promptHash == promptHash &&
            it.sourceHash == sourceHash &&
            it.pipelineVersion == pipelineVersion
    }
}

/** Writes translations back through the same identity the production cache uses. */
internal class FakeTranslationCache(
    private val store: FakeTranslationQueueStore,
) : ChapterTranslationCache {
    var saves = 0
    var failures = 0

    override suspend fun load(request: TranslationRequest): Map<Int, String>? = null

    override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) {
        saves++
        val existing = store.exact(
            chapterId = chapterId(request),
            source = request.source?.value.orEmpty(),
            target = request.target.value,
            provider = result.provider.name,
            promptHash = ChapterTranslationPlanner.promptHash(request.prompt),
            sourceHash = request.revision.sourceHash,
            pipelineVersion = request.revision.pipelineVersion,
        )
        val id = store.save(
            (existing ?: ChapterTranslationEntity(
                chapterId = chapterId(request),
                source = request.source?.value.orEmpty(),
                target = request.target.value,
                provider = result.provider.name,
                model = result.model,
                promptHash = ChapterTranslationPlanner.promptHash(request.prompt),
                sourceHash = request.revision.sourceHash,
                pipelineVersion = request.revision.pipelineVersion,
                status = DownloadTranslationQueue.STATUS_COMPLETE,
                updatedAt = 1,
            )).copy(
                provider = result.provider.name,
                model = result.model,
                status = DownloadTranslationQueue.STATUS_COMPLETE,
                updatedAt = 2,
            ),
        )
        store.blocks[id] = result.translations.map { (index, text) ->
            TranslationBlockEntity(
                translationId = id,
                blockIndex = index,
                sourceTextHash = "hash-$index",
                translatedText = text,
            )
        }.toMutableList()
    }

    override suspend fun markFailed(request: TranslationRequest) {
        failures++
        store.save(
            ChapterTranslationEntity(
                chapterId = chapterId(request),
                source = request.source?.value.orEmpty(),
                target = request.target.value,
                provider = request.provider.name,
                model = null,
                promptHash = ChapterTranslationPlanner.promptHash(request.prompt),
                sourceHash = request.revision.sourceHash,
                pipelineVersion = request.revision.pipelineVersion,
                status = DownloadTranslationQueue.STATUS_FAILED,
                updatedAt = 3,
            ),
        )
    }

    private fun chapterId(request: TranslationRequest): Long =
        store.chapterRows.first { it.url == request.chapterUrl }.chapterId
}

internal class FakeTranslationEngine(
    var outcome: (TranslationRequest) -> Outcome<ChapterTranslationResult> = { request ->
        Outcome.Ok(
            ChapterTranslationResult(
                translations = request.units.associate {
                    it.id.toInt() to "translated:${it.text}"
                },
                provider = request.provider,
                model = "fake",
            ),
        )
    },
) : TranslationEngine {
    val requests = mutableListOf<TranslationRequest>()

    override suspend fun translateChapter(request: TranslationRequest): Outcome<ChapterTranslationResult> {
        requests += request
        return outcome(request)
    }

    override suspend fun translateMetadata(
        request: MetadataRequest,
    ): Outcome<MetadataTranslationResult> = Outcome.Ok(
        MetadataTranslationResult(emptyMap(), request.provider, null),
    )
}

internal class FakeFileStore(
    private val files: MutableMap<String, String> = HashMap(),
) : DownloadFileStore {
    private var next = 1

    fun put(html: String): String {
        val path = "memory-${next++}.html"
        files[path] = html
        return path
    }

    override fun writeText(bookId: Long, chapterId: Long, html: String): StoredText =
        StoredText(put(html), html.length.toLong(), sha(html))

    override fun readText(path: String): String? = files[path]

    override fun writePage(
        bookId: Long,
        chapterId: Long,
        index: Int,
        url: String,
        bytes: ByteArray,
    ): String = "page-$index"

    override fun textExists(path: String): Boolean = path in files

    override fun pagesExist(pagePathsJson: String): Boolean = true

    override fun pagePaths(pagePathsJson: String): List<String> = emptyList()

    override fun encodePagePaths(paths: List<String>): String = paths.joinToString(",")

    override fun deleteChapterFiles(bookId: Long, chapterId: Long) = Unit

    override fun deleteBookFiles(bookId: Long) = Unit
}

internal fun prompt() = PromptSpec(text = "Translate faithfully", version = "1")

internal fun testChapterConfig() = ChapterTranslationConfig(
    mode = TranslationMode(
        source = "en",
        target = "ru",
        provider = "GOOGLE_FREE",
    ),
    prompt = prompt(),
)

internal fun testDocumentHtml() = "<html><body><h1>Title</h1><p>Hello</p><p>World</p></body></html>"

private fun sha(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
