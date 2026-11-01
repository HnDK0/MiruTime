package dev.mirutime.app.core

import java.net.URI

// Repository/index/install byte fetch: walks 3xx by hand (one plain-engine hop
// per request) and owns this lane's error strings. Core's RedirectLimitException
// is deliberately neither caught nor surfaced here.
class RepoFetch(
    private val send: suspend (String) -> RemoteBytes,
    private val maxRedirects: Int = REPO_MAX_REDIRECTS,
) {
    suspend fun fetch(url: String): ByteArray {
        var current = url
        var followed = 0
        while (true) {
            val hop = send(current)
            if (hop.status in REDIRECT_CODES) {
                if (followed >= maxRedirects) {
                    throw IllegalStateException("too many redirects for $url")
                }
                val location = hop.location?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("HTTP ${hop.status} for $current")
                current = URI(hop.finalUrl).resolve(location).toString()
                followed++
                continue
            }
            if (hop.status !in 200..299) {
                throw IllegalStateException("HTTP ${hop.status} for $current")
            }
            return hop.bytes
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

// Redirect hops the plain remote client may follow (initial request not counted).
internal const val REPO_MAX_REDIRECTS = 10
