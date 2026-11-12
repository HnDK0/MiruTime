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
            else -> null
        }
    }
}
