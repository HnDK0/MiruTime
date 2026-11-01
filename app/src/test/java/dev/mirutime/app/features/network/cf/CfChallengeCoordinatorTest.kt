package dev.mirutime.app.features.network.cf

import dev.mirutime.core.host.CfChallenge
import dev.mirutime.core.host.CfSolution
import dev.mirutime.core.host.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CfChallengeCoordinatorTest {

    private val logger = RecordingLogger()

    private fun coordinator(
        ui: CfChallengeUi,
        probe: CfTargetProbe = CfTargetProbe { _, _, _ -> true },
        timeoutMs: Long = 5_000,
    ) = CfChallengeCoordinator(
        ui = ui,
        probe = probe,
        logger = logger,
        timeoutMs = timeoutMs,
    )

    private fun challenge(url: String = "https://example.com/book") = CfChallenge(
        host = "example.com",
        url = url,
        status = 403,
        markers = listOf("cf-challenge"),
    )

    @Test
    fun `only one challenge screen is open at a time`() = runTest {
        val first = FakeSession(cookies = mapOf("cf_clearance" to "one")).apply {
            loads += true
        }
        val second = FakeSession(cookies = mapOf("cf_clearance" to "two")).apply {
            gate.complete(Unit)
            loads += true
        }
        val ui = FakeUi(first, second)
        val subject = coordinator(ui)

        val job1 = async { subject.solve(challenge()) }
        runCurrent()
        assertEquals(1, ui.openCount)

        val job2 = async { subject.solve(challenge("https://example.com/other")) }
        runCurrent()
        assertEquals(1, ui.openCount)
        assertEquals(1, ui.maxActive)

        first.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("one", job1.await()?.cookies?.get("cf_clearance"))
        assertEquals("two", job2.await()?.cookies?.get("cf_clearance"))
        assertEquals(2, ui.openCount)
        assertEquals(1, ui.maxActive)
    }

    @Test
    fun `timeout returns null and closes the screen`() = runTest {
        val session = FakeSession(cookies = mapOf("cf_clearance" to "x"))
        val subject = coordinator(FakeUi(session), timeoutMs = 1_000)

        val result = async { subject.solve(challenge()) }
        runCurrent()
        advanceUntilIdle()

        assertNull(result.await())
        assertTrue(session.closeCalled)
        assertTrue(logger.lines.any { "timeout" in it })
    }

    @Test
    fun `user cancel returns null`() = runTest {
        val session = FakeSession(cookies = emptyMap()).apply {
            gate.complete(Unit)
            // A page load that reports "closed" models Back/Cancel.
            loads += false
        }
        val subject = coordinator(FakeUi(session))

        assertNull(subject.solve(challenge()))
        assertTrue(session.closeCalled)
        assertTrue(logger.lines.any { "cancelled" in it })
    }

    @Test
    fun `non-http navigation url never opens the screen`() = runTest {
        val ui = FakeUi(FakeSession(cookies = mapOf("cf_clearance" to "x")))
        val subject = coordinator(ui)

        val result = subject.solve(
            CfChallenge(
                host = "example.com",
                url = "https://example.com/api/items",
                status = 403,
                markers = emptyList(),
                // An XHR challenge navigates to the Referer: a file: target
                // must never reach the WebView.
                referer = "file:///etc/passwd",
                isXhr = true,
            ),
        )

        assertNull(result)
        assertEquals(0, ui.openCount)
        assertTrue(logger.lines.any { "unsafe-url" in it })
    }

    @Test
    fun `private literal navigation target is allowed`() = runTest {
        val session = FakeSession(cookies = mapOf("cf_clearance" to "x")).apply {
            gate.complete(Unit)
            loads += true
        }
        val ui = FakeUi(session)
        val subject = coordinator(ui)

        val result = subject.solve(challenge("http://192.168.1.10:8080/book"))

        assertNotNull(result)
        assertEquals(1, ui.openCount)
    }

    @Test
    fun `solution carries webview cookies and user agent`() = runTest {
        val session = FakeSession(
            cookies = mapOf("cf_clearance" to "clear-value", "locale" to "en"),
            ua = "TestWebView/1.0",
        ).apply {
            gate.complete(Unit)
            loads += true
        }
        val subject = coordinator(FakeUi(session))

        val solution = subject.solve(challenge())

        assertEquals(
            CfSolution(
                cookies = mapOf("cf_clearance" to "clear-value", "locale" to "en"),
                userAgent = "TestWebView/1.0",
            ),
            solution,
        )
    }

    @Test
    fun `challenged probe is not reported as solved`() = runTest {
        val session = FakeSession(cookies = mapOf("cf_clearance" to "x")).apply {
            gate.complete(Unit)
            loads += true
            loads += true
        }
        val answers = ArrayDeque(listOf(false, true))
        val subject = coordinator(
            FakeUi(session),
            probe = CfTargetProbe { _, _, _ -> answers.removeFirst() },
        )

        val solution = subject.solve(challenge())

        assertEquals("x", solution?.cookies?.get("cf_clearance"))
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `probe receives navigate url not request url`() = runTest {
        val session = FakeSession(cookies = mapOf("cf_clearance" to "x")).apply {
            gate.complete(Unit)
            loads += true
        }
        var probeUrl: String? = null
        val subject = coordinator(
            FakeUi(session),
            probe = CfTargetProbe { targetUrl, _, _ ->
                probeUrl = targetUrl
                true
            },
        )

        // XHR challenge: the page that actually has a JS context is the
        // Referer. Probing the bare search URL proves nothing (it answers with
        // the challenge by definition).
        val solution = subject.solve(
            CfChallenge(
                host = "example.com",
                url = "https://example.com/search",
                status = 403,
                markers = emptyList(),
                referer = "https://example.com/browse",
                isXhr = true,
            ),
        )

        assertEquals("https://example.com/browse", probeUrl)
        assertEquals("x", solution?.cookies?.get("cf_clearance"))
    }

    @Test
    fun `logs never contain cookie values or user agent`() = runTest {
        val session = FakeSession(
            cookies = mapOf("cf_clearance" to "super-secret-clearance"),
            ua = "SecretBrowser/9.9",
        ).apply {
            gate.complete(Unit)
            loads += true
        }
        val subject = coordinator(FakeUi(session))

        subject.solve(challenge())

        val log = logger.lines.joinToString("\n")
        assertFalse(log.contains("super-secret-clearance"))
        assertFalse(log.contains("SecretBrowser/9.9"))
        assertTrue(log.contains("host=example.com"))
    }

    @Test
    fun `ui failure is reported as unsolved instead of crashing`() = runTest {
        val ui = object : CfChallengeUi {
            override suspend fun open(request: CfChallenge, navigateUrl: String): CfChallengeSession =
                throw IllegalStateException("boom")
        }
        val subject = coordinator(ui)

        assertNull(subject.solve(challenge()))
        assertTrue(logger.lines.any { "failed" in it })
    }

    private class FakeUi(vararg sessions: FakeSession) : CfChallengeUi {
        private val queue = ArrayDeque(sessions.toList())
        var openCount = 0
        var active = 0
        var maxActive = 0

        override suspend fun open(request: CfChallenge, navigateUrl: String): CfChallengeSession? {
            openCount++
            active++
            maxActive = maxOf(maxActive, active)
            val session = queue.removeFirstOrNull() ?: return null
            session.onClosed = { active-- }
            return session
        }
    }

    private class FakeSession(
        private val cookies: Map<String, String>,
        private val ua: String? = "UA",
    ) : CfChallengeSession {
        val gate = CompletableDeferred<Unit>()
        val loads = ArrayDeque<Boolean>()
        var closeCalled = false
        var onClosed: (() -> Unit)? = null

        override val userAgent: String?
            get() = ua

        override suspend fun awaitPageLoad(): Boolean {
            gate.await()
            return loads.removeFirstOrNull() ?: false
        }

        override fun cookies(): Map<String, String> = cookies

        override fun close() {
            closeCalled = true
            onClosed?.invoke()
        }
    }

    private class RecordingLogger : Logger {
        val lines = mutableListOf<String>()

        override fun d(tag: String, msg: String) {
            lines += "$tag $msg"
        }

        override fun w(tag: String, msg: String) {
            lines += "$tag $msg"
        }

        override fun e(tag: String, msg: String, t: Throwable?) {
            lines += "$tag $msg"
        }
    }
}
