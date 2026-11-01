package dev.mirutime.core.network.security

import io.ktor.http.Url

// SSRF check verdict. Ok = safe to request; Blocked = refused with reason;
// DnsFailure = hostname could not be resolved (only the explicit pre-connect
// variant uses this; it is a network failure, not an SSRF verdict).
sealed class SsrfVerdict {
    data object Ok : SsrfVerdict()
    data class Blocked(val reason: String) : SsrfVerdict()
    data object DnsFailure : SsrfVerdict()
}

// DNS resolution is injected only for surfaces that cannot install a
// connection-time hook (WebView/CF). Plugin HTTP validates the URL only.
fun interface HostResolver {
    fun resolve(host: String): List<String>
}

// PluginError is a data hierarchy, not Throwable — this bridges SSRF
// failures out of suspend call sites (precedent: CfChallengeRequiredException).
class SsrfBlockedException(val reason: String) :
    IllegalStateException("SSRF blocked: $reason")

// Kept for callers that still map a pre-connect DNS failure; the plugin path
// no longer produces it (the engine's DNS hook reports a normal network error).
class DnsFailureException(val host: String) :
    IllegalStateException("DNS resolution failed for host=$host")

// URL-level SSRF guard: scheme allow-list, credentials, localhost and
// literal-IP block-list checks. Ports are unrestricted: the transport policy is
// scheme/userinfo only (TransportUrlPolicy), and a non-default port is not an
// address decision. No DNS is performed here — the
// Android engine's SafeDns validates the addresses it is about to connect to
// (IP pinning), which closes the DNS-rebinding/TOCTOU gap. The JVM engine (CIO)
// has no DNS hook, so on JVM this guard is URL-only and not an address gate.
// [isSafeResolved] is the pre-connect variant for surfaces that have no DNS
// hook (CF probe/WebView); it is deliberately NOT used by plugin HTTP.
class SsrfGuard(private val resolver: HostResolver? = null) {

    // URL-only: never touches DNS. A hostname that is not a literal IP is
    // accepted here and validated by the connection-time DNS hook.
    fun isSafe(url: String): SsrfVerdict {
        val parsed = parse(url) ?: return SsrfVerdict.Blocked("malformed url")
        return urlVerdict(parsed)
    }

    // URL rules plus resolved-address rules. Fails closed on DNS failures:
    // CF/WebView have no SafeDns equivalent, so an unresolved host must not be
    // opened blindly. null resolver = DnsFailure (fail closed).
    fun isSafeResolved(url: String): SsrfVerdict {
        val parsed = parse(url) ?: return SsrfVerdict.Blocked("malformed url")
        val urlVerdict = urlVerdict(parsed)
        if (urlVerdict !is SsrfVerdict.Ok) return urlVerdict
        val host = normalizedHost(parsed)
        // Literal addresses were fully decided by urlVerdict.
        if (SsrfAddressClassifier.isIpLiteral(host)) return SsrfVerdict.Ok
        val resolved = try {
            resolver?.resolve(host).orEmpty()
        } catch (e: Exception) {
            return SsrfVerdict.DnsFailure
        }
        if (resolved.isEmpty()) return SsrfVerdict.DnsFailure
        for (address in resolved) {
            val reason = SsrfAddressClassifier.blockReason(address)
                ?: continue
            // fail-closed: any blocked address rejects the URL, even when a
            // public one is also present (mixed public+private -> Blocked).
            return SsrfVerdict.Blocked("resolved $address: $reason")
        }
        return SsrfVerdict.Ok
    }

    private fun parse(url: String): Url? = try {
        Url(url)
    } catch (e: Exception) {
        null
    }

    private fun urlVerdict(parsed: Url): SsrfVerdict {
        transportViolation(parsed)?.let { return SsrfVerdict.Blocked(it) }
        val host = normalizedHost(parsed)
        if (host.isEmpty()) return SsrfVerdict.Blocked("empty host")
        if (host == "localhost" || host.endsWith(".localhost")) {
            return SsrfVerdict.Blocked("localhost")
        }
        // Literal IP — decide locally, no DNS involved.
        SsrfAddressClassifier.parseIpv4(host)?.let { octets ->
            SsrfAddressClassifier.blockedV4Reason(octets)?.let { return SsrfVerdict.Blocked(it) }
            return SsrfVerdict.Ok
        }
        // Four purely numeric labels that failed strict parsing (leading-zero
        // octet, out-of-range value) are ambiguous IPv4 literals — fail
        // closed, no DNS fallthrough.
        if (host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }) {
            return SsrfVerdict.Blocked("ambiguous ipv4 literal '$host'")
        }
        if (':' in host) {
            // Parseable or not, an IPv6-shaped host is a literal: block on the
            // classifier's reason, otherwise it is a public literal.
            val reason = SsrfAddressClassifier.blockReason(host)
            return reason?.let { SsrfVerdict.Blocked(it) } ?: SsrfVerdict.Ok
        }
        // Hostname: URL-level rules passed; address checks happen at connect.
        return SsrfVerdict.Ok
    }

    private fun normalizedHost(parsed: Url): String =
        // ktor keeps IPv6 literals bracketed — strip for parsing; a trailing
        // FQDN dot ("localhost.", "127.0.0.1.") is not meaningful here.
        parsed.host.trim('[', ']').removeSuffix(".").lowercase()
}

