package dev.mirutime.app.features.opds.data

import dev.mirutime.app.di.PluginGraph
import java.io.File
import java.util.Base64

/** One feed response. [finalUrl] is what relative hrefs resolve against. */
data class RemoteFetch(
    val status: Int,
    val bytes: ByteArray?,
    val finalUrl: String,
    val contentType: String?,
)

/** Network seam for the OPDS lane; tests implement this with canned bodies. */
interface OpdsTransport {
    suspend fun get(url: String, headers: Map<String, String>): RemoteFetch

    /** Streams the file to [target]; returns its size. */
    suspend fun download(url: String, headers: Map<String, String>, target: File): Long
}

/**
 * Basic auth per RFC 7617: UTF-8 credentials. Not okhttp's
 * `Credentials.basic`, which encodes ISO-8859-1.
 */
fun basicAuthHeader(login: String, password: String): String =
    "Basic " + Base64.getEncoder().encodeToString("$login:$password".toByteArray(Charsets.UTF_8))

class AndroidOpdsTransport(private val graph: PluginGraph) : OpdsTransport {
    override suspend fun get(url: String, headers: Map<String, String>): RemoteFetch {
        val response = graph.fetchRemote(url, headers, followRedirects = true)
        return RemoteFetch(
            status = response.status,
            // Error bodies are keeps: a 400 from a digest-only calibre carries
            // the marker the controller maps to DigestOnly.
            bytes = response.bytes,
            finalUrl = response.finalUrl,
            contentType = response.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.substringBefore(';')
                ?.trim(),
        )
    }

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
    ): Long = graph.downloadToFile(url, headers, target)
}
