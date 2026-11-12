package dev.mirutime.core.network

import dev.mirutime.core.network.cache.platformSynchronized
import dev.mirutime.core.network.cf.CfOptions

// One normalization for EVERY per-host key: HostOptionsRegistry entries,
// CfGate locks/cooldowns and CfChallenge.host must all agree, or lookups
// silently miss (ktor's Url.host keeps "www." and the original case).
// "https://www.Site.Example:8080/x" -> "site.example".
internal fun normalizeHost(raw: String): String =
    raw.lowercase().removePrefix("www.").substringBefore(":")

// Per-host network option overrides registered by installed plugins
// (NoveLA precedent: LuaCfOptionsRegistry). Entries are owner-keyed by
// pluginId: PluginRegistry registers a plugin's cfOptions/userAgentPreset on
// install; PluginHandle.dispose() removes ONLY the entry owned by that
// plugin — another plugin's registration on the same host survives.
// ceiling: plain maps, one value per host (last writer wins) —
// install/dispose are expected to be serialized (plugin-management path,
// not the per-request hot path).
class HostOptionsRegistry {
    private data class Entry<T>(val owner: String, val value: T)

    private val lock = Any()
    private val cfByHost = mutableMapOf<String, Entry<CfOptions>>()
    private val uaByHost = mutableMapOf<String, Entry<String>>()
    private val refererByHost = mutableMapOf<String, Entry<String>>()
    private val authByHost = mutableMapOf<String, Entry<String>>()

    fun setCfOptions(host: String, owner: String, options: CfOptions): Unit = platformSynchronized(lock) {
        cfByHost[host] = Entry(owner, options)
    }

    fun cfOptions(host: String): CfOptions? = platformSynchronized(lock) {
        cfByHost[host]?.value
    }

    fun setUserAgent(host: String, owner: String, userAgent: String): Unit = platformSynchronized(lock) {
        uaByHost[host] = Entry(owner, userAgent)
    }

    fun userAgent(host: String): String? = platformSynchronized(lock) {
        uaByHost[host]?.value
    }

    // Per-host Referer override from a plugin's `referer` global (NoveLA:
    // SourceInterface.referer, used for hotlink-protected covers). Read side
    // wiring lives with the engine lane; the value is the raw configured string.
    // Same first-owner-wins rule as [setAuth]: an OPDS catalogue registers its
    // own host here on every feed load and must not overwrite a plugin's value.
    fun setReferer(host: String, owner: String, referer: String): Unit = platformSynchronized(lock) {
        if (refererByHost[host]?.owner.let { it == null || it == owner }) {
            refererByHost[host] = Entry(owner, referer)
        }
    }

    fun referer(host: String): String? = platformSynchronized(lock) {
        refererByHost[host]?.value
    }

    // Authorization header for host-initiated image requests (private OPDS
    // catalogs: covers live behind the same Basic auth as the feed). Null
    // removes the entry so an anonymous catalog never inherits another one.
    // A non-null value never steals a host another owner registered first.
    fun setAuth(host: String, owner: String, authorization: String?): Unit = platformSynchronized(lock) {
        if (authorization == null) {
            if (authByHost[host]?.owner == owner) authByHost.remove(host)
        } else if (authByHost[host]?.owner.let { it == null || it == owner }) {
            authByHost[host] = Entry(owner, authorization)
        }
    }

    fun auth(host: String): String? = platformSynchronized(lock) {
        authByHost[host]?.value
    }

    // No-op when the entry belongs to a different owner.
    fun clear(host: String, owner: String): Unit = platformSynchronized(lock) {
        if (cfByHost[host]?.owner == owner) cfByHost.remove(host)
        if (uaByHost[host]?.owner == owner) uaByHost.remove(host)
        if (refererByHost[host]?.owner == owner) refererByHost.remove(host)
        if (authByHost[host]?.owner == owner) authByHost.remove(host)
    }
}
