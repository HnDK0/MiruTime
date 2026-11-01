package dev.mirutime.core.engine.binding

import dev.mirutime.core.api.PluginError
import dev.mirutime.core.engine.LuaSession
import dev.mirutime.core.host.Logger
import dev.mirutime.core.host.WebviewClickSelector
import dev.mirutime.core.host.WebviewFetcher
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.pluginCharset
import dev.mirutime.core.network.security.DnsFailureException
import dev.mirutime.core.network.security.ResponseTooLargeException
import dev.mirutime.core.network.security.SsrfBlockedException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import java.nio.charset.Charset

// URL-keyed memo for http_get, ACTIVE only while LuaPluginSource.book()
// aggregates its per-field getters: each getter fetches the same book page,
// so a non-caching plugin would hit the site once per getter (~7x per
// book() without this). Owned/driven by LuaPluginSource (begin/end around
// the getter sequence, cleared in finally), shared with NetworkBindings,
// which consults it in http_get only. http_post / http_get_batch and every
// non-book() path (catalog, chapters, ...) never read or write it.
// ceiling: keyed by URL ONLY — the same URL requested with different headers
// (or method) within one book() shares the first response; acceptable because
// book() getters don't vary headers per page. Plugins with their own caches
// (e.g. a details cache) sit ABOVE this one and are unaffected. Tables are
// COPIED at the boundary (copy-on-store + copy-on-read): a plugin mutating
// r.body/r.headers of one getter's result must not poison the others —
// LuaValue scalars are immutable, only LuaTables alias.
// Session is single-threaded (facade contract), so no locking here.
class GetMemo {
    private var enabled = false
    private val responses = mutableMapOf<String, LuaValue>()

    fun begin() {
        responses.clear()
        enabled = true
    }

    fun end() {
        enabled = false
        responses.clear()
    }

    fun get(url: String): LuaValue? = if (enabled) responses[url]?.let { copyResponse(it) } else null

    fun put(url: String, response: LuaValue) {
        if (enabled) responses[url] = copyResponse(response)
    }

    // Deep copy of a response table: nested headers/body tables must not
    // alias the stored copy (LuaValue scalars are immutable, LuaTables alias).
    private fun copyResponse(value: LuaValue): LuaValue {
        if (!value.istable()) return value
        val src = value.checktable()
        val dst = LuaTable()
        for (key in src.keys()) dst.set(copyResponse(key), copyResponse(src.get(key)))
        return dst
    }
}

