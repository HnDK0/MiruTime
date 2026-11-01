package dev.mirutime.core.network

import dev.mirutime.core.network.cf.CloudflareDetector
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.discardRemaining
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import kotlinx.coroutines.delay

// Set by PluginHttpClient when the call runs under a whole-call budget
// (timeoutMs): NoveLA's BudgetedCall does exactly ONE attempt — no backoff —
// so the budget can never be spent sleeping between retries.
internal val BudgetedCallAttributeKey = AttributeKey<Boolean>("MiruTime.BudgetedCall")

// Optional debug sink, installed by PluginHttpClient: `event=retry` lines only,
// never the URL/headers. Null costs nothing.
internal val RetryLogAttributeKey = AttributeKey<(String) -> Unit>("MiruTime.RetryLog")

// Host-side cap for a server-provided Retry-After. A site may answer 429 with
// an hour; sleeping that long inside one user call is not a retry, it is a hang.
internal const val MAX_RETRY_AFTER_MS: Long = 5_000

// Retries transient server errors (429/502/503/504) with exponential backoff.
// Parity with NoveLA's ServerErrorRetryInterceptor: GET-only, maxRetries = 4,
// 500ms base backoff. Installed on the per-plugin client in PluginHttpClient;
// internal because it is that client's plumbing, not a consumer-facing plugin.
internal val ServerErrorRetryInterceptor = createClientPlugin("ServerErrorRetryInterceptor") {
    val maxRetries = 4
    val initialBackoffMs = 500L

    on(Send) { request ->
        // Only idempotent GETs are replayed — a POST body may not be replayable.
        if (request.method != HttpMethod.Get) return@on proceed(request)
        // Budgeted call: one attempt, never backoff (NoveLA BudgetedCall).
        if (request.attributes.getOrNull(BudgetedCallAttributeKey) == true) {
            return@on proceed(request)
        }
        val deadline = request.attributes.getOrNull(CallDeadlineAttributeKey) ?: NO_DEADLINE
        val log = request.attributes.getOrNull(RetryLogAttributeKey)

        var call = proceed(request)
        var attempt = 0
        while (attempt < maxRetries && shouldRetry(call.response)) {
            val status = call.response.status.value
            // Close the failed response before re-sending (frees the connection).
            call.response.discardRemaining()
            // Retry-After (integer seconds) is the server's own rate-limit hint;
            // HTTP-date values are ignored rather than guessed.
            val retryAfterMs = parseRetryAfterMs(call.response.headers[HttpHeaders.RetryAfter])
            // Sleep at most what the whole-call deadline still allows; an
            // already-spent deadline yields a <=0 delay and throws below
            // instead of waking up late.
            val delayMs = minOf(
                retryAfterMs ?: (initialBackoffMs * (1L shl attempt)),
                MAX_RETRY_AFTER_MS,
                deadline.remainingMs(),
            )
            log?.invoke(
                "event=retry status=$status attempt=${attempt + 1} delayMs=$delayMs " +
                    "retryAfter=${if (retryAfterMs != null) "yes" else "no"} capMs=$MAX_RETRY_AFTER_MS",
            )
            delay(delayMs)
            attempt++
            deadline.throwIfExpired(request.url.toString())
            call = proceed(request)
        }
        call
    }
}

// A CF challenge is NOT a transient server error: backoff-retrying it burns the
// whole attempt budget against the challenge endpoint and delays the detector
// and solver, which only ever see the final response. NoveLA keeps its CF
// interceptor OUTSIDE the retry layer for the same reason — so a challenge is
// solved on attempt 1 here too, instead of after ~7.5s of pointless retries.
// The header check runs BEFORE the status check: a 429 carrying
// `cf-mitigated: challenge` is a browser check, while a bare 429 (even behind
// `Server: cloudflare`) is the origin's rate limit and must retry normally.
// ceiling: only headers are readable at this point — consuming the body would
// break streaming — so a challenge identified by body markers alone, or by a
// plugin's custom cfOptions.statuses, still retries. CloudflareDetector stays
// the authority and takes over the moment this returns the response.
private fun shouldRetry(response: HttpResponse): Boolean {
    if (CloudflareDetector.isChallengeByHeaders(
            status = response.status.value,
            serverHeader = response.headers[HttpHeaders.Server],
            cfMitigatedHeader = response.headers[CF_MITIGATED],
        )
    ) {
        return false
    }
    return response.status.value in RETRYABLE_CODES
}

// Retry-After is either delta-seconds or an HTTP-date. Only the numeric form is
// honored; a date would need a wall-clock parse and the server can always send
// seconds instead.
internal fun parseRetryAfterMs(value: String?): Long? =
    value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1_000 }

// 429 = origin rate limit, retried as transient; 502/503/504 =
// transient CDN errors. CF-marked responses never reach here (see shouldRetry).
private val RETRYABLE_CODES = setOf(429, 502, 503, 504)
