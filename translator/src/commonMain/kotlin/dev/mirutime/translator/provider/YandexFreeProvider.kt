package dev.mirutime.translator.provider

import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationHttpClient
import dev.mirutime.translator.TranslationProviderConfig
import dev.mirutime.translator.internal.KeyRotator
import dev.mirutime.translator.internal.KeyedHttpExecutor
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.internal.ProviderResult
import dev.mirutime.translator.internal.TranslationProvider
import dev.mirutime.translator.internal.cappedMaxChars
import dev.mirutime.translator.internal.chunkUnitGroups
import dev.mirutime.translator.internal.formUrlEncode
import dev.mirutime.translator.internal.parseYandexTrTexts
import dev.mirutime.translator.internal.randomHex
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.urlWithQuery
import dev.mirutime.translator.internal.withProviderTimeout
import dev.mirutime.translator.internal.yandexTrStatusError
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Unofficial Yandex mobile-app translation endpoint. Experimental: no SLA and the
 * endpoint may break or block requests at any time.
 */
internal class YandexFreeProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.YANDEX_FREE

    // baseUrl override is used as the full endpoint (handy for proxies and tests).
    private val endpoint = config.baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_ENDPOINT
    private val timeout = config.timeout
    private val maxChars = cappedMaxChars(config.maxCharsPerRequest, MAX_CHARS)
    private val keys = KeyRotator(listOf("public"), timeSource = timeSource)
    // Keyless endpoint: rate limiting must never turn the synthetic key into a cooldown lock.
    private val executor = KeyedHttpExecutor(
        keys = keys,
        provider = id,
        maxAttempts = config.maxAttempts,
        keyCooldown = false,
        mapFailure = { status, body -> yandexTrStatusError(id, status, body, maxChars) },
    )

    private val counterMutex = Mutex()
    private var counter = 0

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val plan = splitToBudget(request.units, maxChars, id)
        val translations = linkedMapOf<String, String>()

        for (group in chunkUnitGroups(plan.units, maxChars)) {
            val requestId = nextRequestId()
            val texts = executor.execute(
                request = { _ ->
                    withProviderTimeout(id, timeout) {
                        http.post(
                            url = urlWithQuery(
                                endpoint,
                                listOf(
                                    "srv" to "android",
                                    "id" to requestId,
                                    "format" to "text",
                                    "lang" to lang(request.source, request.target),
                                ),
                            ),
                            body = formUrlEncode(group.map { "text" to it.text }),
                            contentType = ContentType.Application.FormUrlEncoded.toString(),
                        )
                    }
                },
                parse = { response ->
                    parseYandexTrTexts(id, response.bodyAsText(), group.size, maxChars)
                },
            )
            group.forEachIndexed { index, unit -> translations[unit.id] = texts[index] }
        }

        return ProviderResult(plan.restore(translations), model = null)
    }

    private suspend fun nextRequestId(): String {
        val requestCounter = counterMutex.withLock { ++counter }
        return "${randomHex(32)}-$requestCounter-0"
    }

    private fun lang(source: LanguageCode?, target: LanguageCode): String {
        val targetCode = primary(target)
        return source?.let { "${primary(it)}-$targetCode" } ?: targetCode
    }

    private fun primary(code: LanguageCode): String =
        code.value.substringBefore('-').lowercase()

    private companion object {
        const val DEFAULT_ENDPOINT = "https://translate.yandex.net/api/v1/tr.json/translate"
        const val MAX_CHARS = 10_000
    }
}
