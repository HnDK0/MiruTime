package dev.mirutime.core.network.security

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

// The local translation engine opts out of the address gate; every other
// engine keeps it. IP literals keep this test off the network: the system
// resolver returns them directly, while SafeDns still classifies them.
class EngineDnsSelectionTest {

    @Test
    fun localEngineDnsResolvesPrivateLiterals() {
        val dns = createEngineDns(safeDns = false)
        for (address in listOf("10.0.2.2", "192.168.1.10", "127.0.0.1")) {
            assertEquals(listOf(address), dns.lookup(address).map { it.hostAddress })
        }
    }

    @Test
    fun localEngineDnsCanResolveLocalhost() {
        val dns = createEngineDns(safeDns = false)
        assertTrue(dns.lookup("localhost").any { it.isLoopbackAddress })
    }

    @Test
    fun localEngineDnsConnectsToLoopbackServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = OkHttpClient.Builder().dns(createEngineDns(safeDns = false)).build()
            val url = "http://127.0.0.1:${server.address.port}/"
            val body = client.newCall(Request.Builder().url(url).build()).execute().use {
                it.body.string()
            }
            assertEquals("ok", body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun pluginEngineDnsStillFiltersPrivateAddresses() {
        val dns = createEngineDns(safeDns = true)
        assertIs<SafeDns>(dns)
        for (address in listOf("10.0.2.2", "192.168.1.10", "127.0.0.1")) {
            assertFailsWith<UnknownHostException>("expected $address to be rejected") {
                dns.lookup(address)
            }
        }
    }
}
