package dev.mirutime.uikit

// Theme background: either a flat palette (None) or an image drawn
// full-bleed behind the app content with a veil for readability. Source is a
// plain string on purpose: http(s) URL, content:// URI from the file picker,
// or an absolute file path. Loading, decoding and storage stay in :app.
sealed interface ThemeBackground {
    data object None : ThemeBackground
    data class Image(val source: String) : ThemeBackground
}
