package dev.mirutime.translator.internal

import dev.mirutime.translator.TranslationHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Plain Ktor adapter for tests and backward compatibility. It deliberately adds
 * no default headers, including User-Agent.
 */
internal class KtorTranslationHttpClient(
    private val client: HttpClient,
) : TranslationHttpClient {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse = client.get(url) {
        headers.forEach { (name, value) -> header(name, value) }
    }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
    ): HttpResponse = client.post(url) {
        headers.forEach { (name, value) -> header(name, value) }
        contentType(ContentType.parse(contentType))
        setBody(body)
    }
}
