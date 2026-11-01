package dev.mirutime.translator.internal

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

internal suspend fun <T> withProviderTimeout(
    provider: ProviderId,
    timeout: Duration,
    block: suspend () -> T,
): T = withTimeoutOrNull(timeout) { block() }
    ?: throw TranslationFailure(TranslationError.Network(provider, "request timed out"))

private val QUOTA_MARKERS = listOf(
    "insufficient_quota",
    "quota_exceeded",
    "quota exceeded",
    "exceeded your current quota",
)

internal fun isQuotaBody(body: String): Boolean =
    QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }

internal fun htmlUnescape(value: String): String =
    value
        .replace(Regex("""&#(\d+);""")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
