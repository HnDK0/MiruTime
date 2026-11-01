package dev.mirutime.translator.provider

import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import dev.mirutime.translator.TranslationHttpClient
import dev.mirutime.translator.TranslationProviderConfig
import dev.mirutime.translator.internal.KeyRotator
import dev.mirutime.translator.internal.KeyedHttpExecutor
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.internal.ProviderResult
import dev.mirutime.translator.internal.TextChunk
import dev.mirutime.translator.internal.TranslationProvider
import dev.mirutime.translator.internal.applyChunkTranslations
import dev.mirutime.translator.internal.boundedMap
import dev.mirutime.translator.internal.cappedMaxChars
import dev.mirutime.translator.internal.chunkUnits
import dev.mirutime.translator.internal.formUrlEncode
import dev.mirutime.translator.internal.isQuotaBody
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.urlWithQuery
import dev.mirutime.translator.internal.validateChunkLines
import dev.mirutime.translator.internal.withProviderTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class GoogleFreeProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.GOOGLE_FREE

    private val endpoint = config.baseUrl?.trimEnd('/')
        ?: "https://translate.googleapis.com/translate_a/single"
    private val timeout = config.timeout
    private val maxChars = cappedMaxChars(config.maxCharsPerRequest, MAX_CHARS)
    private val concurrency = config.chunkConcurrency.coerceIn(1, 32)
    private val keys = KeyRotator(listOf("public"), timeSource = timeSource)
    // Keyless endpoint: rate limiting must never turn the synthetic key into a cooldown lock.
    private val executor = KeyedHttpExecutor(keys, id, config.maxAttempts, keyCooldown = false)

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val plan = splitToBudget(request.units, maxChars, id)
        val chunks = chunkUnits(plan.units, maxChars, "\n")
        // The free endpoint tolerates parallel calls, but the sequential path keeps
        // its 1s spacing because that is what avoids rate limiting for single-run use.
        val translated = if (concurrency == 1) {
            chunks.mapIndexed { index, chunk ->
                if (index > 0) delay(1_000)
                translateChunk(chunk, request)
            }
        } else {
            boundedMap(chunks, concurrency) { chunk -> translateChunk(chunk, request) }
        }

        val merged = applyChunkTranslations(
            units = plan.units,
            chunks = chunks,
            translatedChunks = translated,
            provider = id,
            separatorRegex = NEWLINE,
        )
        return ProviderResult(plan.restore(merged), model = null)
    }

    private suspend fun translateChunk(
        chunk: TextChunk,
        request: ProviderRequest,
    ): String = executor.execute(
        request = { _ ->
            withProviderTimeout(id, timeout) {
                if (chunk.text.length <= 500) {
                    getChunk(chunk.text, request.source, request.target)
                } else {
                    postChunk(chunk.text, request.source, request.target)
                }
            }
        },
        parse = { response ->
            val raw = parseBody(response.bodyAsText())
            validateChunkLines(raw, chunk, id, NEWLINE)
            raw
        },
    )

    override suspend fun detect(text: String): LanguageCode? {
        if (text.isBlank()) return null

        val response = try {
            withProviderTimeout(id, timeout) {
                http.get(
                    urlWithQuery(
                        endpoint,
                        listOf(
                            "client" to "gtx",
                            "sl" to "auto",
                            "tl" to "en",
                            "dt" to "t",
                            "q" to text.take(200),
                        ),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationFailure) {
            throw e
        } catch (e: Throwable) {
            throw TranslationFailure(TranslationError.Network(id, e.message))
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw TranslationFailure(detectFailure(response, body))
        }

        val value = runCatching {
            Json.parseToJsonElement(body).jsonArray.getOrNull(2)?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw TranslationFailure(
            TranslationError.InvalidResponse(id, "language detection failed"),
        )

        return try {
            LanguageCode.parse(value)
        } catch (_: IllegalArgumentException) {
            throw TranslationFailure(
                TranslationError.InvalidResponse(id, "invalid detected language: $value"),
            )
        }
    }

    private fun detectFailure(response: HttpResponse, body: String): TranslationError {
        val message = body.take(300)
        return when (response.status.value) {
            429 -> if (isQuotaBody(message)) {
                TranslationError.Quota(id, message)
            } else {
                TranslationError.RateLimited(
                    id,
                    response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds,
                    message,
                )
            }

            401, 403 -> if (isQuotaBody(message)) {
                TranslationError.Quota(id, message)
            } else {
                TranslationError.Auth(id, message)
            }

            in 500..599 -> TranslationError.Server(id, response.status.value, message)
            else -> TranslationError.InvalidResponse(
                id,
                "HTTP ${response.status.value}: $message",
            )
        }
    }

    private suspend fun getChunk(
        text: String,
        source: LanguageCode?,
        target: LanguageCode,
    ): HttpResponse = http.get(
        urlWithQuery(
            endpoint,
            listOf(
                "client" to "gtx",
                "sl" to (source?.value ?: "auto"),
                "tl" to target.value,
                "dt" to "t",
                "q" to text,
            ),
        ),
    )

    private suspend fun postChunk(
        text: String,
        source: LanguageCode?,
        target: LanguageCode,
    ): HttpResponse = http.post(
        url = endpoint,
        body = formUrlEncode(
            listOf(
                "client" to "gtx",
                "sl" to (source?.value ?: "auto"),
                "tl" to target.value,
                "dt" to "t",
                "q" to text,
            ),
        ),
        contentType = ContentType.Application.FormUrlEncoded.toString(),
    )

    private fun parseBody(body: String): String {
        val lines = runCatching {
            Json.parseToJsonElement(body).jsonArray.getOrNull(0)?.jsonArray
        }.getOrNull() ?: throw TranslationFailure(
            TranslationError.InvalidResponse(id, "unexpected Google Free response"),
        )

        val translated = buildString {
            for (line in lines) {
                append(line.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }

        return translated.takeIf { it.isNotBlank() }
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "empty Google Free translation"),
            )
    }

    private companion object {
        const val MAX_CHARS = 8_000
        val NEWLINE = Regex("\\r?\\n")
    }
}