// Lua -> Ktor network bridge: installs http_get / http_post / http_get_batch /
// sleep into a session's Globals. PluginHttpClient is injected, never a global.
// Response shape matches NoveLA: { success, body, code, headers } on every
// completed exchange (success is 2xx), and { success=false, body=message,
// code=-1 } on a bridge failure. `status`/`charset`/`error`/`errorType` remain
// as aliases for existing host callers.
// ceiling: session worker is single-threaded; runBlocking here only blocks
// this session's worker thread. LuaJ functions are synchronous, so a suspend
// HTTP call has to be bridged with runBlocking — safe because LuaSession
// serializes all calls on one worker and every call is bounded by
// defaultTimeoutMs (a coroutine timer around the request itself, so a hung
// fetch is cancelled and the worker is released).
class NetworkBindings(
    private val client: PluginHttpClient,
    private val sourceId: String,
    // Disabled unless the factory wires the book()-driven memo (see GetMemo).
    private val getMemo: GetMemo = GetMemo(),
    // Plugin-declared response charset (the script's `charset` global via
    // PluginMetadata.charset, NoveLA SourceInterface.charset): the decoding
    // default when the request carries no explicit charset option. Null =
    // undeclared (previous behavior: Content-Type header, then UTF-8).
    private val defaultCharset: String? = null,
    // Per-call ceiling for calls whose plugin options carry no `timeout`.
    // Enforced as a real coroutine timer around the suspend Ktor request, so
    // it CANCELS the fetch — PluginHttpClient's 90s default deadline only
    // checks at boundaries and would let a hung host pin the session worker
    // (and LuaPluginSource's mutex) forever. A plugin's explicit `timeout`
    // keeps its existing whole-call-budget semantics and is never overridden.
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    // Debug sink (`MiruLog.net`); null in tests/hosts that do not log.
    private val logger: Logger? = null,
    // Shared with LuaPluginSource: transports the "request failed but the
    // plugin returned an empty page" signal to the catalog contract.
    private val failureFlag: NetworkFailureFlag = NetworkFailureFlag(),
    // Hidden WebView seam; null = the `webview_fetch` global is not installed
    // (JVM hosts/tests), so plugins see the plain nil they already handle.
    private val webviewFetcher: WebviewFetcher? = null,
) {
    companion object {
        // 30s: the transport ladder's worst case is 27s (4x3s attempts + 15s
        // tail), so a legitimate retry sequence still fits inside one bridge
        // call; a hung host or zombie request is cut here instead.
        const val DEFAULT_TIMEOUT_MS: Long = 30_000
    }

    fun install(session: LuaSession) {
        session.globals.set("http_get", httpGet)
        session.globals.set("http_post", httpPost)
        session.globals.set("http_get_batch", httpGetBatch)
        session.globals.set("sleep", sleep)
        // Absent unless the host wired a hidden WebView: plugins feature-detect
        // it with type(webview_fetch) == "function" (dood.lua:38).
        webviewFetcher?.let { session.globals.set("webview_fetch", webviewFetch) }
    }

    private val httpGet = object : TwoArgFunction() {
        override fun call(url: LuaValue, opts: LuaValue): LuaValue {
            val target = url.checkjstring()
            val options = opts.takeIf { it.istable() }?.checktable()
            // Parse BEFORE the memo: an unsupported method must fail the same
            // way whether or not the URL is memoized.
            val request = parseOptions("http_get", options)
            // Memo hit (book() aggregation): the cached response table comes
            // back with no network call — and therefore no trace event; the
            // trace's lower request count is the memo evidence itself.
            getMemo.get(target)?.let { return it }
            val response = runBlocking { doRequest(target, request) }
            getMemo.put(target, response)
            return response
        }
    }

    private val httpPost = object : ThreeArgFunction() {
        override fun call(url: LuaValue, body: LuaValue, opts: LuaValue): LuaValue {
            val target = url.checkjstring()
            val payload = body.checkjstring()
            val options = opts.takeIf { it.istable() }?.checktable()
            val request = parseOptions("http_post", options)
            return runBlocking { doPost(target, payload, request) }
        }
    }

    private val httpGetBatch = object : TwoArgFunction() {
        override fun call(urls: LuaValue, opts: LuaValue): LuaValue {
            val urlTable = urls.checktable()
            val options = opts.takeIf { it.istable() }?.checktable()
            // Batch-wide defaults; every per-item key may override them.
            val global = parseOptions("http_get_batch", options)
            val maxConcurrent = options?.get("maxConcurrent")?.optint(4)?.coerceIn(1, 32) ?: 4
            val items = (1..urlTable.length()).map { i -> parseBatchItem(urlTable.get(i), global) }
            val results = runBlocking {
                val semaphore = Semaphore(maxConcurrent)
                coroutineScope {
                    items.map { item ->
                        async {
                            semaphore.withPermit { doRequest(item.url, item.options) }
                        }
                    }.awaitAll()
                }
            }
            return LuaValue.listOf(results.toTypedArray())
        }
    }

    private val sleep = object : TwoArgFunction() {
        override fun call(ms: LuaValue, opts: LuaValue): LuaValue {
            val millis = ms.checklong().coerceAtLeast(0)
            runBlocking { delay(millis) }
            return LuaValue.NIL
        }
    }

    // webview_fetch(url, { click = "<CSS selector>" | "$('<CSS selector>').click()",
    //                     expect = "..." }) -> html | nil.
    // Same ceiling as http_get: LuaJ is synchronous, so the suspend fetch is
    // bridged with runBlocking on the single-threaded session worker and bounded
    // by the bridge timeout. Every failure is a plain nil — dood treats nil as
    // "no pass_md5" and keeps its existing error path.
    // `click` is only ever a selector: the plugin's jQuery wrapper (dood's
    // form) is unwrapped here, and any other string is rejected before the
    // host opens a page, so plugin text never becomes JS.
    private val webviewFetch = object : TwoArgFunction() {
        override fun call(url: LuaValue, opts: LuaValue): LuaValue {
            val fetcher = webviewFetcher ?: return LuaValue.NIL
            val target = url.checkjstring()
            val options = opts.takeIf { it.istable() }?.checktable()
            val rawClick = options?.get("click")
            val click = when {
                rawClick == null || rawClick.isnil() -> null
                // LuaJ reports isnumber()=true for numeric strings; a number is
                // not a selector, so require the real string type.
                rawClick.type() == LuaValue.TSTRING -> WebviewClickSelector.extract(rawClick.tojstring())
                    ?: return LuaValue.NIL
                // A function/table/number is not a selector: no fetch.
                else -> return LuaValue.NIL
            }
            val expect = options?.get("expect")?.takeIf { it.isstring() }?.tojstring()
            val html = try {
                runBlocking {
                    withTimeout(defaultTimeoutMs) {
                        fetcher.fetch(target, click, expect, defaultTimeoutMs)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            return html?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL
        }
    }

    // GET/HEAD; returns the NoveLA-shaped response table (or error table).
    private suspend fun doRequest(target: String, options: RequestOptions): LuaValue {
        val startedAt = System.currentTimeMillis()
        return try {
            val response = withBridgeTimeout(options) {
                client.get(
                    sourceId = sourceId,
                    url = target,
                    headers = options.headers,
                    timeoutMs = options.timeoutMs,
                    followRedirects = options.followRedirects,
                    method = options.method,
                )
            }
            successMap(target, response, options, startedAt)
        } catch (e: TimeoutCancellationException) {
            logFailure(target, e, startedAt)
            markFailure(target, e)
            errorMap(e)
        } catch (e: CancellationException) {
            // Caller went away: never turn cancellation into a plugin result.
            throw e
        } catch (e: Exception) {
            logFailure(target, e, startedAt)
            markFailure(target, e)
            errorMap(e)
        }
    }

    // POST; Content-Type comes from the plugin headers or is sniffed from the body.
    private suspend fun doPost(target: String, body: String, options: RequestOptions): LuaValue {
        val startedAt = System.currentTimeMillis()
        return try {
            val contentType = options.headers.entries
                .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
                ?.value
                ?: detectContentType(body)
            val headers = options.headers.filterKeys { !it.equals(HttpHeaders.ContentType, ignoreCase = true) }
            val response = withBridgeTimeout(options) {
                client.post(
                    sourceId = sourceId,
                    url = target,
                    body = body,
                    headers = headers,
                    // Like http_get: the plugin's timeout option is the whole-call
                    // budget (NoveLA callWithTimeout path).
                    timeoutMs = options.timeoutMs,
                    followRedirects = options.followRedirects,
                    contentType = contentType,
                )
            }
            successMap(target, response, options, startedAt)
        } catch (e: TimeoutCancellationException) {
            logFailure(target, e, startedAt)
            markFailure(target, e)
            errorMap(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(target, e, startedAt)
            markFailure(target, e)
            errorMap(e)
        }
    }

    // Suspends the Lua worker only for as long as the call is allowed to run.
    // The timer must wrap the ACTUAL request (not the runBlocking boundary):
    // coroutine cancellation then unwinds Ktor, runBlocking returns, and the
    // session worker / LuaPluginSource mutex is free for the next call. An
    // explicit plugin timeout is left untouched — PluginHttpClient already
    // arms the same timer for it (BudgetedCall), including retry semantics.
    private suspend fun <T> withBridgeTimeout(options: RequestOptions, block: suspend () -> T): T {
        if (options.timeoutMs != null) return block()
        try {
            return withTimeout(defaultTimeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            // A concurrent outer cancellation must win over our own timer:
            // helpers such as migration's withTimeout throw the same type.
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private data class RequestOptions(
        val headers: Map<String, String> = emptyMap(),
        val charset: String? = null,
        val binary: Boolean = false,
        val method: String = "GET",
        val followRedirects: Boolean = true,
        val timeoutMs: Long? = null,
    )

    private data class BatchItem(val url: String, val options: RequestOptions)

    // Reads one config table, overlaying it on the batch-wide defaults.
    private fun parseOptions(tag: String, table: LuaTable?, base: RequestOptions = RequestOptions()): RequestOptions {
        if (table == null) return base
        val timeoutValue = table.get("timeout")
        val timeoutMs = if (timeoutValue.isnil()) {
            base.timeoutMs
        } else {
            timeoutValue.takeIf { it.isnumber() }?.tolong()?.takeIf { it > 0 }
        }
        return RequestOptions(
            headers = base.headers + parseHeaders(table),
            charset = table.get("charset").takeIf { it.isstring() }?.tojstring()?.takeIf { it.isNotBlank() }
                ?: base.charset,
            binary = table.get("binary").optboolean(base.binary),
            method = checkMethod(tag, table.get("method").takeIf { it.isstring() }?.tojstring() ?: base.method),
            followRedirects = table.get("followRedirects").optboolean(base.followRedirects),
            timeoutMs = timeoutMs,
        )
    }

    private fun parseBatchItem(value: LuaValue, base: RequestOptions): BatchItem {
        // type(): LuaJ reports isnumber()=true for numeric strings, and a bare
        // number must be rejected as an item rather than coerced to a URL.
        if (value.type() == LuaValue.TSTRING) return BatchItem(value.checkjstring(), base)
        if (!value.istable()) {
            throw IllegalArgumentException(
                "http_get_batch: item must be a URL string or a table with \"url\", got ${value.typename()}",
            )
        }
        val table = value.checktable()
        val url = table.get("url").optjstring(null)
            ?: throw IllegalArgumentException("http_get_batch: item table is missing the required \"url\" field")
        return BatchItem(url, parseOptions("http_get_batch", table, base))
    }

    // GET/HEAD only; anything else is a plugin bug and must fail before any network call.
    private fun checkMethod(fnName: String, raw: String): String {
        val method = raw.uppercase()
        require(method == "GET" || method == "HEAD") {
            "$fnName: unsupported method \"$raw\", allowed: GET, HEAD"
        }
        return method
    }

    private fun parseHeaders(options: LuaTable?): Map<String, String> {
        val table = options?.get("headers")?.takeIf { it.istable() }?.checktable() ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in table.keys()) {
            result[key.checkjstring()] = table.get(key).checkjstring()
        }
        return result
    }

    private suspend fun successMap(
        target: String,
        response: HttpResponse,
        options: RequestOptions,
        startedAt: Long,
    ): LuaValue {
        val status = response.status.value
        val bytes = response.bodyAsBytes()
        if (status in 200..299) {
            failureFlag.markSuccess()
        } else {
            val (scheme, host, path) = hostPathOf(target)
            failureFlag.markFailure(
                status = status,
                reason = response.status.description,
                host = host,
                path = path,
                scheme = scheme,
            )
        }
        // Charset: explicit option wins, otherwise the plugin's declared
        // charset global, otherwise Content-Type, otherwise UTF-8.
        val charset = options.charset ?: pluginCharset(defaultCharset, response.headers[HttpHeaders.ContentType], null)
        logNet(
            "event=net method=${options.method} url=${safeUrl(target)} status=$status " +
                "bytes=${bytes.size} charset=$charset" +
                (if (options.charset != null) " explicit" else "") +
                " pluginSuccess=${status in 200..299} elapsed=${System.currentTimeMillis() - startedAt}ms",
        )

        val table = LuaTable()
        table.set("success", LuaValue.valueOf(status in 200..299))
        table.set("code", status)
        table.set("status", status) // host-facing alias
        table.set("charset", charset) // host-facing alias

        val headersTable = LuaTable()
        for (name in response.headers.names()) {
            val values = LuaTable()
            response.headers.getAll(name).orEmpty().forEachIndexed { i, value -> values.set(i + 1, value) }
            headersTable.set(name.lowercase(), values)
        }
        table.set("headers", headersTable)

        if (options.binary) {
            val bodyTable = LuaTable()
            bytes.forEachIndexed { i, byte -> bodyTable.set(i + 1, LuaValue.valueOf(byte.toInt() and 0xFF)) }
            table.set("body", bodyTable)
        } else {
            table.set("body", if (options.method == "HEAD") "" else decode(bytes, charset))
        }
        return table
    }

    private fun decode(bytes: ByteArray, charset: String): String =
        try {
            String(bytes, Charset.forName(charset))
        } catch (e: Exception) {
            bytes.decodeToString()
        }

    private fun logNet(message: String) {
        logger?.d("net", "source=$sourceId $message")
    }

    // Debug logs never contain full URLs, query strings, bodies or headers.
    private fun safeUrl(url: String): String = try {
        val uri = java.net.URI(url)
        "${uri.host ?: ""}${uri.path ?: ""}"
    } catch (_: Exception) {
        url.substringBefore('?').take(120)
    }

    // Error class only: a Ktor exception message may embed the full URL.
    private fun logFailure(target: String, e: Exception, startedAt: Long) {
        logNet(
            "event=net url=${safeUrl(target)} error=${e::class.simpleName} " +
                "pluginSuccess=false elapsed=${System.currentTimeMillis() - startedAt}ms",
        )
    }

    // A CF challenge keeps its typed fields (host/url/referer/status) so the UI
    // can offer Solve for the same operation; every other transport failure is
    // a PluginError.Network with the mapped class and scheme+host+path.
    private fun markFailure(target: String, e: Exception) {
        if (e is CfChallengeRequiredException) {
            failureFlag.markFailure(e.error)
            return
        }
        val (scheme, host, path) = hostPathOf(target)
        failureFlag.markFailure(
            PluginError.Network(
                cause = errorTypeOf(e),
                host = host,
                path = path,
                scheme = scheme,
            ),
        )
    }

    private fun hostPathOf(url: String): Triple<String?, String?, String?> = try {
        val uri = java.net.URI(url)
        Triple(uri.scheme, uri.host, uri.path)
    } catch (_: Exception) {
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
            .takeIf { "://" in url }
        val raw = url.substringBefore('?').substringBefore('#').substringAfter("://")
        Triple(scheme, raw.substringBefore('/'), null)
    }

    private fun errorMap(e: Exception): LuaValue {
        val message = e.message ?: e::class.simpleName ?: "request failed"
        val table = LuaTable()
        table.set("success", LuaValue.FALSE)
        table.set("body", message)
        table.set("code", -1)
        // NoveLA HttpGetBatchFunction.withHeadersFallback (LuaSourceLoader.kt:
        // 894-898): every batch slot carries headers, so error slots get an
        // empty table instead of a missing field.
        table.set("headers", LuaTable())
        table.set("error", message) // host-facing alias
        table.set("errorType", errorTypeOf(e)) // host-facing alias
        return table
    }

    private fun errorTypeOf(e: Exception): String = when (e) {
        is SsrfBlockedException -> "SsrfBlocked"
        is DnsFailureException -> "DnsFailure"
        is CfChallengeRequiredException -> "CfChallengeRequired"
        is ResponseTooLargeException -> "ResponseTooLarge"
        is HttpRequestTimeoutException, is TimeoutCancellationException -> "Timeout"
        else -> "Network"
    }

    private fun detectContentType(body: String): String =
        if (body.trimStart().firstOrNull() in listOf('{', '[')) "application/json"
        else "application/x-www-form-urlencoded"
}
