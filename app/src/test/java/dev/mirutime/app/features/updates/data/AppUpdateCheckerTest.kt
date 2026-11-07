package dev.mirutime.app.features.updates.data

import dev.mirutime.app.core.RemoteBytes
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    private val releaseJson = """
        {"tag_name":"v0.2.0","name":"0.2.0","html_url":"https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0"}
    """.trimIndent()

    private fun response(status: Int, body: String = "") = RemoteBytes(
        status = status,
        bytes = body.encodeToByteArray(),
        headers = emptyMap(),
        finalUrl = AppRelease.LatestReleaseApi,
        location = null,
    )

    private fun checker(
        fetch: suspend (String, Map<String, String>, Long) -> RemoteBytes,
    ) = AppUpdateChecker(AppReleaseFetch { url, headers, timeoutMs -> fetch(url, headers, timeoutMs) })

    @Test
    fun `newer release is available`() = runBlocking {
        val result = checker { _, _, _ -> response(200, releaseJson) }.check("0.1.0")

        assertEquals(
            AppUpdateResult.Available("v0.2.0", "https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0", "0.2.0"),
            result,
        )
    }

    @Test
    fun `same release is up to date`() = runBlocking {
        val result = checker { _, _, _ -> response(200, releaseJson) }.check("0.2.0")

        assertEquals(AppUpdateResult.UpToDate("v0.2.0"), result)
    }

    @Test
    fun `404 means no releases`() = runBlocking {
        assertEquals(AppUpdateResult.NoReleases, checker { _, _, _ -> response(404) }.check("0.1.0"))
    }

    @Test
    fun `5xx is an error with the status`() = runBlocking {
        val result = checker { _, _, _ -> response(503) }.check("0.1.0")

        assertEquals(AppUpdateResult.Error("GitHub returned HTTP 503"), result)
    }

    @Test
    fun `unreadable body is an error`() = runBlocking {
        val result = checker { _, _, _ -> response(200, "not json") }.check("0.1.0")

        assertEquals(AppUpdateResult.Error("Unreadable release response"), result)
    }

    @Test
    fun `thrown fetch failure is an error`() = runBlocking {
        val result = checker { _, _, _ -> throw IOException("offline") }.check("0.1.0")

        assertEquals(AppUpdateResult.Error("offline"), result)
    }

    @Test
    fun `request keeps the release headers and the 15 second budget`() = runBlocking {
        var seenUrl: String? = null
        var seenHeaders: Map<String, String>? = null
        var seenTimeout: Long? = null
        checker { url, headers, timeoutMs ->
            seenUrl = url
            seenHeaders = headers
            seenTimeout = timeoutMs
            response(200, releaseJson)
        }.check("0.1.0")

        assertEquals(AppRelease.LatestReleaseApi, seenUrl)
        assertEquals("application/vnd.github+json", seenHeaders?.get("Accept"))
        assertEquals("MiruTime/0.1.0", seenHeaders?.get("User-Agent"))
        assertEquals(15_000L, seenTimeout)
    }
    @Test
    fun `release json keeps tag and browser url`() {
        val release = AppUpdateParser.parse(
            """
            {
              "tag_name": "v0.2.0",
              "name": "0.2.0",
              "html_url": "https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0"
            }
            """.trimIndent(),
        )

        assertEquals("v0.2.0", release?.tagName)
        assertEquals("https://github.com/HnDK0/MiruTime/releases/tag/v0.2.0", release?.htmlUrl)
    }

    @Test
    fun `release body is parsed for the notification`() = runBlocking {
        val body = """{"tag_name":"v0.2.0","html_url":"https://example.test/r","body":"## Added\n- Fixed playback"}"""
        val result = checker { _, _, _ -> response(200, body) }.check("0.1.0")

        assertEquals(
            "## Added\n- Fixed playback",
            (result as AppUpdateResult.Available).body,
        )
    }

    @Test
    fun `release notes are plain text and capped`() {
        assertEquals(
            "Added playback\nFixed export",
            appUpdateReleaseNotes("## Added playback\n\n- Fixed export"),
        )
        assertEquals("abc", appUpdateReleaseNotes("abc", maxChars = 3))
        assertEquals("", appUpdateReleaseNotes(null))
    }

    @Test
    fun `missing or broken payload is not a release`() {
        assertNull(AppUpdateParser.parse("{}"))
        assertNull(AppUpdateParser.parse("not json"))
        assertNull(AppUpdateParser.parse("""{"tag_name":"","html_url":""}"""))
    }

    @Test
    fun `version compare ignores the v prefix and trailing zeroes`() {
        assertTrue(AppVersion.isNewer("v0.2", "0.1"))
        assertTrue(AppVersion.isNewer("0.1.1", "0.1"))
        assertFalse(AppVersion.isNewer("v0.1", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1", "0.1"))
        assertTrue(AppVersion.isNewer("1.10", "1.9"))
    }

    @Test
    fun `prerelease sorts before the final release`() {
        assertTrue(AppVersion.isNewer("1.0.0", "1.0.0-beta.1"))
        assertFalse(AppVersion.isNewer("1.0.0-beta.1", "1.0.0"))
        assertTrue(AppVersion.isNewer("1.0.0-beta.2", "1.0.0-beta.1"))
    }

    @Test
    fun `invalid versions never claim an update`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("abc"))
        assertFalse(AppVersion.isNewer("abc", "0.1"))
        assertFalse(AppVersion.isNewer("0.2", "abc"))
    }
}
