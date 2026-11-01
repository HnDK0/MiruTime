package dev.mirutime.app.features.video

import com.sun.net.httpserver.HttpServer
import dev.mirutime.app.features.video.data.MediaHttp
import dev.mirutime.app.features.video.data.VideoVariantProbe
import dev.mirutime.app.features.video.domain.VideoVariantStatus
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

class VideoVariantProbeTest {

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    private fun serve(path: String, code: Int, cfMitigated: String? = null) {
        server.createContext(path) { exchange ->
            if (cfMitigated != null) exchange.responseHeaders.add("Cf-Mitigated", cfMitigated)
            val body = "body-$code".toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `4xx except 408 and 429 proves deadness`() {
        listOf(403, 404, 410).forEach {
            assertTrue(it.toString(), VideoVariantProbe.isDeadHttpCode(it))
        }
        listOf(500, 503, 408, 429, 301).forEach {
            assertFalse(it.toString(), VideoVariantProbe.isDeadHttpCode(it))
        }
    }

    @Test
    fun `only unknown host proves deadness of a failure`() {
        assertTrue(VideoVariantProbe.isDeadFailure(UnknownHostException("gone.example")))
        assertFalse(VideoVariantProbe.isDeadFailure(SocketTimeoutException("timeout")))
        assertTrue(
            VideoVariantProbe.isDeadFailure(
                IOException("connect failed", UnknownHostException("gone.example")),
            ),
        )
    }

    @Test
    fun `200 is OK`() {
        serve("/video.mp4", code = 200)

        val status = runBlocking {
            VideoVariantProbe.probe(url("/video.mp4"), emptyMap(), OkHttpClient())
        }

        assertEquals(VideoVariantStatus.OK, status)
    }

    @Test
    fun `403 is DEAD`() {
        serve("/video.mp4", code = 403)

        val status = runBlocking {
            VideoVariantProbe.probe(url("/video.mp4"), emptyMap(), OkHttpClient())
        }

        assertEquals(VideoVariantStatus.DEAD, status)
    }

    @Test
    fun `5xx is UNKNOWN`() {
        serve("/video.mp4", code = 503)

        val status = runBlocking {
            VideoVariantProbe.probe(url("/video.mp4"), emptyMap(), OkHttpClient())
        }

        assertEquals(VideoVariantStatus.UNKNOWN, status)
    }

    @Test
    fun `429 is UNKNOWN`() {
        serve("/video.mp4", code = 429)

        val status = runBlocking {
            VideoVariantProbe.probe(url("/video.mp4"), emptyMap(), OkHttpClient())
        }

        assertEquals(VideoVariantStatus.UNKNOWN, status)
    }

    @Test
    fun `cf-mitigated challenge is UNKNOWN not DEAD`() {
        serve("/video.mp4", code = 403, cfMitigated = "challenge")

        val status = runBlocking {
            VideoVariantProbe.probe(url("/video.mp4"), emptyMap(), OkHttpClient())
        }

        assertEquals(VideoVariantStatus.UNKNOWN, status)
    }

    @Test
    fun `media client uses system DNS with 15s connect and 30s read`() {
        val client = MediaHttp.client

        assertSame(Dns.SYSTEM, client.dns)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
    }

    // A DNS failure is a dead host, not a buffering player; the probe maps it
    // to DEAD regardless of the client's resolver.
    @Test
    fun `dns-blocked host is DEAD`() {
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    throw UnknownHostException("SSRF blocked: 127.0.0.1")
            })
            .build()

        val status = runBlocking {
            VideoVariantProbe.probe("https://blocked.example/video.mp4", emptyMap(), client)
        }

        assertEquals(VideoVariantStatus.DEAD, status)
    }

    @Test
    fun `probe sends the stream headers`() {        serve("/video.mp4", code = 200)
        val seenReferer = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    seenReferer.set(chain.request().header("Referer"))
                    chain.proceed(chain.request())
                },
            )
            .build()

        val status = runBlocking {
            VideoVariantProbe.probe(
                url = url("/video.mp4"),
                headers = mapOf("Referer" to "https://site/show/1"),
                client = client,
            )
        }

        assertEquals(VideoVariantStatus.OK, status)
        assertEquals("https://site/show/1", seenReferer.get())
    }
}
