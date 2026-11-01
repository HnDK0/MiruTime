package dev.mirutime.app.features.updates.data

import dev.mirutime.app.core.RemoteBytes
import dev.mirutime.core.log.MiruLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The repository shown in About is also the release source. */
object AppRelease {
    const val RepoUrl = "https://github.com/HnDK0/MiruTime"
    const val LatestReleaseApi = "https://api.github.com/repos/HnDK0/MiruTime/releases/latest"
    const val ReleasesUrl = "$RepoUrl/releases"
}

sealed interface AppUpdateResult {
    data class Available(
        val version: String,
        val url: String,
        val name: String?,
    ) : AppUpdateResult

    data class UpToDate(val version: String) : AppUpdateResult
    data object NoReleases : AppUpdateResult
    data class Error(val message: String) : AppUpdateResult
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

internal object AppUpdateParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(body: String): GitHubRelease? =
        runCatching { json.decodeFromString<GitHubRelease>(body) }
            .getOrNull()
            ?.takeIf { it.tagName.isNotBlank() && it.htmlUrl.isNotBlank() }
}

/**
 * Dotted numeric comparison with a prerelease suffix. `v` is optional; missing
 * components count as zero (`0.1` == `0.1.0`); `1.0.0-beta` < `1.0.0`.
 */
class AppVersion private constructor(
    val parts: List<Int>,
    val prerelease: String?,
) {
    operator fun compareTo(other: AppVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (index in 0 until size) {
            val left = parts.getOrElse(index) { 0 }
            val right = other.parts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return when {
            prerelease == null && other.prerelease == null -> 0
            prerelease == null -> 1
            other.prerelease == null -> -1
            else -> prerelease.compareTo(other.prerelease)
        }
    }

    companion object {
        fun parse(raw: String): AppVersion? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isBlank()) return null
            val dash = trimmed.indexOf('-')
            val core = if (dash >= 0) trimmed.substring(0, dash) else trimmed
            val prerelease = if (dash >= 0) {
                trimmed.substring(dash + 1).takeIf { it.isNotBlank() }
            } else {
                null
            }
            val parts = core.split('.').map { token ->
                val digits = token.takeWhile(Char::isDigit)
                if (digits.isEmpty()) return null
                digits.toIntOrNull() ?: return null
            }
            if (parts.isEmpty()) return null
            return AppVersion(parts, prerelease)
        }

        fun isNewer(latest: String, current: String): Boolean {
            val latestVersion = parse(latest) ?: return false
            val currentVersion = parse(current) ?: return false
            return latestVersion > currentVersion
        }
    }
}

/** One release-API request over the app's plain engine (redirects followed). */
fun interface AppReleaseFetch {
    suspend fun fetch(url: String, headers: Map<String, String>, timeoutMs: Long): RemoteBytes
}

/**
 * Real GitHub release check. A network/API failure is an error, never
 * "you are on the latest version"; a 404 is the honest "no releases" state.
 */
class AppUpdateChecker(
    private val fetch: AppReleaseFetch,
    private val apiUrl: String = AppRelease.LatestReleaseApi,
    private val timeoutMs: Long = 15_000,
) {
    suspend fun check(currentVersion: String): AppUpdateResult {
        val response = try {
            fetch.fetch(
                apiUrl,
                mapOf(
                    "Accept" to "application/vnd.github+json",
                    "User-Agent" to "MiruTime/$currentVersion",
                ),
                timeoutMs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The raw message contains the full API URL: log the host only.
            MiruLog.w(
                "updates",
                "app release check failed host=${updateHost()} class=${e::class.simpleName}",
            )
            return AppUpdateResult.Error(e.message ?: "Network error")
        }
        return when (val code = response.status) {
            404 -> {
                MiruLog.i("updates", "app release check: no releases published")
                AppUpdateResult.NoReleases
            }
            in 200..299 -> {
                val release = AppUpdateParser.parse(response.bytes.decodeToString())
                    ?: return AppUpdateResult.Error("Unreadable release response")
                val tag = release.tagName
                if (AppVersion.isNewer(tag, currentVersion)) {
                    MiruLog.i("updates", "app release available tag=$tag current=$currentVersion")
                    AppUpdateResult.Available(
                        version = tag,
                        url = release.htmlUrl,
                        name = release.name,
                    )
                } else {
                    MiruLog.i("updates", "app release current tag=$tag current=$currentVersion")
                    AppUpdateResult.UpToDate(tag)
                }
            }
            else -> {
                MiruLog.w("updates", "app release check HTTP $code")
                AppUpdateResult.Error("GitHub returned HTTP $code")
            }
        }
    }

    private fun updateHost(): String =
        runCatching { java.net.URI(apiUrl).host }.getOrNull() ?: "unknown"
}
