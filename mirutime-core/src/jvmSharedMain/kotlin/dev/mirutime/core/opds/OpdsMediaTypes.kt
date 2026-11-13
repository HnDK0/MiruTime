package dev.mirutime.core.opds

/**
 * Maps an acquisition link to a format we can open. Media types in real catalogs
 * are unregistered and often wrong (`application/zip`, `octet-stream`, or absent),
 * so the href extension is the fallback. FB2 alone appears as at least four
 * spellings in the wild (Flibusta, calibre, desktop readers).
 */
object OpdsMediaTypes {
    fun formatOf(link: OpdsLink): OpdsFormat? {
        val type = link.type
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        return when (type) {
            "application/epub+zip" -> OpdsFormat.EPUB
            "application/fb2+zip", "application/x-fb2+zip" -> OpdsFormat.FB2_ZIP
            "application/x-fictionbook+xml", "application/x-fictionbook",
            "application/fb2", "text/fb2+xml",
            -> OpdsFormat.FB2
            "application/vnd.comicbook+zip", "application/x-cbz" -> OpdsFormat.CBZ
            "application/pdf", "application/x-pdf" -> OpdsFormat.PDF
            // A Readium audiobook manifest, or a bare audio file the catalogue
            // hands out directly (a one-track book).
            "application/audiobook+json" -> OpdsFormat.AUDIO
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg" -> OpdsFormat.AUDIO
            // OPDS 2 catalogue and publication documents are pages, not books,
            // whatever their href happens to end with.
            "application/opds+json", "application/opds-publication+json" -> null
            else -> byExtension(link.href)
        }
    }

    private fun byExtension(href: String): OpdsFormat? {
        val path = href.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".fb2.zip") -> OpdsFormat.FB2_ZIP
            path.endsWith(".epub.zip") -> OpdsFormat.EPUB
            path.endsWith(".epub") -> OpdsFormat.EPUB
            path.endsWith(".fb2") -> OpdsFormat.FB2
            path.endsWith(".cbz") -> OpdsFormat.CBZ
            path.endsWith(".pdf") -> OpdsFormat.PDF
            else -> if (isAudioFileUrl(href)) OpdsFormat.AUDIO else null
        }
    }

    /** Audio file suffixes we treat as a directly playable track, not a manifest. */
    private val AUDIO_SUFFIXES = listOf(
        ".mp3", ".m4a", ".m4b", ".m4r", ".aac", ".ogg", ".opus", ".flac", ".wav",
    )

    /**
     * True when [url] points at an audio file the catalogue hands out directly.
     * Shared with the add flow so no other list of extensions can drift.
     */
    fun isAudioFileUrl(url: String): Boolean {
        val path = url.trim().substringBefore('?').substringBefore('#').lowercase()
        return AUDIO_SUFFIXES.any { path.endsWith(it) }
    }
}
