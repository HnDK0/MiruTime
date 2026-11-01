package dev.mirutime.translator.provider

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import dev.mirutime.translator.TranslationHttpClient
import dev.mirutime.translator.TranslationProviderConfig
import dev.mirutime.translator.TranslationUnit
import dev.mirutime.translator.internal.KeyRotator
import dev.mirutime.translator.internal.KeyedHttpExecutor
import dev.mirutime.translator.internal.LLM_UNIT_OVERHEAD
import dev.mirutime.translator.internal.ProviderRequest
import dev.mirutime.translator.internal.ProviderResult
import dev.mirutime.translator.internal.TranslationProvider
import dev.mirutime.translator.internal.boundedMap
import dev.mirutime.translator.internal.chunkUnitGroups
import dev.mirutime.translator.internal.jsonInput
import dev.mirutime.translator.internal.llmMaxChars
import dev.mirutime.translator.internal.parseTranslations
import dev.mirutime.translator.internal.renderPrompt
import dev.mirutime.translator.internal.splitToBudget
import dev.mirutime.translator.internal.translationsJsonSchema
import dev.mirutime.translator.internal.withProviderTimeout
import dev.mirutime.translator.openAiCompatibleChatEndpoint
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

/** Any OpenAI chat-completions compatible endpoint (OpenRouter, Ollama, LM Studio...). */
internal class OpenAiCompatibleProvider(
    private val http: TranslationHttpClient,
    config: TranslationProviderConfig,
    timeSource: TimeSource,
) : TranslationProvider {
    override val id: ProviderId = ProviderId.OPENAI_COMPATIBLE

    private val model = config.model?.takeIf { it.isNotBlank() }
    private val endpoint = config.baseUrl?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::openAiCompatibleChatEndpoint)
    private val timeout = config.timeout
    private val maxOutputTokens = config.maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT
    private val maxCharsPerRequest = config.maxCharsPerRequest
    private val useJsonSchema = config.useJsonSchema
    private val concurrency = config.chunkConcurrency.coerceIn(1, 32)
    private val keys = KeyRotator(config.apiKeys, timeSource = timeSource)
    private val executor = KeyedHttpExecutor(keys, id, config.maxAttempts)

    override suspend fun translate(request: ProviderRequest): ProviderResult {
        val resolvedModel = model ?: throw TranslationFailure(
            TranslationError.InvalidRequest(id, "model is required"),
        )
        val url = endpoint ?: throw TranslationFailure(
            TranslationError.InvalidRequest(id, "baseUrl is required"),
        )
        val prompt = renderPrompt(request.prompt, request.source, request.target)
        val maxChars = llmMaxChars(
            configured = maxCharsPerRequest,
            maxOutputTokens = maxOutputTokens,
            defaultMaxOutputTokens = DEFAULT_MAX_OUTPUT,
            target = request.target,
        )
        val plan = splitToBudget(request.units, maxChars, id)
        val groups = chunkUnitGroups(plan.units, maxChars, overheadPerUnit = LLM_UNIT_OVERHEAD)
        val results = if (concurrency == 1) {
            groups.map { group -> translateGroup(group, prompt, resolvedModel, url) }
        } else {
            boundedMap(groups, concurrency) { group ->
                translateGroup(group, prompt, resolvedModel, url)
            }
        }
        val translations = linkedMapOf<String, String>()
        for (parsed in results) {
            translations.putAll(parsed)
        }

        return ProviderResult(plan.restore(translations), resolvedModel)
    }

    private suspend fun translateGroup(
        group: List<TranslationUnit>,
        prompt: String,
        model: String,
        url: String,
    ): Map<String, String> = executor.execute(
        request = { key ->
            withProviderTimeout(id, timeout) {
                http.post(
                    url = url,
                    body = body(model, prompt, jsonInput(group)),
                    contentType = ContentType.Application.Json.toString(),
                    headers = mapOf(
                        HttpHeaders.Authorization to "Bearer ${key.orEmpty()}",
                    ),
                )
            }
        },
        parse = { response ->
            parseTranslations(parseContent(response.bodyAsText(), group.size), group, id)
        },
    )

    private fun body(model: String, prompt: String, input: String): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", prompt)
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", input)
                },
            )
        }
        put("max_tokens", maxOutputTokens)
        putJsonObject("response_format") {
            if (useJsonSchema) {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "translations")
                    put("strict", true)
                    put("schema", translationsJsonSchema)
                }
            } else {
                put("type", "json_object")
            }
        }
    }.toString()

    private fun parseContent(body: String, expected: Int): String {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "unexpected OpenAI-compatible response"),
            )

        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "OpenAI-compatible response has no choices"),
            )

        when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
            "length" -> throw TranslationFailure(
                TranslationError.Truncated(id, expected, 0),
            )
            "content_filter" -> throw TranslationFailure(
                TranslationError.ContentBlocked(id, "content_filter"),
            )
        }

        val messageText = when (val content = choice["message"]?.jsonObject?.get("content")) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text", "output_text", null -> obj["text"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.joinToString("")
            else -> null
        }
        // Older/legacy-compatible servers answer with choices[0].text instead
        // of a message object; keep that as the fallback.
        val text = (messageText ?: choice["text"]?.jsonPrimitive?.contentOrNull)?.trim()

        return text?.takeIf { it.isNotBlank() }
            ?: throw TranslationFailure(
                TranslationError.InvalidResponse(id, "OpenAI-compatible returned empty text"),
            )
    }

    private companion object {
        const val DEFAULT_MAX_OUTPUT = 4_096
    }
}
