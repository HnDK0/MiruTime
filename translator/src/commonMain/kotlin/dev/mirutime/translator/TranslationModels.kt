package dev.mirutime.translator

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Canonical BCP-47 language tag. */
@JvmInline
value class LanguageCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): LanguageCode {
            val cleaned = raw.trim().replace('_', '-')
            require(cleaned.isNotEmpty()) { "language code is blank" }
            require(!cleaned.equals("auto", ignoreCase = true)) {
                "auto must be represented as null source"
            }

            val parts = cleaned.split('-')
            val language = parts.first().lowercase()
            val normalized = buildList {
                add(language)
                for (part in parts.drop(1)) {
                    when (part.length) {
                        4 -> add(part.lowercase().replaceFirstChar { it.uppercase() })
                        2, 3 -> add(part.uppercase())
                        else -> add(part.lowercase())
                    }
                }
            }
            return LanguageCode(normalized.joinToString("-"))
        }
    }
}

enum class ProviderId {
    GOOGLE_PA,
    GOOGLE_FREE,
    GEMINI,
    OPENAI,
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    DEEPL,
    YANDEX_CLOUD,

    /**
     * Unofficial Yandex Translate endpoint (`srv=android`). Experimental and opt-in only:
     * no SLA, and the endpoint may break or block requests at any time.
     */
    YANDEX_FREE,

    /**
     * Unofficial Yandex browser page-translation endpoint (`srv=yabrowser`). Experimental
     * and opt-in only: no SLA, and the endpoint may break or block requests at any time.
     */
    YANDEX_PA;

    companion object {
        fun from(raw: String): ProviderId? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

enum class TranslationScope {
    STANDARD,
    FULL,
}

data class PromptSpec(
    val text: String,
    val version: String = "",
)

data class TranslationRevision(
    val sourceHash: String = "",
    val pipelineVersion: String = "1",
)

data class TranslationUnit(
    val id: String,
    val text: String,
)

data class BatchTranslationRequest(
    val bookId: Long,
    val chapterKey: String,
    val units: List<TranslationUnit>,
    val source: LanguageCode?,
    val target: LanguageCode,
    val provider: ProviderId,
    val prompt: PromptSpec? = null,
    val scope: TranslationScope = TranslationScope.STANDARD,
    val revision: TranslationRevision = TranslationRevision(),
)

data class BatchTranslationResult(
    val translations: Map<String, String>,
    val provider: ProviderId,
    val model: String?,
    val promptHash: String,
    val detectedSource: LanguageCode?,
)

/**
 * Metadata batch (book title, synopsis, chapter titles). Never spends LLM or
 * paid providers: an explicit free [provider] is honored, anything else walks
 * the free chain (PA first).
 */
data class MetadataTranslationRequest(
    val bookId: Long,
    val key: String,
    val units: List<TranslationUnit>,
    val source: LanguageCode?,
    val target: LanguageCode,
    val provider: ProviderId,
    val prompt: PromptSpec? = null,
)

data class TitleTranslationRequest(
    val bookId: Long,
    val title: String,
    val source: LanguageCode?,
    val target: LanguageCode,
    val provider: ProviderId,
    val prompt: PromptSpec? = null,
)

data class TranslationProviderConfig(
    val provider: ProviderId,
    val apiKeys: List<String> = emptyList(),
    val model: String? = null,
    val baseUrl: String? = null,
    val maxOutputTokens: Int? = null,
    val timeout: Duration = 180.seconds,
    val maxAttempts: Int = 6,
    /**
     * Character budget for one request. `null` or a non-positive value means auto:
     * LLM providers derive it from [maxOutputTokens], other providers use their hard
     * endpoint limit.
     */
    val maxCharsPerRequest: Int? = null,
    val folderId: String? = null,
    val useJsonSchema: Boolean = false,
    /**
     * Maximum number of chunks/batches translated concurrently. The app configures
     * this per provider because every operator has its own rate limits. Defaults:
     * Google Free, Google PA and Yandex PA — 8; everything else, including all
     * AI providers — 1 (conservative; override per operator in app settings).
     */
    val chunkConcurrency: Int = when (provider) {
        ProviderId.GOOGLE_FREE, ProviderId.GOOGLE_PA, ProviderId.YANDEX_PA -> 8
        else -> 1
    },
) {
    override fun toString(): String =
        "TranslationProviderConfig(provider=$provider, " +
            "apiKeys=${apiKeys.map { "***" }}, model=$model, baseUrl=$baseUrl, " +
            "maxOutputTokens=$maxOutputTokens, timeout=$timeout, maxAttempts=$maxAttempts, " +
            "maxCharsPerRequest=$maxCharsPerRequest, folderId=$folderId, " +
            "useJsonSchema=$useJsonSchema, chunkConcurrency=$chunkConcurrency)"
}
