package dev.mirutime.app.features.translation.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.features.book.data.chapterSourceLogLine
import dev.mirutime.core.reader.ChapterSource
import dev.mirutime.app.features.translation.domain.ChapterTranslationResult
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.TranslationBlockEntity
import java.security.MessageDigest

/** L2 cache for chapter translations; L1 stays in TranslationController. */
interface ChapterTranslationCache {
    suspend fun load(request: TranslationRequest): Map<Int, String>?
    suspend fun save(request: TranslationRequest, result: ChapterTranslationResult)
    suspend fun markFailed(request: TranslationRequest)
}

class RoomTranslationCache(
    private val library: LibraryRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ChapterTranslationCache {
    override suspend fun load(request: TranslationRequest): Map<Int, String>? {
        val started = System.nanoTime()
        val chapterId = resolveChapterId(request) ?: return null
        val row = library.chapterTranslation(
            chapterId = chapterId,
            source = request.source?.value.orEmpty(),
            target = request.target.value,
            provider = request.provider.name,
            promptHash = promptHash(request),
            sourceHash = request.revision.sourceHash,
            pipelineVersion = request.revision.pipelineVersion,
        ) ?: return null
        if (!row.status.equals(StatusComplete, ignoreCase = true)) return null
        val blocks = library.translationBlocks(row.translationId)
        if (blocks.isEmpty()) return null
        val result = blocks.associate { it.blockIndex to it.translatedText }
        MiruLog.d(
            "chapter-source",
            chapterSourceLogLine(
                source = ChapterSource.TRANSLATION_CACHE,
                chapterId = chapterId,
                chapterIndex = null,
                bytes = result.values.sumOf { it.toByteArray().size },
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                cacheHit = true,
            ),
        )
        return result
    }

    override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) {
        val chapterId = resolveChapterId(request) ?: return
        val translationId = library.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = chapterId,
                source = request.source?.value.orEmpty(),
                target = request.target.value,
                provider = result.provider.name,
                model = result.model,
                promptHash = promptHash(request),
                sourceHash = request.revision.sourceHash,
                pipelineVersion = request.revision.pipelineVersion,
                status = StatusComplete,
                updatedAt = now(),
            ),
        )
        library.deleteTranslationBlocks(translationId)
        val sourceByIndex = request.units.mapNotNull { unit ->
            unit.id.toIntOrNull()?.let { it to unit.text }
        }.toMap()
        for ((index, text) in result.translations) {
            val source = sourceByIndex[index] ?: continue
            library.saveTranslationBlock(
                TranslationBlockEntity(
                    translationId = translationId,
                    blockIndex = index,
                    sourceTextHash = sha256(source),
                    translatedText = text,
                ),
            )
        }
    }

    override suspend fun markFailed(request: TranslationRequest) {
        val chapterId = resolveChapterId(request) ?: return
        library.saveChapterTranslation(
            ChapterTranslationEntity(
                chapterId = chapterId,
                source = request.source?.value.orEmpty(),
                target = request.target.value,
                provider = request.provider.name,
                model = null,
                promptHash = promptHash(request),
                sourceHash = request.revision.sourceHash,
                pipelineVersion = request.revision.pipelineVersion,
                status = StatusFailed,
                updatedAt = now(),
            ),
        )
    }

    private suspend fun resolveChapterId(request: TranslationRequest): Long? {
        // Unique-URL resolution only: with two installs sharing a URL a
        // URL-only lookup could read another work's cached chapter.
        val bookId = library.bookIdByUrlUnique(request.bookUrl) ?: return null
        return library.chapterByUrl(bookId, request.chapterUrl)?.chapterId
    }

    private fun promptHash(request: TranslationRequest): String =
        ChapterTranslationPlanner.promptHash(request.prompt)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val StatusComplete = "COMPLETE"
        const val StatusFailed = "FAILED"
    }
}
