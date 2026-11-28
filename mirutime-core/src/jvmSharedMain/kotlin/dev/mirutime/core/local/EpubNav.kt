package dev.mirutime.core.local

/** One table-of-contents link, already resolved to the path of an archive entry. */
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

/**
 * The table of contents to use: the first source whose links reach a spine file, NCX before the
 * EPUB 3 nav document. Links are relative to the TOC document itself; some generators write them
 * relative to the OPF folder instead, which is how they used to be read, so that is the second try.
 * A TOC that reaches no spine file is as good as none: an empty list makes the caller fall back to
 * one chapter per spine file instead of dropping every chapter.
 */
internal fun parseToc(
    read: (String) -> ByteArray?,
    ncxPath: String?,
    navPath: String?,
    rootPath: String,
    spineUrls: Collection<String>,
): List<EpubNavEntry> {
    val spineFiles = spineUrls.mapTo(HashSet()) { it.substringBefore('#').lowercase() }
    fun List<EpubNavEntry>.reachesSpine() = any { it.link.substringBefore('#').lowercase() in spineFiles }

    val sources = listOfNotNull(
        ncxPath?.let { path -> path to { base: String -> parseNcxToc(read, path, base) } },
        navPath?.let { path -> path to { base: String -> parseEpub3Nav(read, path, base) } },
    )
    for ((path, parse) in sources) {
        val ownFolder = path.substringBeforeLast('/', "")
        for (base in listOf(ownFolder, rootPath).distinct()) {
            runCatching { parse(base) }.getOrNull()
                ?.takeIf { it.reachesSpine() }
                ?.let { return it }
        }
    }
    return emptyList()
}
