package dev.mirutime.core.network.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SsrfGuardTest {

    private fun resolvedGuard(vararg addresses: String) =
        SsrfGuard { addresses.toList() }

    private val throwingResolver = SsrfGuard { _ -> throw IllegalStateException("dns down") }

    // URL-only checks are decided locally — a resolver must never be consulted.
    private val noDnsNeeded = SsrfGuard { host -> error("resolver must not be called for $host") }

    @Test
    fun blockedLiteralTable() {
        val blocked = listOf(
            // brief block-list
            "http://localhost/page",
            "http://127.0.0.1/",
            "http://10.1.2.3/",
            "http://172.16.0.1/",
            "http://172.31.255.255/", // /12 upper bound
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/", // cloud metadata
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[ff02::1]/", // IPv6 multicast
            "ftp://example.com/", // non-http scheme
            // extensions beyond the brief (report documents these)
            "http://0.0.0.0/", // 0.0.0.0/8 any
            "http://[::]/", // ::/128 any
            "http://[fe80::1]/", // IPv6 link-local
            "http://[::ffff:127.0.0.1]/", // IPv4-mapped loopback
        )
        for (url in blocked) {
            val verdict = noDnsNeeded.isSafe(url)
            assertIs<SsrfVerdict.Blocked>(verdict, "expected Blocked for $url, got $verdict")
        }
    }

    // The old failure: a pre-flight DNS lookup returned DnsFailure and blocked
    // a working request. URL-only validation accepts the hostname and lets the
    // engine's SafeDns hook make the address decision.
    @Test
    fun hostnameIsAcceptedWithoutDns() {
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("https://example.com/page"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("https://ghost.example.com/page"))
    }

    @Test
    fun publicLiteralIpIsOk() {
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://93.184.216.34/"))
    }

    @Test
    fun credentialsInUrlAreBlocked() {
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://user:pass@example.com/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://user@example.com/"))
    }

    // A non-default port is not an address decision: the plugin engine's
    // SafeDns still validates the resolved address, so the URL rules only
    // cover scheme/userinfo/host literals (NoveLA has no port rule either).
    @Test
    fun nonDefaultPortsAreAllowed() {
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://example.com:8080/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("https://example.com:8443/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://example.com:443/"))
    }

    @Test
    fun defaultPortsAreAllowed() {
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://example.com:80/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("https://example.com:443/"))
    }

    @Test
    fun publicHostWithPublicResolutionIsOk() {
        assertEquals(
            SsrfVerdict.Ok,
            resolvedGuard("93.184.216.34").isSafeResolved("https://example.com/page"),
        )
    }

    @Test
    fun mixedPublicAndPrivateResolutionIsBlocked() {
        val verdict = resolvedGuard("93.184.216.34", "10.0.0.1")
            .isSafeResolved("https://mixed.example.com/")
        assertIs<SsrfVerdict.Blocked>(verdict)
    }

    @Test
    fun privateOnlyResolutionIsBlocked() {
        val verdict = resolvedGuard("192.168.0.10").isSafeResolved("https://lan.example.com/")
        assertIs<SsrfVerdict.Blocked>(verdict)
    }

    @Test
    fun emptyResolutionIsDnsFailure() {
        assertEquals(
            SsrfVerdict.DnsFailure,
            resolvedGuard().isSafeResolved("https://ghost.example.com/"),
        )
    }

    @Test
    fun resolverExceptionIsDnsFailure() {
        assertEquals(
            SsrfVerdict.DnsFailure,
            throwingResolver.isSafeResolved("https://broken.example.com/"),
        )
    }

    @Test
    fun boundaryAddressesRespectCidrEdges() {
        // Just outside each private range must stay public (Ok).
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://11.255.255.255/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://172.15.255.255/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://172.32.0.0/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://192.169.0.0/"))
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://169.253.0.1/"))
        // Inside each edge must be blocked.
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://10.255.255.255/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://172.16.0.0/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://172.31.255.255/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://192.168.255.255/"))
    }

    @Test
    fun leadingZeroOctetsAreBlocked() {
        // Native inet_aton-style parsers read "012" as octal (10.x) — an
        // octet with a leading '0' must never be treated as decimal.
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://012.1.1.1/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://10.0.0.01/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://0177.0.0.1/"))
    }

    @Test
    fun ipv4CompatibleIpv6IsCheckedWithV4Rules() {
        // ::a.b.c.d (::/96 without ffff) — deprecated but still parsed by
        // stacks as the embedded IPv4 address.
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://[::10.0.0.1]/"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://[::127.0.0.1]/"))
        // Public embedded v4 stays Ok.
        assertEquals(SsrfVerdict.Ok, noDnsNeeded.isSafe("http://[::93.184.216.34]/"))
    }

    @Test
    fun trailingDotIsNormalized() {
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://localhost./"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://127.0.0.1./"))
        assertIs<SsrfVerdict.Blocked>(noDnsNeeded.isSafe("http://169.254.169.254./"))
    }
}
