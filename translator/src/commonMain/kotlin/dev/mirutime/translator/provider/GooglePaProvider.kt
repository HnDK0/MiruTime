package dev.mirutime.translator.provider

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
import dev.mirutime.translator.internal.applyChunkWithFallback
import dev.mirutime.translator.internal.boundedMap
import dev.mirutime.translator.internal.cappedMaxChars
import dev.mirutime.translator.internal.chunkUnits
import dev.mirutime.translator.internal.htmlUnescape
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.throttledTranslator
import dev.mirutime.translator.internal.validateChunkLines
import dev.mirutime.translator.internal.withProviderTimeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

// Obfuscated at rest only: a client-side key is still recoverable at runtime.
// It is not a secret; app-configured keys are tried first and this is the fallback.
private const val ENCODED_GOOGLE_PA_API_KEY =
    "0c20081407102c311219535a444c1c2526311c2c3c07333143007b5e285904221021002a657302"

private val GOOGLE_PA_KEY_MASK = byteArrayOf(
    0x4D, 0x69, 0x72, 0x75, 0x54, 0x69, 0x6D,
    0x65, 0x50, 0x41, 0x32, 0x30, 0x32, 0x36,
)

internal val DEFAULT_GOOGLE_PA_API_KEY: String = decodeGooglePaKey()

private fun decodeGooglePaKey(): String {
    val encoded = ByteArray(ENCODED_GOOGLE_PA_API_KEY.length / 2)
    for (index in encoded.indices) {
        val high = ENCODED_GOOGLE_PA_API_KEY[index * 2].digitToInt(16)
        val low = ENCODED_GOOGLE_PA_API_KEY[index * 2 + 1].digitToInt(16)
        encoded[index] = ((high shl 4) or low).toByte()
    }
    return ByteArray(encoded.size) { index ->
        (encoded[index].toInt() xor GOOGLE_PA_KEY_MASK[index % GOOGLE_PA_KEY_MASK.size].toInt()).toByte()
    }.decodeToString()
}

/**
 * Paragraph boundary of a translateHtml response. The endpoint keeps `<p>`
 * blocks but drops `<br>` and raw newlines (verified for CJK), so blocks are
 * the only separator that maps one translated line back to one unit.
 */
internal val PA_PARAGRAPH_END = Regex("</p\\s*>", RegexOption.IGNORE_CASE)

private val LEADING_PARAGRAPH_OPEN = Regex("^\\s*<p\\b[^>]*>", RegexOption.IGNORE_CASE)

internal fun stripParagraphMarkup(block: String): String =
    htmlUnescape(block.replace(LEADING_PARAGRAPH_OPEN, "")).trim()

internal class GooglePaProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.GOOGLE_PA

    private val endpoint = config.baseUrl?.trimEnd('/')
        ?: "https://translate-pa.googleapis.com/v1/translateHtml"
    private val timeout = config.timeout
    private val maxChars = cappedMaxChars(config.maxCharsPerRequest, MAX_CHARS)
    private val concurrency = config.chunkConcurrency.coerceIn(1, 32)
    private val configuredKeys = config.apiKeys.map { it.trim() }.filter { it.isNotEmpty() }
    private val keys = KeyRotator(
        configuredKeys + DEFAULT_GOOGLE_PA_API_KEY,
        timeSource = timeSource,
    )
    private val executor = KeyedHttpExecutor(keys, id, config.maxAttempts)

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val plan = splitToBudget(request.units, maxChars, id)
        // Paragraph blocks, not <br>: this endpoint drops <br> and newlines
        // (especially for CJK), which used to break the unit count and push the
        // whole batch into per-unit requests. </p> markers survive one per unit
        // (verified on 500 CJK titles), so the batch stays one request.
        val chunks = chunkUnits(plan.units, maxChars, PA_PARAGRAPH_JOIN)
        val translate: suspend (TextChunk) -> String = throttledTranslator(concurrency, 400) { chunk ->
            translateChunk(chunk, request)
        }
        // A mismatched line count bisects the chunk instead of re-requesting
        // every unit, so one awkward (e.g. CJK) chunk cannot fail the batch and
        // cannot explode into hundreds of small requests either.
        val merged = if (concurrency == 1) {
            buildMap {
                chunks.forEach { chunk ->
                    putAll(
                        applyChunkWithFallback(
                            chunk = chunk,
                            units = plan.units,
                            provider = id,
                            separatorRegex = PA_PARAGRAPH_END,
                            transformLine = ::stripParagraphMarkup,
                            translateChunk = translate,
                        ),
                    )
                }
            }
        } else {
            val results = boundedMap(chunks, concurrency) { chunk ->
                applyChunkWithFallback(
                    chunk = chunk,
                    units = plan.units,
                    provider = id,
                    separatorRegex = PA_PARAGRAPH_END,
                    transformLine = ::stripParagraphMarkup,
                    translateChunk = translate,
                )
            }
            buildMap { results.forEach { putAll(it) } }
        }
        return ProviderResult(plan.restore(merged), model = null)
    }

    private suspend fun translateChunk(
        chunk: TextChunk,
        request: ProviderRequest,
    ): String = executor.execute(
        request = { key ->
            withProviderTimeout(id, timeout) {
                http.post(
                    url = endpoint,
                    body = payload(chunk, request),
                    contentType = ContentType("application", "json+protobuf").toString(),
                    headers = mapOf(
                        "X-Goog-Api-Key" to key.orEmpty(),
                        HttpHeaders.Origin to "https://translate.google.com",
                    ),
                )
            }
        },
        parse = { response ->
            val raw = parseHtml(response.bodyAsText())
            validateChunkLines(raw, chunk, id, PA_PARAGRAPH_END)
            raw
        },
        // The caller bisects a mismatched chunk, so a retry here would only
        // repeat the same deterministic body and multiply the request count.
        retryTruncated = false,
    )

    private fun payload(chunk: TextChunk, request: ProviderRequest): String =
        buildJsonArray {
            add(
                buildJsonArray {
                    add(JsonPrimitive(chunk.text))
                    add(JsonPrimitive(request.source?.value ?: "auto"))
                    add(JsonPrimitive(request.target.value))
                },
            )
            add(JsonPrimitive("wt_lib"))
        }.toString()

    private fun parseHtml(body: String): String {
        val parsed = runCatching {
            Json.parseToJsonElement(body).jsonArray
                .getOrNull(0)?.jsonArray
                ?.getOrNull(0)?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        return parsed?.takeIf { it.isNotBlank() }
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "unexpected Google PA response"),
            )
    }

    private companion object {
        const val MAX_CHARS = 8_000
        const val PA_PARAGRAPH_JOIN = "</p><p>"
    }
}
