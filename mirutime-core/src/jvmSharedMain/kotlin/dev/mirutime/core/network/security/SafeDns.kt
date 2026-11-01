package dev.mirutime.core.network.security

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

// Connection-time SSRF gate for the OkHttp engine. The URL guard cannot see
// the addresses a hostname resolves to; OkHttp asks this Dns right before it
// opens a socket and then connects to exactly the returned addresses, so a
// private/loopback answer is rejected with no DNS-rebinding window between
// check and connect (IP pinning).
internal class SafeDns(
    private val delegate: Dns,
    private val log: (String) -> Unit = {},
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        checkAddresses(
            hostname,
            addresses.map {
                it.hostAddress
                    ?: throw UnknownHostException("Unparseable address for host \"$hostname\"")
            },
            log,
        )
        return addresses
    }
}

// One address gate for both transports: SafeDns validates before OkHttp opens
// the socket, CheckedHostResolver validates the list a caller will pin.
internal fun checkAddresses(
    hostname: String,
    addresses: List<String>,
    log: (String) -> Unit = {},
) {
    if (addresses.isEmpty()) {
        throw UnknownHostException("Unable to resolve host \"$hostname\"")
    }
    for (address in addresses) {
        val reason = SsrfAddressClassifier.blockReason(address) ?: continue
        log("event=dns_blocked host=${hostname.lowercase()}")
        throw UnknownHostException("SSRF blocked: $reason")
    }
}

// Public seam for transports that build their own client (repo/index fetch,
// video probe/downloader): the returned addresses are already checked, so the
// caller can pin them into the connection instead of re-resolving at connect.
fun createCheckedHostResolver(log: (String) -> Unit = {}): HostResolver =
    CheckedHostResolver(DnsOverHttps(log = log), log)

internal class CheckedHostResolver(
    private val delegate: HostResolver,
    private val log: (String) -> Unit = {},
) : HostResolver {
    override fun resolve(host: String): List<String> =
        delegate.resolve(host).also { checkAddresses(host, it, log) }
}

// One DnsOverHttps instance wrapped once: the engine and every request share
// the same cache, and no lookup path can bypass the address validation.
internal fun createSafeDns(log: (String) -> Unit = {}): Dns = SafeDns(DnsOverHttps(log = log), log)

// Engine DNS selection. The plugin/install/repo/video engine uses the gate; the
// local translation engine falls back to the plain system resolver so a
// user-configured Ollama/LM Studio host (10.0.2.2, 192.168.x, loopback) works.
// Not a global disable: callers must opt out explicitly.
internal fun createEngineDns(safeDns: Boolean, log: (String) -> Unit = {}): Dns =
    if (safeDns) createSafeDns(log) else Dns.SYSTEM
