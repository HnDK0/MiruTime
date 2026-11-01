package dev.mirutime.translator.internal

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

private const val HEX = "0123456789abcdef"

internal fun randomHex(length: Int): String = buildString(length) {
    repeat(length) { append(HEX[Random.nextInt(HEX.length)]) }
}

/**
 * Maps statuses that Yandex uses outside the standard HTTP failure set.
 * These endpoints are keyless, so HTTP 403 + code 405/406 means the request
 * was rejected (format/anti-bot), not that credentials are invalid.
 */
internal fun yandexTrStatusError(
    provider: ProviderId,
    status: Int,
    body: String,
    maxRequestChars: Int,
): TranslationError? = when (status) {
    413 -> TranslationError.InvalidRequest(provider, "text exceeds $maxRequestChars")
    403 -> yandexTrCode(body)
        ?.takeIf { it == 405 || it == 406 }
        ?.let {
            TranslationError.InvalidResponse(provider, "Yandex rejected the request (code $it)")
        }
    else -> null
}

internal fun parseYandexTrTexts(
    provider: ProviderId,
    body: String,
    expected: Int,
    maxRequestChars: Int,
): List<String> {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw TranslationFailure(
            TranslationError.InvalidResponse(provider, "unexpected Yandex response"),
        )

    val code = (root["code"] as? JsonPrimitive)?.intOrNull
    if (code != null && code != 200) {
        throw TranslationFailure(yandexTrCodeError(provider, code, body, maxRequestChars))
    }

    val texts = when (val text = root["text"]) {
        null -> throw TranslationFailure(
            TranslationError.InvalidResponse(provider, "Yandex returned no text"),
        )
        is JsonArray -> text.map { it.asText(provider) }
        // Some endpoints return a single string instead of a one-element array.
        is JsonPrimitive -> listOfNotNull(text.contentOrNull)
        else -> throw TranslationFailure(
            TranslationError.InvalidResponse(provider, "unexpected Yandex text payload"),
        )
    }

    if (texts.size != expected) {
        throw TranslationFailure(TranslationError.Truncated(provider, expected, texts.size))
    }
    return texts
}

private fun yandexTrCode(body: String): Int? =
    runCatching {
        (Json.parseToJsonElement(body).jsonObject["code"] as? JsonPrimitive)?.intOrNull
    }.getOrNull()

private fun yandexTrCodeError(
    provider: ProviderId,
    code: Int,
    body: String,
    maxRequestChars: Int,
): TranslationError = when (code) {
    429 -> TranslationError.RateLimited(provider, message = body)
    413 -> TranslationError.InvalidRequest(provider, "text exceeds $maxRequestChars")
    405, 406 -> TranslationError.InvalidResponse(
        provider,
        "Yandex rejected the request (code $code): $body",
    )
    else -> TranslationError.InvalidResponse(provider, "Yandex returned code $code: $body")
}

private fun JsonElement.asText(provider: ProviderId): String =
    (this as? JsonPrimitive)?.contentOrNull
        ?: throw TranslationFailure(
            TranslationError.InvalidResponse(provider, "Yandex returned a non-text translation"),
        )
