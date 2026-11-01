package dev.mirutime.core.network

import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.host.FakeCookieStore
import dev.mirutime.core.host.FakeSystemInfo
import dev.mirutime.core.host.RecordingLogger
import dev.mirutime.core.network.cf.CfOptions
import dev.mirutime.core.network.cf.CloudflareDetector
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// CF detection must not decode a body it does not need: the metadata/URL
// branches decide first, and a client that cannot solve never scans a success
// body at all.
class PluginHttpClientCfBodyTest {

    private val detector = CloudflareDetector()

    private val metadata = PluginMetadata(
        id = "dev.mirutime.demo",
        name = "Demo",
        baseUrl = "https://example.com",
        contentType = ContentType.NOVEL,
        language = "ru",
        api = 1,
        version = "0.1.0",
        sha256 = "abc",
    )

    private fun client(engine: MockEngine, logger: RecordingLogger) = PluginHttpClient(
        metadata = metadata,
        systemInfo = FakeSystemInfo(),
        cookieStore = FakeCookieStore(),
        logger = logger,
        engine = engine,
    )

    private fun cfLine(logger: RecordingLogger): String = logger.lines.single { "event=cf" in it }

    @Test
    fun metadataDecidedChallengeNeverReadsTheBody() {
        var reads = 0
        val body: () -> String = {
            reads++
            "<html>cf-challenge</html>"
        }

        assertTrue(detector.isChallenge(403, body, null, cfMitigatedHeader = "challenge"))
        assertEquals(0, reads, "cf-mitigated decides before any marker scan")

        assertEquals(
            "cf-mitigated",
            detector.challengeReason(403, body, null, cfMitigatedHeader = "challenge"),
        )
        assertEquals(0, reads)
    }

    @Test
    fun markerOnlyChallengeReadsTheBodyOncePerCall() {
        var reads = 0
        val body: () -> String = {
            reads++
            "<html>cf-challenge here</html>"
        }

        assertTrue(
            detector.isChallenge(
                403,
                body,
                // A configured trigger forces a second scan pass; the lazy
                // provider must still be called only once.
                CfOptions(triggerMarkers = listOf("waf-block")),
            ),
        )
        assertEquals(1, reads)
    }

    @Test
    fun largeNonUtf8SuccessOnANonSolvingClientIsNotScanned() = runTest {
        // A marker sits at the front of a 20 MB non-UTF8 body: any scan would
        // see it and report a challenge. solveChallenges=false returns a
        // challenge as-is, so its success bodies are never decoded.
        val body = "cf-challenge".toByteArray() + ByteArray(20 * 1024 * 1024) { 0xFF.toByte() }
        val lines = RecordingLogger()
        val engine = MockEngine { respond(body) }

        val response = client(engine, lines)
            .get("demo", "https://example.com/image/1", solveChallenges = false)

        assertEquals(200, response.status.value)
        assertEquals(body.size.toLong(), response.bodyAsBytes().size.toLong())
        val cf = cfLine(lines)
        assertTrue("verdict=none" in cf, "the marker must not have been scanned: $cf")
    }

    @Test
    fun cfMitigatedIsDetectedOnA2xxWithoutABody() = runTest {
        val lines = RecordingLogger()
        val engine = MockEngine {
            respond(
                content = "cf-challenge",
                status = HttpStatusCode.OK,
                headers = headersOf(CF_MITIGATED, "challenge"),
            )
        }

        val response = client(engine, lines)
            .get("demo", "https://example.com/page", solveChallenges = false)

        assertEquals(200, response.status.value)
        val cf = cfLine(lines)
        assertTrue("verdict=challenge" in cf, cf)
        assertTrue("trigger=cf-mitigated" in cf, cf)
    }

    @Test
    fun markerOnly403IsDetectedForANonSolvingClient() = runTest {
        val lines = RecordingLogger()
        val engine = MockEngine {
            respond("<html>cf-challenge</html>", HttpStatusCode.Forbidden)
        }

        val response = client(engine, lines)
            .get("demo", "https://example.com/page", solveChallenges = false)

        assertEquals(403, response.status.value)
        val cf = cfLine(lines)
        assertTrue("verdict=challenge" in cf, cf)
        assertTrue("trigger=marker" in cf, cf)
    }
}
