package dev.mirutime.translator.provider

import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationHttpClient
import dev.mirutime.translator.TranslationProviderConfig
import dev.mirutime.translator.TranslationUnit
import dev.mirutime.translator.internal.KeyRotator
import dev.mirutime.translator.internal.KeyedHttpExecutor
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.internal.ProviderResult
import dev.mirutime.translator.internal.TranslationProvider
import dev.mirutime.translator.internal.boundedMap
import dev.mirutime.translator.internal.cappedMaxChars
import dev.mirutime.translator.internal.chunkUnitGroups
import dev.mirutime.translator.internal.parseYandexTrTexts
import dev.mirutime.translator.internal.randomHex
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.urlWithQuery
import dev.mirutime.translator.internal.withProviderTimeout
import dev.mirutime.translator.internal.yandexTrStatusError
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * Unofficial Yandex browser page-translation endpoint. Experimental: no SLA and the
 * endpoint may break or block requests at any time.
 */
@OptIn(ExperimentalTime::class)
internal class YandexPageProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.YANDEX_PA

    // baseUrl override is used as the full endpoint (handy for proxies and tests).
    private val endpoint = config.baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_ENDPOINT
    private val timeout = config.timeout
    private val maxChars = cappedMaxChars(config.maxCharsPerRequest, MAX_CHARS)
    private val concurrency = config.chunkConcurrency.coerceIn(1, 16)
    private val keys = KeyRotator(listOf("public"), timeSource = timeSource)
    // Keyless endpoint: rate limiting must never turn the synthetic key into a cooldown lock.
    private val executor = KeyedHttpExecutor(
        keys = keys,
        provider = id,
        maxAttempts = config.maxAttempts,
        keyCooldown = false,
        mapFailure = { status, body -> yandexTrStatusError(id, status, body, REQUEST_LIMIT) },
    )

    private val sessionId = newSessionId()
    private val counterMutex = Mutex()
    private var counter = 0

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val plan = splitToBudget(request.units, maxChars, id)
        val groups = chunkUnitGroups(plan.units, maxChars)
        val translatedGroups = boundedMap(groups, concurrency) { group ->
            translateGroup(group, request)
        }
        val translations = linkedMapOf<String, String>()

        groups.forEachIndexed { index, group ->
            val texts = translatedGroups[index]
            group.forEachIndexed { unitIndex, unit ->
                translations[unit.id] = texts[unitIndex]
            }
        }

        return ProviderResult(plan.restore(translations), model = null)
    }

    private suspend fun translateGroup(
        group: List<TranslationUnit>,
        request: ProviderRequest,
    ): List<String> {
        val requestId = nextRequestId()
        return executor.execute(
            request = { _ ->
                withProviderTimeout(id, timeout) {
                    http.get(
                        urlWithQuery(
                            endpoint,
                            buildList {
                                add("srv" to "yabrowser")
                                add("id" to requestId)
                                add("lang" to lang(request.source, request.target))
                                add("format" to "html")
                                add("options" to "2")
                                for (unit in group) add("text" to unit.text)
                            },
                        ),
                    )
                }
            },
            parse = { response ->
                parseYandexTrTexts(id, response.bodyAsText(), group.size, REQUEST_LIMIT)
            },
        )
    }

    private suspend fun nextRequestId(): String {
        val requestCounter = counterMutex.withLock { ++counter }
        return "$sessionId-$requestCounter-0"
    }

    private fun newSessionId(): String =
        (Clock.System.now().toEpochMilliseconds().toString(16) + randomHex(16)).take(16)

    private fun lang(source: LanguageCode?, target: LanguageCode): String {
        val targetCode = primary(target)
        return source?.let { "${primary(it)}-$targetCode" } ?: targetCode
    }

    private fun primary(code: LanguageCode): String =
        code.value.substringBefore('-').lowercase()

    private companion object {
        const val DEFAULT_ENDPOINT =
            "https://browser.translate.yandex.net/api/v1/tr.json/translate"
        // Safety margin under the endpoint's hard 2048-character request limit.
        const val MAX_CHARS = 2_000
        const val REQUEST_LIMIT = 2_048
    }
}
