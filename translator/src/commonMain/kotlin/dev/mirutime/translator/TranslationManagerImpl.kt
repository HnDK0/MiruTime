package dev.mirutime.translator

import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.internal.KtorTranslationHttpClient
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.internal.TranslationProvider
import dev.mirutime.translator.internal.renderPrompt
import dev.mirutime.translator.internal.stableHash
import dev.mirutime.translator.provider.AnthropicProvider
import dev.mirutime.translator.provider.DeepLProvider
import dev.mirutime.translator.provider.GeminiProvider
import dev.mirutime.translator.provider.GoogleFreeProvider
import dev.mirutime.translator.provider.GooglePaProvider
import dev.mirutime.translator.provider.OpenAiCompatibleProvider
import dev.mirutime.translator.provider.OpenAiProvider
import dev.mirutime.translator.provider.YandexCloudProvider
import dev.mirutime.translator.provider.YandexFreeProvider
import dev.mirutime.translator.provider.YandexPageProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

class TranslationManagerImpl(
    private val http: TranslationHttpClient,
    configs: List<TranslationProviderConfig>,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val detectProvider: ProviderId = ProviderId.GOOGLE_FREE,
) : TranslationManager {
    constructor(
        client: HttpClient,
        configs: List<TranslationProviderConfig>,
        timeSource: TimeSource = TimeSource.Monotonic,
        detectProvider: ProviderId = ProviderId.GOOGLE_FREE,
    ) : this(KtorTranslationHttpClient(client), configs, timeSource, detectProvider)

    private val providers: Map<ProviderId, TranslationProvider> = configs.associate { config ->
        config.provider to when (config.provider) {
            ProviderId.GOOGLE_PA -> GooglePaProvider(http, config, timeSource)
            ProviderId.GOOGLE_FREE -> GoogleFreeProvider(http, config, timeSource)
            ProviderId.GEMINI -> GeminiProvider(http, config, timeSource)
            ProviderId.OPENAI -> OpenAiProvider(http, config, timeSource)
            ProviderId.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(http, config, timeSource)
            ProviderId.ANTHROPIC -> AnthropicProvider(http, config, timeSource)
            ProviderId.DEEPL -> DeepLProvider(http, config, timeSource)
            ProviderId.YANDEX_CLOUD -> YandexCloudProvider(http, config, timeSource)
            ProviderId.YANDEX_FREE -> YandexFreeProvider(http, config, timeSource)
            ProviderId.YANDEX_PA -> YandexPageProvider(http, config, timeSource)
        }
    }

    override suspend fun translateBatch(
        request: BatchTranslationRequest,
    ): Outcome<BatchTranslationResult> = executeBatch(
        units = request.units,
        source = request.source,
        target = request.target,
        provider = request.provider,
        prompt = request.prompt,
    )

    override suspend fun translateMetadata(
        request: MetadataTranslationRequest,
    ): Outcome<BatchTranslationResult> {
        // Metadata never spends LLM or paid providers: a free selection is
        // honored, anything else walks the free chain. When everything fails
        // the last error is returned.
        val chain = if (request.provider in FREE_PROVIDERS) {
            listOf(request.provider)
        } else {
            FREE_PROVIDER_ORDER
        }
        var lastError: MiruError? = null
        for (providerId in chain) {
            when (
                val result = executeBatch(
                    units = request.units,
                    source = request.source,
                    target = request.target,
                    provider = providerId,
                    prompt = request.prompt,
                )
            ) {
                is Outcome.Ok -> return result
                is Outcome.Err -> lastError = result.error
            }
        }
        return Outcome.Err(
            lastError
                ?: TranslationError.InvalidRequest(request.provider, "no free metadata provider"),
        )
    }

    private suspend fun executeBatch(
        units: List<TranslationUnit>,
        source: LanguageCode?,
        target: LanguageCode,
        provider: ProviderId,
        prompt: PromptSpec?,
    ): Outcome<BatchTranslationResult> {
        if (units.isEmpty()) {
            return Outcome.Ok(
                BatchTranslationResult(
                    translations = emptyMap(),
                    provider = provider,
                    model = null,
                    promptHash = "",
                    detectedSource = null,
                ),
            )
        }

        val ids = units.map { it.id }
        if (ids.size != ids.toSet().size) {
            return Outcome.Err(TranslationError.InvalidRequest(null, "duplicate translation unit id"))
        }

        val blank = units
            .filter { it.text.isBlank() }
            .associate { it.id to it.text }
        val toTranslate = units.filter { it.text.isNotBlank() }

        var resolvedSource = source
        var detected: LanguageCode? = null
        if (toTranslate.isNotEmpty() && resolvedSource == null) {
            val detectSample = toTranslate.firstOrNull { unit -> unit.text.any(Char::isLetter) }
                ?: toTranslate.first()
            when (val result = detectLanguage(detectSample.text)) {
                is Outcome.Ok -> {
                    resolvedSource = result.value
                    detected = result.value
                }

                is Outcome.Err -> {
                    // Detection being unavailable is not fatal; the provider may auto-detect.
                    // InvalidRequest also covers a detect provider that is not configured.
                    if (
                        result.error !is TranslationError.InvalidResponse &&
                        result.error !is TranslationError.InvalidRequest
                    ) {
                        return Outcome.Err(result.error)
                    }
                }
            }
        }

        if (toTranslate.isEmpty()) {
            return Outcome.Ok(
                BatchTranslationResult(
                    translations = blank,
                    provider = provider,
                    model = null,
                    promptHash = "",
                    detectedSource = detected,
                ),
            )
        }

        val translator = providers[provider]
            ?: return Outcome.Err(
                TranslationError.InvalidRequest(provider, "provider is not configured"),
            )

        val result = try {
            translator.translate(
                ProviderRequest(
                    units = toTranslate,
                    source = resolvedSource,
                    target = target,
                    prompt = prompt,
                ),
            )
        } catch (e: TranslationFailure) {
            return Outcome.Err(e.error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Outcome.Err(TranslationError.Network(translator.id, e.message))
        }

        val expected = toTranslate.map { it.id }.toSet()
        if (result.translations.keys != expected) {
            return Outcome.Err(
                TranslationError.Truncated(translator.id, expected.size, result.translations.size),
            )
        }

        return Outcome.Ok(
            BatchTranslationResult(
                translations = blank + result.translations,
                provider = translator.id,
                model = result.model,
                promptHash = promptHash(translator.id, prompt, resolvedSource, target),
                detectedSource = detected,
            ),
        )
    }

    override suspend fun translateTitle(request: TitleTranslationRequest): Outcome<String> {
        if (request.title.isBlank()) {
            return Outcome.Err(
                TranslationError.InvalidRequest(request.provider, "title is blank"),
            )
        }

        // Titles are metadata: LLM and paid providers are never used, the free
        // chain runs PA first (same rule as translateMetadata).
        val chain = if (request.provider in FREE_PROVIDERS) {
            listOf(request.provider)
        } else {
            FREE_PROVIDER_ORDER
        }

        var lastError: MiruError? = null
        for (providerId in chain) {
            val provider = providers[providerId] ?: continue

            val result = try {
                provider.translate(
                    ProviderRequest(
                        units = listOf(TranslationUnit("title", request.title)),
                        source = request.source,
                        target = request.target,
                        prompt = request.prompt,
                    ),
                )
            } catch (e: TranslationFailure) {
                lastError = e.error
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = TranslationError.Network(provider.id, e.message)
                continue
            }

            val translated = result.translations["title"]
            if (!translated.isNullOrBlank() && translated != request.title) {
                return Outcome.Ok(translated)
            }
            lastError = TranslationError.InvalidResponse(
                provider.id,
                if (translated.isNullOrBlank()) {
                    "empty title translation"
                } else {
                    "title translation is identical to the original"
                },
            )
        }

        return Outcome.Err(
            lastError ?: TranslationError.InvalidRequest(request.provider, "no title provider configured"),
        )
    }

    override suspend fun detectLanguage(text: String): Outcome<LanguageCode> {
        val provider = providers[detectProvider]
            ?: return Outcome.Err(
                TranslationError.InvalidRequest(detectProvider, "detect provider is not configured"),
            )

        val detected = try {
            provider.detect(text)
        } catch (e: TranslationFailure) {
            return Outcome.Err(e.error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Outcome.Err(TranslationError.Network(provider.id, e.message))
        }

        return detected?.let { Outcome.Ok(it) }
            ?: Outcome.Err(
                TranslationError.InvalidResponse(provider.id, "language detection failed"),
            )
    }

    private fun promptHash(
        provider: ProviderId,
        prompt: PromptSpec?,
        source: LanguageCode?,
        target: LanguageCode,
    ): String = if (provider in LLM_PROVIDERS) {
        stableHash(renderPrompt(prompt, source, target))
    } else {
        ""
    }
}

private val LLM_PROVIDERS = setOf(
    ProviderId.GEMINI,
    ProviderId.OPENAI,
    ProviderId.OPENAI_COMPATIBLE,
    ProviderId.ANTHROPIC,
)

/** Free backends — the only ones metadata may use. */
private val FREE_PROVIDERS = setOf(
    ProviderId.GOOGLE_PA,
    ProviderId.GOOGLE_FREE,
    ProviderId.YANDEX_PA,
    ProviderId.YANDEX_FREE,
)

/** Fallback order when the selected provider is LLM or paid. */
private val FREE_PROVIDER_ORDER = listOf(
    ProviderId.GOOGLE_PA,
    ProviderId.GOOGLE_FREE,
    ProviderId.YANDEX_PA,
    ProviderId.YANDEX_FREE,
)
