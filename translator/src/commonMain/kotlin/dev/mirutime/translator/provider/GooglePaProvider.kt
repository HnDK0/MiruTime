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
import dev.mirutime.translator.internal.applyChunkTranslations
import dev.mirutime.translator.internal.boundedMap
import dev.mirutime.translator.internal.cappedMaxChars
import dev.mirutime.translator.internal.chunkUnits
import dev.mirutime.translator.internal.htmlUnescape
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.validateChunkLines
import dev.mirutime.translator.internal.withProviderTimeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
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
        val chunks = chunkUnits(plan.units, maxChars, "<br>")
        // Sequential mode keeps the 400ms spacing that reduces rate limiting.
        val translated = if (concurrency == 1) {
            chunks.mapIndexed { index, chunk ->
                if (index > 0) delay(400)
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
            separatorRegex = BR_OR_NEWLINE,
            transformLine = ::htmlUnescape,
        )
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
            validateChunkLines(raw, chunk, id, BR_OR_NEWLINE)
            raw
        },
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
        val BR_OR_NEWLINE = Regex("<br\\s*/?>|\\r?\\n", RegexOption.IGNORE_CASE)
    }
}
