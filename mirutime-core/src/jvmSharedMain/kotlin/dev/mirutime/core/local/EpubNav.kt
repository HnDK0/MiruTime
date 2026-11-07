package dev.mirutime.core.local

/** One EPUB3 nav link, already resolved against the OPF root. */
internal data class EpubNavEntry(val title: String, val link: String)

/**
 * Reads the EPUB3 navigation document. NCX is optional, so this is the fallback
 * TOC source. Prefer the nav marked `epub:type="toc"` over landmarks/other navs.
 */
internal fun parseEpub3Nav(
    read: (String) -> ByteArray?,
    navPath: String,
    rootPath: String,
): List<EpubNavEntry> {
    val bytes = read(navPath) ?: return emptyList()
    val document = parseXmlDocument(bytes) ?: return emptyList()
    val navs = document.selectTag("nav")
    val navElement = navs.firstOrNull { nav ->
        nav.attr("epub:type").orEmpty().split(Regex("\\s+")).contains("toc") ||
            nav.attr("type").orEmpty().split(Regex("\\s+")).contains("toc")
    } ?: navs.firstOrNull() ?: return emptyList()
    return navElement.selectTag("a").mapNotNull { link ->
        val href = link.attr("href").orEmpty().trim()
        if (href.isEmpty()) {
            null
        } else {
            EpubNavEntry(
                title = link.textContent.replace(Regex("\\s+"), " ").trim(),
                link = href.decodedURL.hrefAbsolutePath(rootPath),
            )
        }
    }
}

/** NCX navMap is optional, but when present it is the EPUB2 TOC source. */
internal fun parseNcxToc(
    read: (String) -> ByteArray?,
    ncxPath: String,
    rootPath: String,
): List<EpubNavEntry> {
    val ncx = read(ncxPath) ?: throw IllegalStateException("ncx file missing")
    val navMap = parseXmlDocument(ncx)?.selectFirstTag("navMap")
        ?: throw IllegalStateException("Invalid NCX file: navMap not found")
    return navMap.selectTag("navPoint").mapNotNull { navPoint ->
        val navTitle = (navPoint.selectFirstTag("navLabel")
            ?.selectFirstTag("text")?.textContent ?: "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val src = navPoint.selectFirstTag("content")?.attr("src") ?: ""
        if (src.isEmpty()) null
        else EpubNavEntry(navTitle, src.decodedURL.hrefAbsolutePath(rootPath))
    }
}

/** TOC entry for one spine item; a missing TOC falls back to the chapter title. */
internal fun tocEntryFor(
    spineUrl: String,
    tocEntries: List<EpubNavEntry>,
    parsedTitle: String,
): EpubNavEntry? = tocEntries.firstOrNull {
    it.link.substringBefore('#').equals(spineUrl.substringBefore('#'), ignoreCase = true)
} ?: if (tocEntries.isEmpty()) {
    EpubNavEntry(
        title = parsedTitle.ifBlank {
            spineUrl.substringAfterLast('/').substringBeforeLast('.')
        },
        link = spineUrl,
    )
} else {
    null
}
