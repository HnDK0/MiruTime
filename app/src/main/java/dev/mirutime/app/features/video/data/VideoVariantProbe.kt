package dev.mirutime.app.features.video.data

import dev.mirutime.app.features.video.domain.VideoVariantStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * One-shot liveness check before playback. An expired CDN URL otherwise shows
 * up only as endless buffering. The result feeds the variant chooser badge;
 * there is no cache inside — the screen owns the per-URL status map.
 *
 * The probe is a plain GET with the same per-stream headers the player uses.
 * It never opens a WebView: a Cloudflare challenge is a reason to leave the
 * status UNKNOWN, not to take over the player with a CF flow.
 */
object VideoVariantProbe {

    private const val ALIVE_TIMEOUT_MS = 8_000L

    private val defaultClient: OkHttpClient = MediaHttp.client

    suspend fun probe(
        url: String,
        headers: Map<String, String> = emptyMap(),
        client: OkHttpClient = defaultClient,
    ): VideoVariantStatus = withContext(Dispatchers.IO) {
        when (okHttpCall(url, headers, client, ALIVE_TIMEOUT_MS)) {
            is CallResult.Ok -> VideoVariantStatus.OK
            CallResult.Dead -> VideoVariantStatus.DEAD
            CallResult.Unreliable -> VideoVariantStatus.UNKNOWN
        }
    }

    private sealed interface CallResult<out T> {
        data class Ok<T>(val value: T) : CallResult<T>

        data object Dead : CallResult<Nothing>

        data object Unreliable : CallResult<Nothing>
    }

    /**
     * 2xx proves the URL is alive; 4xx (except 408/429) proves it is dead.
     * A cf-mitigated challenge is not evidence of death. Every other failure
     * is UNKNOWN: no proof either way.
     */
    private fun okHttpCall(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        timeoutMs: Long,
    ): CallResult<Unit> = try {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .build()
        client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                when {
                    response.header("cf-mitigated")?.equals("challenge", ignoreCase = true) == true ->
                        CallResult.Unreliable

                    response.isSuccessful -> CallResult.Ok(Unit)
                    isDeadHttpCode(response.code) -> CallResult.Dead
                    else -> CallResult.Unreliable
                }
            }
    } catch (e: Exception) {
        if (isDeadFailure(e)) CallResult.Dead else CallResult.Unreliable
    }

    /** 4xx proves the URL is dead; 408 (server timeout) and 429 (rate limit) do not. */
    internal fun isDeadHttpCode(code: Int): Boolean =
        code in 400..499 && code != 408 && code != 429

    /** Only DNS failure proves death; a timeout or reset is a temporary failure. */
    internal fun isDeadFailure(e: Exception): Boolean {
        var current: Throwable? = e
        var steps = 0
        while (current != null && steps < 4) {
            if (current is UnknownHostException) return true
            current = current.cause
            steps++
        }
        return false
    }
}
