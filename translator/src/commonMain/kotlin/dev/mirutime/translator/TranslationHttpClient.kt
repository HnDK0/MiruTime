package dev.mirutime.translator

import io.ktor.client.statement.HttpResponse

/**
 * Transport used by translation providers. The production implementation is
 * [CoreTranslationHttpClient], which delegates to MiruTime-Core networking.
 */
interface TranslationHttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse
}
