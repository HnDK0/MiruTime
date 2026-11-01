package dev.mirutime.core.network

import dev.mirutime.core.host.Logger
import io.ktor.client.engine.HttpClientEngine

// Platform engine selection for PluginHttpClient: CIO on JVM, OkHttp on Android.
// Consumers hand the result to PluginHttpClient(engine = createEngine()).
// [logger] is optional (null = silent); Android wires it to the DNS resolver.
// [safeDns] keeps the connection-time SSRF gate on the Android engine. Only
// the plugin runtime (host fetcher and Lua http_get/post) leaves it true;
// user-configured lanes (repo/install/update, translation, images) opt out.
// Media uses its own OkHttp transport. The hidden `webview_fetch` fetcher does
// not use this factory: it applies its own pre-connect SsrfGuard before
// navigation.
expect fun createEngine(logger: Logger? = null, safeDns: Boolean = true): HttpClientEngine
