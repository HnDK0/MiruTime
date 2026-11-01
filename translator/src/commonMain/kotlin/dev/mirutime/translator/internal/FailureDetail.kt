package dev.mirutime.translator.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_DETAIL_CHARS = 200
private val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val TAG_PATTERN = Regex("<[^>]*>")
private val SPACES = Regex("\\s+")

/**
 * Short description of a failed response body: the provider's `error.message`
 * when the body is JSON, otherwise a redacted excerpt (markup stripped, URLs
 * removed) so a full endpoint never leaks into an error message.
 */
internal fun failureDetail(rawBody: String): String {
    val body = rawBody.trim()
    val jsonMessage = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        when (val error = root["error"]) {
            is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    val detail = jsonMessage ?: body.replace(TAG_PATTERN, " ")
    return URL_PATTERN.replace(detail, "[url]")
        .replace(SPACES, " ")
        .trim()
        .take(MAX_DETAIL_CHARS)
}
