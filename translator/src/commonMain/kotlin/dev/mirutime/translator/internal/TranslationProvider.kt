package dev.mirutime.translator.internal

import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationUnit

internal data class ProviderRequest(
    val units: List<TranslationUnit>,
    val source: LanguageCode?,
    val target: LanguageCode,
    val prompt: PromptSpec?,
)

internal data class ProviderResult(
    val translations: Map<String, String>,
    val model: String?,
    val detectedSource: LanguageCode? = null,
)

internal interface TranslationProvider {
    val id: ProviderId

    suspend fun translate(request: ProviderRequest): ProviderResult

    suspend fun detect(text: String): LanguageCode? = null
}
