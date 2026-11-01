package dev.mirutime.app.core

import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.core.network.security.HostResolver
import dev.mirutime.core.network.security.createCheckedHostResolver

/**
 * Resolver behind the hidden `webview_fetch` guard: that lane is a WebView, so
 * it has no connect-time DNS hook and resolves the target itself before
 * loading. Every other HTTP lane uses the Ktor engine's SafeDns (plugin) or
 * system DNS (repo/update/images/translation/media).
 */
internal object SafeHttp {
    private const val TAG = "network"

    val resolver: HostResolver by lazy {
        createCheckedHostResolver { message -> MiruCoreLogger.d(TAG, message) }
    }
}
