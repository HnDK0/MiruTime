package dev.mirutime.app.features.translation.domain

import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.BatchTranslationRequest
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.MetadataTranslationRequest
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationManager
import dev.mirutime.translator.TranslationRevision
import dev.mirutime.translator.TranslationScope
import dev.mirutime.translator.TranslationUnit

/** One chapter translation job, independent of the translator library types. */
data class TranslationRequest(
    val bookId: Long,
    val bookUrl: String,
    val chapterUrl: String,
    val units: List<TranslationUnit>,
    val source: LanguageCode?,
    val target: LanguageCode,
    val provider: ProviderId,
    val prompt: PromptSpec?,
    val revision: TranslationRevision,
    // FULL also translates metadata; the character pipeline stays STANDARD.
    val scope: TranslationScope = TranslationScope.STANDARD,
)

data class ChapterTranslationResult(
    val translations: Map<Int, String>,
    val provider: ProviderId,
    val model: String?,
)

/** One metadata batch (title/synopsis/chapter titles) of a book. */
data class MetadataRequest(
    val bookId: Long,
    val key: String,
    val units: List<TranslationUnit>,
    val source: LanguageCode?,
    val target: LanguageCode,
    val provider: ProviderId,
    val prompt: PromptSpec?,
)

data class MetadataTranslationResult(
    val translations: Map<Int, String>,
    val provider: ProviderId,
    val model: String?,
)

interface TranslationEngine {
    suspend fun translateChapter(request: TranslationRequest): Outcome<ChapterTranslationResult>

    /** Metadata path: the library guarantees free-only providers here. */
    suspend fun translateMetadata(request: MetadataRequest): Outcome<MetadataTranslationResult>
}

/** Adapts [TranslationManager] to the reader's block-indexed contract. */
class ManagerTranslationEngine(
    private val managerProvider: () -> TranslationManager,
) : TranslationEngine {
    private val manager: TranslationManager get() = managerProvider()

    override suspend fun translateChapter(
        request: TranslationRequest,
    ): Outcome<ChapterTranslationResult> =
        when (
            val result = manager.translateBatch(
                BatchTranslationRequest(
                    bookId = request.bookId,
                    chapterKey = request.chapterUrl,
                    units = request.units,
                    source = request.source,
                    target = request.target,
                    provider = request.provider,
                    prompt = request.prompt,
                    scope = request.scope,
                    revision = request.revision,
                ),
            )
        ) {
            is Outcome.Ok -> Outcome.Ok(
                ChapterTranslationResult(
                    translations = result.value.translations.toIndexTranslations(),
                    provider = result.value.provider,
                    model = result.value.model,
                ),
            )
            is Outcome.Err -> result
        }

    override suspend fun translateMetadata(
        request: MetadataRequest,
    ): Outcome<MetadataTranslationResult> =
        when (
            val result = manager.translateMetadata(
                MetadataTranslationRequest(
                    bookId = request.bookId,
                    key = request.key,
                    units = request.units,
                    source = request.source,
                    target = request.target,
                    provider = request.provider,
                    prompt = request.prompt,
                ),
            )
        ) {
            is Outcome.Ok -> Outcome.Ok(
                MetadataTranslationResult(
                    translations = result.value.translations.toIndexTranslations(),
                    provider = result.value.provider,
                    model = result.value.model,
                ),
            )
            is Outcome.Err -> result
        }
}

private fun Map<String, String>.toIndexTranslations(): Map<Int, String> =
    mapNotNull { (id, text) -> id.toIntOrNull()?.let { index -> index to text } }.toMap()
