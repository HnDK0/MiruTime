package dev.mirutime.core.text

/**
 * Replaces literal ASCII angle brackets with typographic ones. Translators and
 * HTML consumers treat `<System>` as markup, so the text inside is dropped or
 * mangled; `⟨System⟩` is plain text for them. One-to-one replacement keeps
 * offsets stable for TTS highlighting, reading anchors and pagination.
 */
fun normalizeAngles(text: String): String =
    if ('<' in text || '>' in text) text.replace('<', '⟨').replace('>', '⟩') else text
