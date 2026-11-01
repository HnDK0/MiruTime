package dev.mirutime.core.network.security

import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

// DoH provider queried by certificate hostname while the socket connects to a
// hardcoded IP (OkHttp's per-host Dns below), so a broken/blocked system
// resolver cannot also break the DoH lookup. NoveLA DnsOverHttps parity,
// reduced to the two providers requested here.
internal data class DohProvider(
    val name: String,
    val domain: String,
    val primaryIp: String,
    val fallbackIp: String,
)

internal val DEFAULT_DOH_PROVIDERS = listOf(
    DohProvider("Cloudflare", "cloudflare-dns.com", "1.1.1.1", "1.0.0.1"),
    DohProvider("Google", "dns.google", "8.8.8.8", "8.8.4.4"),
    DohProvider("Yandex", "common.dot.dns.yandex.net", "77.88.8.8", "77.88.8.1"),
    DohProvider("AdGuard", "dns.adguard-dns.com", "94.140.14.14", "94.140.15.15"),
    DohProvider("Quad9", "dns.quad9.net", "9.9.9.9", "149.112.112.112"),
    DohProvider("Comodo", "cdn77-doh.com", "8.26.56.26", "8.20.247.20"),
)

// One DoH query. Returns the JSON body, or null on any transport/HTTP failure.
internal fun interface DohTransport {
    fun query(provider: DohProvider, hostname: String): String?
}

/**
 * System DNS first, then Cloudflare/Google DoH. Positive results are cached
 * for [CACHE_TTL_MS]; a full failure is remembered for [NEGATIVE_TTL_MS] so a
 * dead/blocked host does not pay the whole provider loop on every request.
 *
 * Implements both seams from one instance: [Dns] for the Ktor/OkHttp engine
 * (wrapped by SafeDns at connect time) and [HostResolver] for the pre-connect
 * CF/WebView guard.
 */
internal class DnsOverHttps(
    private val transport: DohTransport = OkHttpDohTransport(),
    private val providers: List<DohProvider> = DEFAULT_DOH_PROVIDERS,
    private val systemLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : Dns, HostResolver {

    private data class CacheEntry(val addresses: List<InetAddress>, val at: Long)

    // shortcut: unbounded positive cache, bounded only by distinct hosts seen;
    // add an LRU cap if a hostname flood ever shows up in practice.
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    override fun resolve(host: String): List<String> = lookup(host).mapNotNull { it.hostAddress }

    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.lowercase().removeSuffix(".")
        val now = clock()

        failedAt[key]?.let { ts ->
            if (now - ts < NEGATIVE_TTL_MS) {
                log("event=dns_negative host=$key")
                throw UnknownHostException(
                    "Unable to resolve host \"$hostname\": failed recently (negative cache)",
                )
            }
            failedAt.remove(key)
        }

        cache[key]?.let { entry ->
            if (now - entry.at < CACHE_TTL_MS) {
                log("event=dns_cache host=$key")
                return entry.addresses
            }
            cache.remove(key)
        }

        val system = try {
            systemLookup(key)
        } catch (e: Exception) {
            emptyList()
        }
        if (system.isNotEmpty()) {
            cache[key] = CacheEntry(system, now)
            failedAt.remove(key)
            log("event=dns_system host=$key")
            return system
        }
        log("event=dns_system_failed host=$key")

        for (provider in providers) {
            val addresses = try {
                transport.query(provider, key)?.let(::parseA)
            } catch (e: Exception) {
                null
            }
            if (!addresses.isNullOrEmpty()) {
                cache[key] = CacheEntry(addresses, clock())
                failedAt.remove(key)
                log("event=dns_doh host=$key provider=${provider.name}")
                return addresses
            }
            log("event=dns_doh_failed host=$key provider=${provider.name}")
        }

        failedAt[key] = clock()
        trimNegative()
        log("event=dns_failed host=$key")
        throw UnknownHostException(
            "Unable to resolve host \"$hostname\": system DNS and ${providers.size} DoH providers failed",
        )
    }

    // Only A records: a DoH answer also carries CNAME/AAAA rows, and feeding
    // an AAAA to a resolver that promised IPv4 would be wrong.
    private fun parseA(body: String): List<InetAddress> {
        val answers = try {
            Json.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return answers.mapNotNull { element ->
            val record = element.jsonObject
            if (record["type"]?.jsonPrimitive?.intOrNull != A_RECORD_TYPE) return@mapNotNull null
            val data = record["data"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!IPV4.matches(data)) return@mapNotNull null
            try {
                InetAddress.getByName(data)
            } catch (e: Exception) {
                null
            }
        }
    }

    // Expired entries are dropped when the map hits its cap; live entries stay,
    // so the map can exceed the cap under a burst of distinct dead hosts.
    private fun trimNegative() {
        if (failedAt.size < NEGATIVE_MAX_SIZE) return
        val now = clock()
        failedAt.entries.removeIf { now - it.value >= NEGATIVE_TTL_MS }
    }

    private companion object {
        const val A_RECORD_TYPE = 1
        const val CACHE_TTL_MS = 5 * 60_000L
        const val NEGATIVE_TTL_MS = 60_000L
        const val NEGATIVE_MAX_SIZE = 256
        val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    }
}

// Public seam for the pre-connect CF/WebView guard; plugin HTTP uses the
// engine's SafeDns instead, so okhttp3.Dns stays out of the published API.
fun createDohHostResolver(log: (String) -> Unit = {}): HostResolver = DnsOverHttps(log = log)

internal class OkHttpDohTransport(
    private val client: OkHttpClient = defaultClient(),
) : DohTransport {

    override fun query(provider: DohProvider, hostname: String): String? {
        val url = "https://${provider.domain}/dns-query?name=" +
            URLEncoder.encode(hostname, "UTF-8") + "&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", MEDIA_TYPE)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val MEDIA_TYPE = "application/dns-json"
        const val TIMEOUT_SECONDS = 5L

        // Hardcoded provider IPs: the DoH client resolves only provider
        // domains this way, everything else still uses the system resolver.
        val providerIps: Map<String, List<InetAddress>> = DEFAULT_DOH_PROVIDERS.associate { p ->
            p.domain to listOf(
                InetAddress.getByName(p.primaryIp),
                InetAddress.getByName(p.fallbackIp),
            )
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    providerIps[hostname] ?: Dns.SYSTEM.lookup(hostname)
            })
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Bounds both hardcoded IPs of one provider to a single 5s budget.
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
