package dev.mirutime.app.di.host

// JVM stand-in for CookieManager: keeps one raw header per URL (the same shape
// getCookie returns) and records every write/flush so the store's contract is
// assertable without an Android WebView provider.
internal class FakeWebCookieStorage : WebCookieStorage {
    val cookieHeaders = mutableMapOf<String, String>()

    // (url, raw Set-Cookie header) in call order.
    val setCalls = mutableListOf<Pair<String, String>>()
    var flushes = 0
    var unavailable = false

    override suspend fun getCookie(url: String): String? =
        if (unavailable) null else cookieHeaders[url]

    override suspend fun setCookie(url: String, setCookieHeader: String): Boolean {
        if (unavailable) return false
        setCalls += url to setCookieHeader
        apply(url, setCookieHeader)
        return true
    }

    override suspend fun flush(): Boolean {
        flushes++
        return !unavailable
    }

    private fun apply(url: String, header: String) {
        val name = header.substringBefore('=').trim()
        val value = header.substringAfter('=', "").substringBefore(';').trim()
        val kept = cookieHeaders[url].orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.substringBefore('=').trim() != name }
        val expires = header.contains("Max-Age=0", ignoreCase = true)
        val updated = if (expires) kept else kept + "$name=$value"
        if (updated.isEmpty()) cookieHeaders.remove(url)
        else cookieHeaders[url] = updated.joinToString("; ")
    }
}
