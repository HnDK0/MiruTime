package dev.mirutime.app.features.translation.data

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.openAiCompatibleChatEndpoint

/**
 * Typed preflight for a user-configured endpoint. Pure so the settings error
 * and the request path share one rule; the caller reads the live grant and SDK
 * level. Both editable OpenAI base URLs carry the key and chapter text, so the
 * cleartext rule covers OPENAI and OPENAI_COMPATIBLE alike.
 */
fun translationEndpointError(
    provider: ProviderId,
    baseUrl: String,
    sdkInt: Int,
    granted: Boolean,
): TranslationError? {
    if (provider != ProviderId.OPENAI_COMPATIBLE && provider != ProviderId.OPENAI) return null
    val url = baseUrl.trim()
    if (url.isEmpty()) return null
    val syntax = checkTranslationBaseUrl(url)
    if (syntax != TranslationBaseUrlCheck.Ok) {
        // Syntax first: a malformed URL would otherwise spend a network attempt
        // (or surface a timeout) before its real cause is visible.
        return TranslationError.InvalidRequest(provider, "invalid base URL: ${syntax.name}")
    }
    if (!LocalNetworkAccess.isCleartextAllowed(url)) {
        return TranslationError.CleartextNotAllowed(provider)
    }
    if (LocalNetworkAccess.isRequired(url, sdkInt, granted)) {
        return TranslationError.LocalNetworkRequired(provider)
    }
    return null
}

/**
 * Same policy keyed by the request URL instead of the provider: the transport
 * guard only sees a URL, so it matches the two editable OpenAI base URLs and
 * applies [translationEndpointError] to the owner. One check covers every
 * manager entry point (batch, metadata, title, detection) that reaches the
 * network.
 */
fun translationUrlError(
    url: String,
    openAiBaseUrl: String,
    compatibleBaseUrl: String,
    sdkInt: Int,
    granted: Boolean,
): TranslationError? {
    val candidates = listOf(
        ProviderId.OPENAI to openAiBaseUrl,
        ProviderId.OPENAI_COMPATIBLE to compatibleBaseUrl,
    )
    for ((provider, rawBaseUrl) in candidates) {
        val base = rawBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) continue
        // The compatible request URL is built by this same normalizer, so a
        // pasted .../chat/completions base still matches its preflight.
        val matchUrl = if (provider == ProviderId.OPENAI_COMPATIBLE) {
            openAiCompatibleChatEndpoint(base)
        } else {
            base
        }
        if (url == matchUrl || url.startsWith("$matchUrl/")) {
            return translationEndpointError(provider, base, sdkInt, granted)
        }
    }
    return null
}
