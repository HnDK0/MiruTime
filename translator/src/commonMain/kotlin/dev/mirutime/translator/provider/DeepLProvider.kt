package dev.mirutime.translator.provider

import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import dev.mirutime.translator.TranslationHttpClient
import dev.mirutime.translator.TranslationProviderConfig
import dev.mirutime.translator.TranslationUnit
import dev.mirutime.translator.internal.KeyRotator
import dev.mirutime.translator.internal.KeyedHttpExecutor
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.internal.ProviderResult
import dev.mirutime.translator.internal.TranslationProvider
import dev.mirutime.translator.internal.cappedMaxChars
import dev.mirutime.translator.internal.chunkUnitGroups
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.withProviderTimeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.TimeSource

internal class DeepLProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.DEEPL

    private val endpoint = (config.baseUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        ?: if (config.apiKeys.any { it.trim().endsWith(FREE_KEY_SUFFIX) }) FREE_URL else PAID_URL) +
        "/v2/translate"
    private val timeout = config.timeout
    private val maxChars = cappedMaxChars(config.maxCharsPerRequest, MAX_CHARS)
    private val keys = KeyRotator(config.apiKeys, timeSource = timeSource)
    private val executor = KeyedHttpExecutor(keys, id, config.maxAttempts)

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val plan = splitToBudget(request.units, maxChars, id)
        val translations = mutableMapOf<String, String>()
        var detected: LanguageCode? = null

        for (group in chunkUnitGroups(plan.units, maxChars)) {
            val parsed = executor.execute(
                request = { key ->
                    withProviderTimeout(id, timeout) {
                        http.post(
                            url = endpoint,
                            body = body(group, request),
                            contentType = ContentType.Application.Json.toString(),
                            headers = mapOf(
                                HttpHeaders.Authorization to
                                    "DeepL-Auth-Key ${key.orEmpty()}",
                            ),
                        )
                    }
                },
                parse = { response -> parseResponse(response.bodyAsText(), group) },
            )
            translations.putAll(parsed.translations)
            if (detected == null) detected = parsed.detected
        }

        return ProviderResult(plan.restore(translations), model = null, detectedSource = detected)
    }

    private fun body(units: List<TranslationUnit>, request: ProviderRequest): String =
        buildJsonObject {
            putJsonArray("text") {
                for (unit in units) add(JsonPrimitive(unit.text))
            }
            request.source?.let { put("source_lang", it.value.uppercase()) }
            put("target_lang", request.target.value.uppercase())
        }.toString()

    private fun parseResponse(body: String, units: List<TranslationUnit>): Parsed {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "unexpected DeepL response"),
            )
        val items = root["translations"]?.jsonArray
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "DeepL returned no translations"),
            )
        if (items.size != units.size) {
            throw TranslationFailure(TranslationError.Truncated(id, units.size, items.size))
        }

        val translations = linkedMapOf<String, String>()
        var detected: LanguageCode? = null
        items.forEachIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw TranslationFailure(
                    TranslationError.InvalidResponse(id, "DeepL translation is not an object"),
                )
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
                ?: throw TranslationFailure(
                    TranslationError.InvalidResponse(id, "DeepL translation without text"),
                )
            translations[units[index].id] = text
            if (detected == null) {
                detected = obj["detected_source_language"]
                    ?.jsonPrimitive?.contentOrNull
                    ?.let { parseLanguage(it) }
            }
        }

        return Parsed(translations, detected)
    }

    private fun parseLanguage(raw: String): LanguageCode? =
        runCatching { LanguageCode.parse(raw) }.getOrNull()

    private data class Parsed(
        val translations: Map<String, String>,
        val detected: LanguageCode?,
    )

    private companion object {
        const val FREE_URL = "https://api-free.deepl.com"
        const val PAID_URL = "https://api.deepl.com"
        const val FREE_KEY_SUFFIX = ":fx"
        const val MAX_CHARS = 8_000
    }
}
