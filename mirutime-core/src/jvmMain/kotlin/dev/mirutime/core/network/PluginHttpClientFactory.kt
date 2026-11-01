package dev.mirutime.core.network

import dev.mirutime.core.host.Logger
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

// PluginHttpClientFactory: JVM picks the CIO engine.
// CIO exposes no DNS hook, so this build has NO connection-time address check:
// only the URL-level SSRF rules run (PluginHttpClient.checkSsrf). The JVM host
// is the plugin-tester/library surface, NOT the Android security boundary — do
// not claim SafeDns parity here. The Android actual installs SafeDns, which
// validates the addresses OkHttp is about to connect to.
// safeDns is an Android-only knob: CIO has no DNS hook, so JVM behaves the
// same either way.
actual fun createEngine(logger: Logger?, safeDns: Boolean): HttpClientEngine = CIO.create()
