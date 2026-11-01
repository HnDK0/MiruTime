package dev.mirutime.translator

import dev.mirutime.core.api.Outcome

interface TranslationManager {
    suspend fun translateBatch(request: BatchTranslationRequest): Outcome<BatchTranslationResult>

    /**
     * Metadata only (title/description/chapter titles). The implementation
     * guarantees free providers: LLM and paid selections fall back through the
     * free chain instead of spending tokens/quota.
     */
    suspend fun translateMetadata(request: MetadataTranslationRequest): Outcome<BatchTranslationResult>

    suspend fun translateTitle(request: TitleTranslationRequest): Outcome<String>

    suspend fun detectLanguage(text: String): Outcome<LanguageCode>
}
