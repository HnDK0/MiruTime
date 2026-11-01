package dev.mirutime.translator

import dev.mirutime.core.network.PluginHttpClient
import io.ktor.client.statement.HttpResponse

/**
 * Production transport over MiruTime-Core networking. Common browser headers,
 * cookies, Cloudflare handling, redirects, SSRF checks and retries come from
 * [PluginHttpClient]. [preflight] runs once per request URL before any network
 * I/O, so an endpoint policy covers every manager entry point.
 */
class CoreTranslationHttpClient(
    private val client: PluginHttpClient,
    private val sourceId: String = "translation",
    private val preflight: ((String) -> TranslationError?)? = null,
) : TranslationHttpClient {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse {
        preflight?.invoke(url)?.let { throw TranslationFailure(it, preflight = true) }
        return client.get(sourceId = sourceId, url = url, headers = headers)
    }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse {
        preflight?.invoke(url)?.let { throw TranslationFailure(it, preflight = true) }
        return client.post(
            sourceId = sourceId,
            url = url,
            body = body,
            headers = headers,
            contentType = contentType,
        )
    }
}
