package dev.mirutime.core.api

interface PluginSource {
    val metadata: PluginMetadata

    // Plugin-declared capability (metadata.supportsSearchFilters): its
    // catalogFiltered also consumes the reserved `_query` key.
    val supportsSearchFilters: Boolean get() = metadata.supportsSearchFilters

    // Ready-made Referer for hotlink-protected images (covers) of this
    // source. NoveLA precedent (SourceInterface.kt:38-43): the Lua `referer`
    // global at the script root, e.g. "https://site.com/". "" = undeclared,
    // the caller falls back to a Referer derived from the image URL.
    val referer: String get() = ""

    // Rewrites a chapter URL before fetching (NoveLA precedent:
    // SourceInterface.kt:47 `suspend fun transformChapterUrl(url: String):
    // String = url`). Lua plugins declare no such hook — identity is the
    // whole contract here; built-in sources override it.
    suspend fun transformChapterUrl(url: String): String = url

    suspend fun catalog(index: Int): Outcome<CatalogPage>

    suspend fun search(index: Int, query: String): Outcome<CatalogPage>

    suspend fun catalogFiltered(
        index: Int,
        filters: Map<String, List<String>>,
    ): Outcome<CatalogPage>

    suspend fun book(url: String): Outcome<BookMeta>

    suspend fun chapters(url: String): Outcome<List<Chapter>>

    /**
     * Optional NoveLA getChapterListHash: a cheap chapter-list probe used by
     * library updates. Null means the source has no hash; callers fetch the
     * full chapter list instead.
     */
    suspend fun chapterListHash(url: String): Outcome<String?> = Outcome.Ok(null)

    suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent>

    suspend fun filters(): Outcome<List<Map<String, String>>>

    suspend fun settingsSchema(): Outcome<List<Map<String, String>>>
}
