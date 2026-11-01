package dev.mirutime.imageloader

// The app's image lane behind Coil: one suspend function, no interface. The
// production implementation is PluginGraph.fetchImage, which applies the
// per-host UA/Referer registry and the domain-aware WebView cookies.
typealias ImageFetchPort = suspend (url: String, headers: Map<String, String>) -> FetchedImage
