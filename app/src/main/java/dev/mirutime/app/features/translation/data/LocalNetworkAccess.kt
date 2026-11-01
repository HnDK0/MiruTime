package dev.mirutime.app.features.translation.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Rules for user-configured local endpoints (Ollama/LM Studio, a LAN plugin
 * repository) and Android 17's ACCESS_LOCAL_NETWORK runtime grant. The endpoint
 * rules stay pure so they are JVM-testable; [isGranted] is the one Android
 * read, and every surface must use it instead of its own permission check.
 */
object LocalNetworkAccess {
    // Literal instead of Manifest.permission: the manifest contract is the
    // permission name, and the symbol only exists in compileSdk 37.
    const val Permission = "android.permission.ACCESS_LOCAL_NETWORK"

    // Android 17 (API 37) gates private-network access behind a runtime grant.
    const val RequiredApi = 37

    // LAN-name suffixes the user may type instead of a raw address.
    private val lanNameSuffixes = listOf(".local", ".lan", ".home.arpa", ".internal")

    /**
     * True for loopback, 10/8, 172.16/12, 192.168/16, 169.254/16,
     * IPv6 loopback/link-local (`fe80::/10`), unique-local (`fc00::/7`),
     * IPv4-mapped literals, `localhost` and LAN names (`.local`, `.lan`,
     * `.home.arpa`, `.internal`) — the hosts a local LLM server can live on.
     * `100.64/10` (Tailscale/CGNAT) stays excluded until a product decision:
     * it also carries carrier-grade NAT addresses, not just the user's own.
     */
    fun isLocalEndpoint(rawUrl: String): Boolean {
        val host = runCatching { URI(rawUrl.trim()).host }.getOrNull()
            ?.trim('[', ']')?.removeSuffix(".")?.lowercase(Locale.ROOT)
            ?: return false
        if (host.isEmpty()) return false
        if (host == "localhost" || lanNameSuffixes.any { host.endsWith(it) }) return true
        if (host.contains(':')) return isLocalIpv6(host)
        return isLocalIpv4(host)
    }

    private fun isLocalIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return isLocalIpv4(octets[0], octets[1])
    }

    private fun isLocalIpv4(address: Inet4Address): Boolean {
        val bytes = address.address
        return isLocalIpv4(bytes[0].toInt() and 0xff, bytes[1].toInt() and 0xff)
    }

    private fun isLocalIpv4(a: Int, b: Int): Boolean =
        a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)

    private fun isLocalIpv6(host: String): Boolean {
        // Literals only (the caller matched a ':'), so this never resolves DNS.
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return when (address) {
            // ::ffff:a.b.c.d is reported as an IPv4 address by the JDK/Android.
            is Inet4Address -> isLocalIpv4(address)
            is Inet6Address -> address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                (address.address[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }

    /** The grant is only needed on Android 17+ and only for a private target. */
    fun isRequired(rawUrl: String, sdkInt: Int, granted: Boolean): Boolean =
        sdkInt >= RequiredApi && !granted && isLocalEndpoint(rawUrl)

    /**
     * The current grant; re-read on resume, never cached by a screen. On
     * Android below [RequiredApi] the permission is not applicable, so this
     * returns false instead of checking a grant the OS never asks for. Callers
     * must combine it with [isRequired] (SDK level + private endpoint) rather
     * than treating false alone as "access denied".
     */
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= RequiredApi &&
            context.checkSelfPermission(Permission) == PackageManager.PERMISSION_GRANTED

    /**
     * One shared cleartext rule: plain HTTP is allowed only for a local
     * endpoint; HTTPS is allowed for any host. Blank is not validated here.
     * An unparsable URL (backslashes, malformed) fails closed: Ktor may
     * normalize text `URI` rejects into a real cleartext request.
     */
    fun isCleartextAllowed(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty()) return true
        val scheme = try {
            URI(url).scheme
        } catch (_: Exception) {
            return false
        }?.lowercase(Locale.ROOT)
        return scheme != "http" || isLocalEndpoint(url)
    }
}
