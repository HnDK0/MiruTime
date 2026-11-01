package dev.mirutime.app.features.translation.data

import java.net.URI

/**
 * Syntax check for the editable OpenAI base URLs. A base URL is not a request
 * URL: it needs an http(s) scheme and a host, while the path stays free-form
 * (`/v1` is the common OpenAI form, never required). Blank means "not set".
 */
enum class TranslationBaseUrlCheck {
    Ok,
    MissingScheme,
    UnsupportedScheme,
    MissingHost,
    InvalidUrl,
}

fun checkTranslationBaseUrl(raw: String): TranslationBaseUrlCheck {
    val url = raw.trim()
    if (url.isEmpty()) return TranslationBaseUrlCheck.Ok
    // Ktor would normalize these into a real request; a base URL must be literal.
    if (url.any { it.isWhitespace() } || '\\' in url) return TranslationBaseUrlCheck.InvalidUrl
    if ("://" !in url) return TranslationBaseUrlCheck.MissingScheme
    val scheme = url.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return TranslationBaseUrlCheck.UnsupportedScheme
    val authority = url.substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (authority.isEmpty()) return TranslationBaseUrlCheck.MissingHost
    val uri = runCatching { URI(url) }.getOrNull() ?: return TranslationBaseUrlCheck.InvalidUrl
    // Credentials in a base URL would end up in logs and the request itself.
    if (!uri.userInfo.isNullOrEmpty()) return TranslationBaseUrlCheck.InvalidUrl
    if (uri.rawQuery != null || uri.rawFragment != null) return TranslationBaseUrlCheck.InvalidUrl
    if (uri.host.isNullOrBlank()) return TranslationBaseUrlCheck.MissingHost
    return TranslationBaseUrlCheck.Ok
}