// Shared address classifier: URL literal checks and SafeDns use the same rules,
// so an address can never be public in one layer and private in the other.
internal object SsrfAddressClassifier {

    // Non-null = blocked, null = public literal. An unparseable address is
    // blocked (fail closed) rather than silently accepted.
    fun blockReason(address: String): String? {
        val a = address.trim('[', ']').removeSuffix(".").lowercase()
        parseIpv4(a)?.let { return blockedV4Reason(it) }
        if (':' in a) {
            val groups = parseIpv6(a) ?: return "unparseable ipv6 literal '$address'"
            return blockedV6Reason(groups)
        }
        return "unparseable address '$address'"
    }

    fun isIpLiteral(host: String): Boolean {
        val h = host.trim('[', ']').removeSuffix(".")
        return parseIpv4(h) != null || ':' in h
    }

    fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return null
            // Leading zero is octal-vs-decimal ambiguous (inet_aton reads
            // "012" as 10) — strict decimal only, callers fail closed.
            if (p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            octets[i] = v
        }
        return octets
    }

    // Expands "::" by hand and matches by16-bit groups; supports an
    // embedded IPv4 tail (e.g. ::ffff:127.0.0.1).
    fun parseIpv6(host: String): IntArray? {
        var s = host
        val zone = s.indexOf('%')
        if (zone >= 0) s = s.substring(0, zone)
        if (s.isEmpty()) return null
        val parts = s.split("::")
        if (parts.size > 2) return null

        fun parseGroups(segment: String): List<Int>? {
            if (segment.isEmpty()) return emptyList()
            val groups = mutableListOf<Int>()
            for (piece in segment.split(':')) {
                if (piece.isEmpty()) return null
                if (piece.contains('.')) {
                    val v4 = parseIpv4(piece) ?: return null
                    groups += (v4[0] shl 8) or v4[1]
                    groups += (v4[2] shl 8) or v4[3]
                } else {
                    if (piece.length > 4 || piece.any { it !in "0123456789abcdef" }) return null
                    groups += piece.toInt(16)
                }
            }
            return groups
        }

        val head = parseGroups(parts[0]) ?: return null
        if (parts.size == 1) {
            return if (head.size == 8) head.toIntArray() else null
        }
        val tail = parseGroups(parts[1]) ?: return null
        // "::" must stand for at least one zero group.
        if (head.size + tail.size > 7) return null
        val full = ArrayList<Int>(8)
        full.addAll(head)
        repeat(8 - head.size - tail.size) { full += 0 }
        full.addAll(tail)
        return full.toIntArray()
    }

    fun blockedV4Reason(o: IntArray): String? = when {
        o[0] == 0 -> "0.0.0.0/8 (any)"
        o[0] == 10 -> "10.0.0.0/8 (private)"
        o[0] == 127 -> "127.0.0.0/8 (loopback)"
        o[0] == 169 && o[1] == 254 -> "169.254.0.0/16 (link-local/metadata)"
        o[0] == 172 && o[1] in 16..31 -> "172.16.0.0/12 (private)"
        o[0] == 192 && o[1] == 168 -> "192.168.0.0/16 (private)"
        o[0] in 224..239 -> "${o.joinToString(".")} (multicast 224.0.0.0/4)"
        else -> null
    }

    fun blockedV6Reason(g: IntArray): String? {
        if (g.all { it == 0 }) return "::/128 (any)"
        if (g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 &&
            g[4] == 0 && g[5] == 0 && g[6] == 0 && g[7] == 1
        ) {
            return "::1 (loopback)"
        }
        // IPv4-compatible ::a.b.c.d (::/96) and IPv4-mapped ::ffff:a.b.c.d
        // (::ffff:0:0/96) — fall through to the IPv4 rules for the embedded
        // address. "::" and "::1" are already handled above.
        if (g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0) {
            val v4 = intArrayOf(g[6] shr 8, g[6] and 0xff, g[7] shr 8, g[7] and 0xff)
            blockedV4Reason(v4)?.let { return "::/96 (embedded: $it)" }
        }
        val b0 = g[0] shr 8
        val b1 = g[0] and 0xff
        return when {
            b0 == 0xfc || b0 == 0xfd -> "fc00::/7 (unique-local)"
            b0 == 0xff -> "ff00::/8 (multicast)"
            b0 == 0xfe && (b1 and 0xc0) == 0x80 -> "fe80::/10 (link-local)"
            else -> null
        }
    }
}
