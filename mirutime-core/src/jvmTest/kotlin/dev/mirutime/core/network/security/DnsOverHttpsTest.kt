package dev.mirutime.core.network.security

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DnsOverHttpsTest {

    private var now = 1_000_000L

    private fun resolver(
        transport: DohTransport,
        system: (String) -> List<InetAddress> = { throw UnknownHostException(it) },
    ) = DnsOverHttps(transport = transport, systemLookup = system, clock = { now })

    private class FakeTransport(private val body: (DohProvider) -> String?) : DohTransport {
        val providers = mutableListOf<String>()

        override fun query(provider: DohProvider, hostname: String): String? {
            providers += provider.name
            return body(provider)
        }
    }

    @Test
    fun `system result wins and is cached`() {
        var systemCalls = 0
        val transport = FakeTransport { error("DoH must not run") }
        val dns = resolver(transport) {
            systemCalls++
            listOf(InetAddress.getByName("203.0.113.10"))
        }

        assertEquals("203.0.113.10", dns.lookup("example.com").single().hostAddress)
        assertEquals("203.0.113.10", dns.lookup("example.com").single().hostAddress)
        assertEquals(1, systemCalls)
        assertTrue(transport.providers.isEmpty())
    }

    @Test
    fun `system failure falls back to cloudflare and parses only A records`() {
        val transport = FakeTransport {
            dohJson(
                5 to "alias.example.com.",
                1 to "93.184.216.34",
                28 to "2606:2800:220:1:248:1893:25c8:1946",
            )
        }
        val dns = resolver(transport)

        val resolved = dns.lookup("example.com")
        assertEquals(listOf("93.184.216.34"), resolved.map { it.hostAddress })
        assertEquals(listOf("Cloudflare"), transport.providers)
    }

    @Test
    fun `doh answer is cached`() {
        val transport = FakeTransport { dohJson(1 to "93.184.216.34") }
        val dns = resolver(transport)

        dns.lookup("example.com")
        dns.lookup("example.com")
        assertEquals(listOf("Cloudflare"), transport.providers)
    }

    @Test
    fun `cache expires after ttl`() {
        val transport = FakeTransport { dohJson(1 to "93.184.216.34") }
        val dns = resolver(transport)

        dns.lookup("example.com")
        now += 5 * 60_000L
        dns.lookup("example.com")
        assertEquals(listOf("Cloudflare", "Cloudflare"), transport.providers)
    }

    @Test
    fun `second provider is used when the first fails`() {
        val transport = FakeTransport { provider ->
            if (provider.name == "Google") dohJson(1 to "8.8.4.4") else null
        }
        val dns = resolver(transport)

        assertEquals("8.8.4.4", dns.lookup("example.com").single().hostAddress)
        assertEquals(listOf("Cloudflare", "Google"), transport.providers)
    }

    @Test
    fun `empty or malformed doh answers fall through to the next provider`() {
        val transport = FakeTransport { provider ->
            when (provider.name) {
                "Cloudflare" -> "not json"
                else -> dohJson(1 to "8.8.4.4")
            }
        }
        val dns = resolver(transport)

        assertEquals("8.8.4.4", dns.lookup("example.com").single().hostAddress)
        assertEquals(listOf("Cloudflare", "Google"), transport.providers)
    }

    @Test
    fun `negative cache short-circuits and retries after ttl`() {
        val transport = FakeTransport { null }
        val dns = resolver(transport)
        val providerCount = DEFAULT_DOH_PROVIDERS.size

        assertFailsWith<UnknownHostException> { dns.lookup("dead.example") }
        assertEquals(DEFAULT_DOH_PROVIDERS.map { it.name }, transport.providers)

        // Inside the negative window: no system lookup, no DoH.
        assertFailsWith<UnknownHostException> { dns.lookup("dead.example") }
        assertEquals(providerCount, transport.providers.size)

        now += 60_001L
        assertFailsWith<UnknownHostException> { dns.lookup("dead.example") }
        assertEquals(providerCount * 2, transport.providers.size)
    }

    @Test
    fun `host resolver adapter returns host addresses`() {
        val dns = resolver(FakeTransport { dohJson(1 to "93.184.216.34") })
        assertEquals(listOf("93.184.216.34"), dns.resolve("example.com"))
    }

    private fun dohJson(vararg records: Pair<Int, String>): String {
        val answers = records.joinToString(",") { (type, data) ->
            """{"name":"example.com.","type":$type,"TTL":60,"data":"$data"}"""
        }
        return """{"Status":0,"Answer":[$answers]}"""
    }
}
