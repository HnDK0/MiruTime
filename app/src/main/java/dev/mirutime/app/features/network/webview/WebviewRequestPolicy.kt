package dev.mirutime.app.features.network.webview

import dev.mirutime.core.network.security.SsrfVerdict

// What a hidden-WebView request may do. One rule for the main frame and for
// every subresource (iframe, worker, XHR, WebSocket, img): the page must never
// reach a non-https or private/metadata target.
internal enum class WebviewRequestDecision { Allow, Block }

// `resolvedVerdict = null` means "no address verdict at this call site": the
// https rule still applies, and the address belongs to a caller that runs off
// Main (shouldOverrideUrlLoading must not do DNS; shouldInterceptRequest does
// it on a WebView background thread and always passes a verdict).
// isMainFrame is deliberately NOT part of the rule: subresources are blocked
// exactly like the main frame, and the tests pin that parity.
internal fun webviewRequestDecision(
    scheme: String?,
    resolvedVerdict: SsrfVerdict?,
    isMainFrame: Boolean,
): WebviewRequestDecision {
    if (!scheme.equals("https", ignoreCase = true)) return WebviewRequestDecision.Block
    return if (resolvedVerdict == null || resolvedVerdict == SsrfVerdict.Ok) {
        WebviewRequestDecision.Allow
    } else {
        WebviewRequestDecision.Block
    }
}
