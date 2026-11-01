package dev.mirutime.imageloader

import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PluginImageNetworkClientTest {
    private fun request(url: String = "https://img.example/a.webp") = NetworkRequest(url = url)

    @Test
    fun `2xx forwards bytes and headers`() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val client = PluginImageNetworkClient { _, _ ->
            FetchedImage(
                status = 200,
                bytes = bytes,
                headers = mapOf("Content-Type" to "image/webp", "ETag" to "\"v1\""),
            )
        }

        val result = client.executeRequest(request()) { response ->
            val buffer = Buffer()
            response.body!!.writeTo(buffer)
            Triple(response.code, response.headers["content-type"], buffer.readByteArray())
        }

        assertEquals(200, result.first)
        assertEquals("image/webp", result.second)
        assertArrayEquals(bytes, result.third)
    }

    @Test
    fun `non-2xx surfaces the real code with an empty body`() = runTest {
        val client = PluginImageNetworkClient { _, _ -> FetchedImage(status = 404) }

        val seen = client.executeRequest(request()) { response -> response.code to response.body }

        assertEquals(404, seen.first)
        assertNull(seen.second)
    }

    @Test
    fun `transport failure throws IOException`() = runTest {
        val client = PluginImageNetworkClient { _, _ -> FetchedImage(status = 0) }

        try {
            client.executeRequest(request()) { it.code }
            fail("expected IOException")
        } catch (_: IOException) {
            // Coil treats this as a network error, not a cached image.
        }
    }

    @Test
    fun `request url and headers reach the fetch`() = runTest {
        var seen: Pair<String, Map<String, String>>? = null
        val client = PluginImageNetworkClient { url, headers ->
            seen = url to headers
            FetchedImage(status = 200, bytes = byteArrayOf(1))
        }
        val headers = NetworkHeaders.Builder().set("X-Test", "yes").build()

        client.executeRequest(
            NetworkRequest(url = "https://img.example/a.webp", headers = headers),
        ) { it.code }

        assertEquals("https://img.example/a.webp", seen!!.first)
        assertEquals("yes", seen!!.second["x-test"])
    }
}
