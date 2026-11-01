package dev.mirutime.translator.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** JSON Schema shared by OpenAI-compatible and Anthropic structured output. */
internal val translationsJsonSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("translations") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string") }
                    putJsonObject("text") { put("type", "string") }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("id"))
                    add(JsonPrimitive("text"))
                }
                put("additionalProperties", false)
            }
        }
    }
    putJsonArray("required") {
        add(JsonPrimitive("translations"))
    }
    put("additionalProperties", false)
}
