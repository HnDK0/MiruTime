package dev.mirutime.translator.internal

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class KeyedHttpExecutor(
    private val keys: KeyRotator,
    private val provider: ProviderId,
    private val maxAttempts: Int,
    private val keyCooldown: Boolean = true,
    /** Maps endpoint-specific failure statuses to terminal typed errors. */
    private val mapFailure: ((status: Int, body: String) -> TranslationError?)? = null,
) {
    suspend fun <T> execute(
        request: suspend (key: String?) -> HttpResponse,
        parse: suspend (HttpResponse) -> T,
    ): T {
        var lastError: TranslationError? = null
        var attempt = 0

        while (attempt < maxAttempts.coerceAtLeast(1)) {
            val key = keys.next()
            if (key == null) {
                lastError = lastError ?: TranslationError.Auth(provider, "no usable API key")
                break
            }

            val response = try {
                request(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationFailure) {
                // A transport preflight already named the cause; retrying it
                // would turn an actionable error into a generic network one.
                if (e.preflight) throw e
                // Provider timeouts surface as typed failures too; keep the
                // existing network backoff/retry for them.
                lastError = e.error
                attempt++
                if (attempt < maxAttempts) delay(backoff(attempt))
                continue
            } catch (e: Throwable) {
                // Network errors are not key-specific; back off and retry with the same key.
                lastError = TranslationError.Network(provider, e.message)
                attempt++
                if (attempt < maxAttempts) delay(backoff(attempt))
                continue
            }

            if (response.status.isSuccess()) {
                keys.reset(key)
                try {
                    return parse(response)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TranslationFailure) {
                    if (e.error !is TranslationError.Truncated) throw e
                    lastError = e.error
                } catch (e: Throwable) {
                    throw TranslationFailure(
                        TranslationError.InvalidResponse(provider, e.message ?: "parse failed"),
                    )
                }

                attempt++
                if (attempt < maxAttempts) delay(backoff(attempt))
                continue
            }

            val rawBody = response.bodyAsText().take(2_000)
            val body = failureDetail(rawBody)
            mapFailure?.invoke(response.status.value, rawBody)?.let { throw TranslationFailure(it) }
            var retryImmediately = false
            when (response.status.value) {
                400 -> {
                    if (isInvalidKey(rawBody)) {
                        if (keyCooldown) keys.coolDown(key, AUTH_COOLDOWN)
                        lastError = TranslationError.Auth(provider, body)
                    } else {
                        throw TranslationFailure(
                            TranslationError.InvalidRequest(
                                provider,
                                "HTTP 400: $body",
                            ),
                        )
                    }
                }

                401, 403 -> {
                    if (isQuotaBody(rawBody)) {
                        throw TranslationFailure(TranslationError.Quota(provider, body))
                    }
                    if (keyCooldown) keys.coolDown(key, AUTH_COOLDOWN)
                    lastError = TranslationError.Auth(provider, body)
                }

                429 -> {
                    if (isQuotaBody(rawBody)) {
                        throw TranslationFailure(TranslationError.Quota(provider, body))
                    }
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]
                        ?.toLongOrNull()
                        ?.seconds
                    lastError = TranslationError.RateLimited(provider, retryAfter, body)
                    if (retryAfter != null && keyCooldown) {
                        // Rotate to a healthy key immediately; only wait when this is the
                        // last usable key. Never keep it cooling longer than the capped wait.
                        val hasAnotherKey = keys.availableCount() > 1
                        keys.coolDown(
                            key,
                            if (hasAnotherKey) retryAfter else retryAfter.coerceAtMost(MAX_RETRY_DELAY),
                        )
                        retryImmediately = hasAnotherKey
                    }
                }

                in 500..599 -> {
                    lastError = TranslationError.Server(provider, response.status.value, body)
                }

                // DeepL signals an exhausted character quota with 456.
                456 -> throw TranslationFailure(TranslationError.Quota(provider, body))

                else -> {
                    throw TranslationFailure(
                        TranslationError.InvalidRequest(
                            provider,
                            "HTTP ${response.status.value}: $body",
                        ),
                    )
                }
            }

            attempt++
            if (attempt >= maxAttempts) break
            if (retryImmediately) continue
            delay(retryDelay(lastError, attempt))
        }

        throw TranslationFailure(lastError ?: TranslationError.Auth(provider, "no usable API key"))
    }

    private fun retryDelay(error: TranslationError?, attempt: Int): Duration =
        when (error) {
            is TranslationError.RateLimited ->
                error.retryAfter?.coerceAtMost(MAX_RETRY_DELAY) ?: backoff(attempt)
            else -> backoff(attempt)
        }

    private fun isInvalidKey(body: String): Boolean =
        INVALID_KEY_MARKERS.any { body.contains(it, ignoreCase = true) }

    private fun backoff(attempt: Int): Duration =
        (250L * (1 shl (attempt.coerceIn(1, 4) - 1))).milliseconds

    private companion object {
        val AUTH_COOLDOWN = 1.hours
        val MAX_RETRY_DELAY = 30.seconds
        val INVALID_KEY_MARKERS = listOf(
            "api_key_invalid",
            "api key not valid",
            "invalid api key",
            "permission_denied",
        )
    }
}
