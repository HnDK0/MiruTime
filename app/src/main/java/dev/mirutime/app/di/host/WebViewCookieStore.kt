package dev.mirutime.app.di.host

import android.webkit.CookieManager
import dev.mirutime.core.host.Cookie
import dev.mirutime.core.host.CookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Platform cookie storage seam: the store owns the rules, the adapter owns
// CookieManager. Every method is best-effort — an unavailable WebView provider
// degrades to "no cookies", never to a failed request.
internal interface WebCookieStorage {
    /** Raw `Cookie` header for [url]; null when the provider is unavailable. */
    suspend fun getCookie(url: String): String?

    /** Writes one raw Set-Cookie header against [url]; false = provider unavailable. */
    suspend fun setCookie(url: String, setCookieHeader: String): Boolean

    /** Persists pending writes; false = provider unavailable. */
    suspend fun flush(): Boolean
}

// CookieManager access stays lazy and on IO: getInstance() loads the WebView
// provider (~100-300 ms) and must never run on Main or at startup. Only a
// successful init is cached, so one bad init cannot disable cookies forever
// (NoveLA ScraperCookieJar.kt:22-32).
internal class AndroidWebCookieStorage : WebCookieStorage {
    @Volatile
    private var cached: CookieManager? = null

    private fun manager(): CookieManager? {
        cached?.let { return it }
        return runCatching {
            CookieManager.getInstance().apply { setAcceptCookie(true) }
        }.getOrNull()?.also { cached = it }
    }

    override suspend fun getCookie(url: String): String? = withContext(Dispatchers.IO) {
        runCatching { manager()?.getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override suspend fun setCookie(url: String, setCookieHeader: String): Boolean =
        withContext(Dispatchers.IO) {
            val manager = manager() ?: return@withContext false
            runCatching { manager.setCookie(url, setCookieHeader) }.isSuccess
        }

    override suspend fun flush(): Boolean = withContext(Dispatchers.IO) {
        val manager = manager() ?: return@withContext false
        runCatching { manager.flush() }.isSuccess
    }
}

/**
 * One `CookieStore` over `CookieManager`: the WebView and every native request
 * read and write the same cookies, so there is no merge and no precedence rule.
 *
 * `CookieManager.getCookie(url)` only returns a header string, so [get] parses
 * `name=value` pairs without domain/path/expiry — the platform owns the
 * RFC-6265 rules now. [setFromResponse] forwards the raw Set-Cookie against the
 * actual response URL.
 */
internal class WebViewCookieStore(
    private val storage: WebCookieStorage,
) : CookieStore {
    override suspend fun get(url: String): List<Cookie> {
        val header = storage.getCookie(url) ?: return emptyList()
        // Order preserved: CookieManager lists the most specific path first, so
        // a same-name cookie on a second path must not collapse the header.
        return splitCookieHeader(header).map { (name, value) -> Cookie(name = name, value = value) }
    }

    override suspend fun setFromResponse(url: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        setCookieHeaders.forEach { header -> storage.setCookie(url, header) }
        storage.flush()
    }

    override suspend fun clear(host: String) {
        val names = LinkedHashSet<String>()
        SCHEMES.forEach { scheme ->
            storage.getCookie("$scheme://$host/")?.let { names += parseCookieHeader(it).keys }
        }
        clearCookies(host, names.toList())
    }

    override suspend fun clearCookies(host: String, names: List<String>) {
        val normalized = host.trim().removePrefix(".").lowercase()
        if (normalized.isNotEmpty()) {
            names.filter { it.isNotEmpty() }.forEach { name ->
                SCHEMES.forEach { scheme ->
                    storage.setCookie("$scheme://$normalized/", expired(name, null))
                    storage.setCookie("$scheme://$normalized/", expired(name, normalized))
                    storage.setCookie("$scheme://$normalized/", expired(name, ".$normalized"))
                }
            }
        }
        storage.flush()
    }

    private fun expired(name: String, domain: String?): String = buildString {
        append(name).append("=; Max-Age=0; Path=/")
        if (domain != null) append("; Domain=").append(domain)
    }

    private companion object {
        val SCHEMES = listOf("https", "http")
    }
}

// Same parser the CF WebView snapshot uses: `name=value; name2=value2`.
internal fun parseCookieHeader(header: String?): Map<String, String> =
    splitCookieHeader(header).toMap()

private fun splitCookieHeader(header: String?): List<Pair<String, String>> {
    if (header.isNullOrBlank()) return emptyList()
    return header.split(';').mapNotNull { part ->
        val pair = part.trim().split('=', limit = 2)
        if (pair.size != 2) return@mapNotNull null
        val name = pair[0].trim()
        if (name.isEmpty()) return@mapNotNull null
        name to pair[1].trim()
    }
}
