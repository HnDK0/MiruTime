package dev.mirutime.app.features.translation.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {
    @Test
    fun `loopback is local`() {
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://127.0.0.1:11434/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://127.8.9.10/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://localhost:18080/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://LOCALHOST/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://[::1]:8080/v1"))
    }

    @Test
    fun `private ipv4 ranges are local`() {
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://10.0.2.2:18080/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://10.255.255.255/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://172.16.0.1/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://172.31.255.255/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://192.168.1.10/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("https://ollama.local:11434/v1"))
    }

    @Test
    fun `link-local ipv4 is local`() {
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://169.254.1.1/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://169.254.255.254/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://169.255.1.1/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://168.254.1.1/v1"))
    }

    @Test
    fun `ipv6 link-local and unique-local ranges are local`() {
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://[fe80::1]/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://[febf::abcd]/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://[fc00::1]/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://[fd12:3456::1]/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://[fec0::1]/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://[2001:4860:4860::8888]/v1"))
    }

    @Test
    fun `ipv4-mapped ipv6 literals follow the ipv4 rule`() {
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://[::ffff:192.168.1.10]/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://[::ffff:127.0.0.1]/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://[::ffff:8.8.8.8]/v1"))
    }

    @Test
    fun `lan names beyond local are local`() {
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://ollama.lan:11434/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://nas.home.arpa/v1"))
        assertTrue(LocalNetworkAccess.isLocalEndpoint("http://llm.internal/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("https://internal.example.com/v1"))
    }

    @Test
    fun `tailscale range stays a documented exclusion`() {
        // 100.64/10 also covers carrier-grade NAT; enabling it needs a decision.
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://100.64.0.1/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://100.127.255.254/v1"))
    }

    @Test
    fun `public and malformed hosts are not local`() {
        assertFalse(LocalNetworkAccess.isLocalEndpoint("https://api.openai.com/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("https://8.8.8.8/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://172.15.0.1/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://172.32.0.1/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://192.169.1.1/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://11.0.0.1/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("http://300.1.1.1/v1"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint("not a url"))
        assertFalse(LocalNetworkAccess.isLocalEndpoint(""))
    }

    @Test
    fun `cleartext is allowed only for a local endpoint`() {
        assertFalse(LocalNetworkAccess.isCleartextAllowed("http://api.example.com/v1"))
        assertTrue(LocalNetworkAccess.isCleartextAllowed("http://10.0.2.2:18080/v1"))
        assertTrue(LocalNetworkAccess.isCleartextAllowed("http://169.254.1.1/v1"))
        assertTrue(LocalNetworkAccess.isCleartextAllowed("https://api.example.com/v1"))
        assertTrue(LocalNetworkAccess.isCleartextAllowed(" https://localhost/v1 "))
        assertTrue(LocalNetworkAccess.isCleartextAllowed(""))
    }

    @Test
    fun `cleartext fails closed on an unparsable url`() {
        // Ktor can normalize these into a real request, so never treat the
        // failed parse as "not http" and let the endpoint through.
        assertFalse(LocalNetworkAccess.isCleartextAllowed("http:\\api.example.com\\v1"))
        assertFalse(LocalNetworkAccess.isCleartextAllowed("http://api.example.com\\v1"))
        assertFalse(LocalNetworkAccess.isCleartextAllowed("not a url"))
        assertFalse(LocalNetworkAccess.isCleartextAllowed("http://[::1"))
    }

    @Test
    fun `grant is required only on android 17 for a local endpoint`() {
        assertTrue(LocalNetworkAccess.isRequired("http://10.0.2.2:18080/v1", 37, granted = false))
        assertFalse(LocalNetworkAccess.isRequired("http://10.0.2.2:18080/v1", 37, granted = true))
        assertFalse(LocalNetworkAccess.isRequired("http://10.0.2.2:18080/v1", 36, granted = false))
        assertFalse(LocalNetworkAccess.isRequired("https://api.openai.com/v1", 37, granted = false))
    }
}
