package dev.mirutime.core.network.security

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import okhttp3.Dns

class SafeDnsTest {

    private fun delegate(vararg addresses: String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            addresses.map(InetAddress::getByName)
    }

    private fun dns(vararg addresses: String) = SafeDns(delegate(*addresses))

    @Test
    fun publicAddressesPassThroughUnchanged() {
        val addresses = listOf("93.184.216.34", "2606:4700:4700::1111")
        assertEquals(addresses.map(InetAddress::getByName), dns(*addresses.toTypedArray()).lookup("example.com"))
    }

    @Test
    fun blockedAddressFamiliesAreRejected() {
        val blocked = listOf(
            "127.0.0.1", // loopback
            "10.0.0.1", // private
            "172.16.0.1", // private
            "192.168.1.1", // private
            "169.254.169.254", // link-local / metadata
            "224.0.0.1", // multicast
            "0.0.0.0", // unspecified
            "::1", // IPv6 loopback
            "::", // IPv6 unspecified
            "fc00::1", // ULA
            "fd12:3456::1", // ULA
            "fe80::1", // link-local
            "ff02::1", // multicast
        )
        for (address in blocked) {
            assertFailsWith<UnknownHostException>("expected $address to be rejected") {
                dns(address).lookup("example.com")
            }
        }
    }

    @Test
    fun mixedPublicAndPrivateAnswersAreRejected() {
        assertFailsWith<UnknownHostException> {
            dns("93.184.216.34", "10.0.0.1").lookup("mixed.example.com")
        }
    }

    @Test
    fun emptyAnswerIsAHostFailure() {
        assertFailsWith<UnknownHostException> {
            dns().lookup("ghost.example.com")
        }
    }

    // createEngine (Android) must install this wrapper, not the raw resolver.
    @Test
    fun engineDnsFactoryReturnsSafeWrapper() {
        assertIs<SafeDns>(createSafeDns())
    }

    private fun hostResolver(vararg addresses: String) = object : HostResolver {
        override fun resolve(host: String): List<String> = addresses.toList()
    }

    @Test
    fun checkedResolverPassesPublicAddressesUnchanged() {
        val resolver = CheckedHostResolver(hostResolver("93.184.216.34", "2606:4700:4700::1111"))
        assertEquals(
            listOf("93.184.216.34", "2606:4700:4700::1111"),
            resolver.resolve("example.com"),
        )
    }

    @Test
    fun checkedResolverRejectsPrivateAndMixedAnswers() {
        for (blocked in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "fe80::1")) {
            assertFailsWith<UnknownHostException>("expected $blocked to be rejected") {
                CheckedHostResolver(hostResolver(blocked)).resolve("example.com")
            }
        }
        assertFailsWith<UnknownHostException> {
            CheckedHostResolver(hostResolver("93.184.216.34", "10.0.0.1")).resolve("mixed.example.com")
        }
    }

    @Test
    fun checkedResolverFailsClosedOnAnEmptyAnswer() {
        assertFailsWith<UnknownHostException> {
            CheckedHostResolver(hostResolver()).resolve("ghost.example.com")
        }
    }
}
