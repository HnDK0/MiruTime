package dev.mirutime.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// chapter_pages stores a JSON array of relative file paths. Parsing it with
// android.jar's org.json is unavailable in plain JVM unit tests, so this keeps
// the filesystem cleanup path testable and independent of Android stubs.
internal fun jsonStringArray(raw: String): List<String> {
    val body = raw.trim()
    if (!body.startsWith("[") || !body.endsWith("]")) return emptyList()
    return JsonString.findAll(body.substring(1, body.length - 1))
        .map { match ->
            match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        .toList()
}

private val JsonString = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")

// One string field of a stored JSON object (books.extrasJson); malformed JSON
// yields null instead of throwing on a read path.
internal fun jsonStringField(raw: String, key: String): String? = runCatching {
    Json.parseToJsonElement(raw).jsonObject[key]?.jsonPrimitive?.contentOrNull
}.getOrNull()?.takeIf { it.isNotBlank() }
