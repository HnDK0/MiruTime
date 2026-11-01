package dev.mirutime.translator

import dev.mirutime.core.api.MiruError
import kotlin.time.Duration

sealed interface TranslationError : MiruError {
    data class Auth(
        val provider: ProviderId,
        val message: String? = null,
    ) : TranslationError

    data class RateLimited(
        val provider: ProviderId,
        val retryAfter: Duration? = null,
        val message: String? = null,
    ) : TranslationError

    data class Quota(
        val provider: ProviderId,
        val message: String? = null,
    ) : TranslationError

    data class InvalidRequest(
        val provider: ProviderId?,
        val message: String,
    ) : TranslationError

    data class ContentBlocked(
        val provider: ProviderId,
        val finishReason: String? = null,
    ) : TranslationError

    data class Truncated(
        val provider: ProviderId,
        val expected: Int,
        val actual: Int,
    ) : TranslationError

    data class InvalidResponse(
        val provider: ProviderId,
        val message: String,
    ) : TranslationError

    data class Network(
        val provider: ProviderId,
        val message: String? = null,
    ) : TranslationError

    data class Server(
        val provider: ProviderId,
        val status: Int,
        val message: String? = null,
    ) : TranslationError

    /** Android 17+ local-network grant is missing for a private endpoint. */
    data class LocalNetworkRequired(
        val provider: ProviderId,
    ) : TranslationError

    /** Plain HTTP is allowed only for a local-network endpoint. */
    data class CleartextNotAllowed(
        val provider: ProviderId,
    ) : TranslationError

    data object Cancelled : TranslationError
}

internal class TranslationFailure(
    val error: TranslationError,
    // True when a transport preflight rejected the request before any I/O.
    // Unlike a provider failure, an executor must not retry it as network.
    val preflight: Boolean = false,
) : Exception(error.toString())
