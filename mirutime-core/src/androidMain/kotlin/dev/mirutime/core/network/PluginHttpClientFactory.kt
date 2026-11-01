package dev.mirutime.core.network

import dev.mirutime.core.host.Logger
import dev.mirutime.core.network.security.createEngineDns
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

// PluginHttpClientFactory: Android picks the OkHttp engine and routes every
// hostname through one system-first + DoH resolver wrapped in SafeDns. The
// wrapper rejects private/loopback answers at connect time and OkHttp then
// connects to exactly those validated addresses (IP pinning).
// safeDns=true is the plugin runtime only; user-configured lanes (repo/install/
// update, translation, images) pass false. The hidden `webview_fetch` fetcher
// has its own pre-connect SsrfGuard and does not call this factory.
actual fun createEngine(logger: Logger?, safeDns: Boolean): HttpClientEngine = OkHttp.create {
    dns = createEngineDns(safeDns) { message -> logger?.d(TAG, message) }
}

private const val TAG = "network"
