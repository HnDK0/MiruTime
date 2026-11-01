package dev.mirutime.imageloader

import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import java.io.IOException
import okio.Buffer

// Coil's network backend for plugin images: every request goes through the
// injected fetch port, so the plugin jar, WebView cookies, per-host
// Referer/UA and the SSRF-checked engine apply exactly as for covers. Keeping
// the NetworkClient (instead of a custom Fetcher) preserves Coil's URL-keyed
// disk cache and cache strategy.
class PluginImageNetworkClient(
    private val fetch: ImageFetchPort,
) : NetworkClient {

    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T {
        val image = fetch(
            request.url,
            request.headers.asMap().mapValues { (_, values) -> values.joinToString(", ") },
        )
        val headers = NetworkHeaders.Builder().apply {
            image.headers.forEach { (name, value) -> set(name, value) }
        }.build()
        return when (image.status) {
            // fetchImage reports transport failures as status 0.
            0 -> throw IOException("image request failed")
            in 200..299 -> block(
                NetworkResponse(
                    code = image.status,
                    headers = headers,
                    body = image.bytes?.let { bytes -> NetworkResponseBody(Buffer().write(bytes)) },
                ),
            )
            // Coil's NetworkFetcher turns a non-2xx into HttpException; the
            // response code must still come from the real one.
            else -> block(NetworkResponse(code = image.status, headers = headers))
        }
    }
}
