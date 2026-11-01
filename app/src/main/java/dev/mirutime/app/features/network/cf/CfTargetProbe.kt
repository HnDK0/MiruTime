package dev.mirutime.app.features.network.cf

import dev.mirutime.core.network.cf.CloudflareDetector
import dev.mirutime.core.network.security.isTransportAllowed
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Minimal HTTP response the probe needs. Injected so tests can feed a fake
// challenge/clean body without touching the network.
data class ProbeResponse(
    val status: Int,
    val body: String,
    val serverHeader: String? = null,
    val cfMitigatedHeader: String? = null,
    val location: String? = null,
)

fun interface ProbeHttp {
    suspend fun get(url: String, headers: Map<String, String>): ProbeResponse
}

// Clears a target only when CloudflareDetector says the response carries no
// challenge. Redirects are followed manually so every hop passes the transport
// policy first (scheme/userinfo) — private/LAN targets stay allowed.
class HttpCfProbe(
    private val http: ProbeHttp,
    private val detector: CloudflareDetector = CloudflareDetector(),
) : CfTargetProbe {

    override suspend fun isClear(
        targetUrl: String,
        cookies: Map<String, String>,
        userAgent: String?,
    ): Boolean {
        val headers = buildMap {
            if (cookies.isNotEmpty()) {
                put("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        var current = targetUrl
        var currentHeaders = headers
        repeat(MAX_HOPS) {
            // Scheme/userinfo only: the probe's HttpURLConnection has no SafeDns
            // hook, but a private/LAN target is a legitimate clearance probe.
            if (!isTransportAllowed(current)) return false
            val response = try {
                http.get(current, currentHeaders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false
            }
            val location = response.location
            if (response.status in REDIRECT_STATUSES && !location.isNullOrBlank()) {
                val next = resolve(current, location) ?: return false
                // Cookies belong to the original host; never leak them to a
                // cross-host redirect.
                if (hostOf(next) != hostOf(current)) currentHeaders = currentHeaders - "Cookie"
                current = next
                return@repeat
            }
            return !detector.isChallenge(
                status = response.status,
                body = response.body,
                cfOptions = null,
                serverHeader = response.serverHeader,
                cfMitigatedHeader = response.cfMitigatedHeader,
                requestUrl = current,
            )
        }
        return false
    }

    private fun resolve(base: String, location: String): String? =
        try {
            URI(base).resolve(location).toString()
        } catch (e: Exception) {
            null
        }

    private fun hostOf(url: String): String? =
        try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val MAX_HOPS = 4
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

// Real probe transport: no automatic redirects, bounded time and body.
class UrlConnectionProbeHttp(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : ProbeHttp {

    override suspend fun get(url: String, headers: Map<String, String>): ProbeResponse =
        withContext(Dispatchers.IO) {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total < MAX_BODY_BYTES) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray().decodeToString()
                }.orEmpty()
                ProbeResponse(
                    status = status,
                    body = body,
                    serverHeader = connection.getHeaderField("Server"),
                    cfMitigatedHeader = connection.getHeaderField("cf-mitigated"),
                    location = connection.getHeaderField("Location"),
                )
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val MAX_BODY_BYTES = 512 * 1024
    }
}
