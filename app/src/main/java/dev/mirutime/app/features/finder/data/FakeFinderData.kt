package dev.mirutime.app.features.finder.data

import android.net.Uri
import dev.mirutime.app.di.InstallReport
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogPage
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.finder.domain.LocalBook
import dev.mirutime.app.features.finder.domain.LocalCatalogFolder
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.domain.LinkResolution
import dev.mirutime.app.features.finder.domain.PluginExtension
import dev.mirutime.app.features.finder.domain.PluginFileInstall
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterOption
import dev.mirutime.app.features.finder.domain.PluginFilterType
import dev.mirutime.app.features.finder.domain.PluginResetResult
import dev.mirutime.app.features.finder.domain.PluginSetting
import dev.mirutime.app.features.finder.domain.PluginSettingOption
import dev.mirutime.app.features.finder.domain.applyLibraryUrls
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.data.ImportSummary
import dev.mirutime.app.features.library.data.LocalImportResult
import dev.mirutime.app.features.library.data.PickedImportFile
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.api.Outcome
import dev.mirutime.data.library.LibraryMembership
import kotlin.math.abs
import kotlinx.coroutines.delay

// Refresh result: merged rows plus any shared-library failure that would make
// the sources fail require_lib at load time. The screen shows the failure; a
// library problem is never swallowed behind a list reload.
data class ExtensionRefreshResult(
    val extensions: List<PluginExtension>,
    val failed: List<Pair<String, String>> = emptyList(),
)

interface FinderDataProvider {
    // Installed AND enabled plugins (what Browse lists).
    suspend fun sources(): List<CatalogSource>
    suspend fun togglePin(sourceId: String): Boolean

    // One catalogue page (1-based) for the applied query + filters. Throws on
    // a source error; the screen shows the error state with Reload / Copy.
    suspend fun page(sourceId: String, filters: CatalogFilters, page: Int): CatalogPage
    fun genres(): List<String>

    // Plugin-declared `search_filters` capability and source language, both
    // for the filter sheet. Defaults keep fakes/previews honest (no capability,
    // no language hint).
    suspend fun supportsSearchFilters(sourceId: String): Boolean = false
    suspend fun sourceLanguage(sourceId: String): String? = null

    // Plugin-declared catalogue filters (getFilterList). Ok(empty) means the
    // source genuinely declared no filters; Err is a load failure the sheet
    // must show with Retry. Never built from library genres.
    suspend fun filterSchema(sourceId: String): Outcome<List<PluginFilter>>

    // Global search: one source's answer (first matches), may throw.
    suspend fun searchSource(sourceId: String, query: String): List<CatalogBook>

    /** Batch membership lookup for catalogue cards; empty set = none. */
    suspend fun libraryUrls(urls: Collection<String>): Set<String>

    /**
     * Catalogue membership in one batch: URL matches (same source) plus title
     * counts (the book exists from another plugin). Default keeps fakes simple.
     */
    suspend fun libraryMembership(
        urls: Collection<String>,
        titles: Collection<String>,
    ): LibraryMembership = LibraryMembership(urls = libraryUrls(urls), titles = emptyMap())

    /**
     * Explicit add/remove from the library. Never called by a normal tap; the
     * catalogue long-press is the only entry point.
     */
    suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult

    // Built-in local sources: imported device files.
    suspend fun localBooks(kind: String): List<LocalBook>
    suspend fun importLocal(files: List<PickedImportFile>): ImportSummary
    // Loose image folder: null means "not an image tree, use file import".
    suspend fun importTree(uri: String): LocalImportResult?
    suspend fun removeLocal(bookUrl: String)

    // Folder management for the built-in local sources. The sheet lists the
    // configured trees, adds/removes them (SAF permission follows the tree)
    // and runs the bulk import for one folder.
    suspend fun localCatalogFolders(kind: String): List<LocalCatalogFolder>
    suspend fun addLocalCatalogFolder(kind: String, uri: String)
    suspend fun removeLocalCatalogFolder(kind: String, uri: String)
    suspend fun importLocalCatalogFolder(kind: String, uri: String): ImportSummary

    // Per-plugin base URL (Finder row settings).
    fun baseUrl(id: String): String
    suspend fun setBaseUrl(id: String, url: String)

    // Settings the plugin itself declares (`getSettingsSchema`); empty list =
    // the plugin has none.
    fun settingsSchema(id: String): List<PluginSetting>

    // Manage extensions screen. extensions() paints local/disk rows and must
    // never await the network; refreshExtensions() may fetch the repository.
    suspend fun extensions(): List<PluginExtension>
    suspend fun refreshExtensions(): ExtensionRefreshResult = ExtensionRefreshResult(extensions())
    suspend fun uninstallMany(ids: Set<String>)
    // The real install pass report: `failed` names every source that could not
    // be written and why, so the screen never silences a broken update.
    suspend fun install(id: String): InstallReport
    suspend fun update(id: String): InstallReport
    suspend fun updateAll(): InstallReport
    suspend fun uninstall(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    fun repoUrl(): String
    fun defaultRepoUrl(): String
    // Refreshes the repository catalog (and its shared libraries); the report
    // names every library that could not be installed.
    suspend fun refreshRepo(url: String): InstallReport

    // Local .lua install / health actions. Bytes are already read and
    // size-capped by the caller; the provider owns validation + disk.
    suspend fun installFromFile(fileName: String, bytes: ByteArray): PluginFileInstall
    // Reinstalls the repository copy of an installed plugin; only offered
    // when PluginExtension.repoAvailable is true.
    suspend fun resetPlugin(id: String): PluginResetResult
    // Read-only source for the viewer; null when the file is gone.
    suspend fun pluginScript(id: String): String?

    // Add by link.
    suspend fun resolveLink(url: String): LinkResolution

    // Filter presets, per source, max FilterPreset.Max.
    fun presets(sourceId: String): List<FilterPreset>
    fun savePreset(sourceId: String, preset: FilterPreset)
    fun deletePreset(sourceId: String, name: String)
}

class FakeFinderDataProvider : FinderDataProvider {
    override suspend fun sources(): List<CatalogSource> {
        delay(150)
        val local = listOf(
            CatalogSource(
                id = LocalSources.Books,
                name = "Local books",
                language = "",
                type = ContentType.Novel,
                version = "",
                pinned = LocalSources.Books in FinderFixtures.pinned,
                local = true,
                libraryCount = FinderFixtures.localBooks.count { !it.isManga },
            ),
            CatalogSource(
                id = LocalSources.Manga,
                name = "Local manga",
                language = "",
                type = ContentType.Manga,
                version = "",
                pinned = LocalSources.Manga in FinderFixtures.pinned,
                local = true,
                libraryCount = FinderFixtures.localBooks.count { it.isManga },
            ),
        )
        val plugins = FinderFixtures.plugins
            .filter { it.installed && it.enabled }
            .map {
                CatalogSource(
                    id = it.id,
                    name = it.name,
                    language = it.language,
                    type = it.type,
                    version = it.installedVersion.orEmpty(),
                    pinned = it.id in FinderFixtures.pinned,
                    libraryCount = FinderFixtures.libraryCount(it.id),
                )
            }
        return local + plugins
    }

    override suspend fun togglePin(sourceId: String): Boolean {
        if (!FinderFixtures.pinned.remove(sourceId)) FinderFixtures.pinned.add(sourceId)
        return true
    }

    override suspend fun page(sourceId: String, filters: CatalogFilters, page: Int): CatalogPage {
        delay(350)
        // One source fails on its very first request: shows the error state.
        if (sourceId == "cloud-gate" && !FinderFixtures.cloudGateFailedOnce) {
            FinderFixtures.cloudGateFailedOnce = true
            throw IllegalStateException("HTTP 403: Cloudflare check required (cloud-gate)")
        }
        val plugin = FinderFixtures.plugins.firstOrNull { it.id == sourceId && it.installed }
            ?: return CatalogPage(emptyList(), hasMore = false)
        val matched = FinderFixtures.catalog(plugin)
            .filter { filters.query.isBlank() || it.title.contains(filters.query.trim(), ignoreCase = true) }
            .filter { filters.genres.isEmpty() || it.genres.containsAll(filters.genres) }
            .filter { !filters.ongoingOnly || it.ongoing }
        val ascending = when (filters.sort) {
            CatalogSort.Popular -> matched
            CatalogSort.Latest -> matched.asReversed()
            CatalogSort.Rating -> matched.sortedBy { it.rating }
            CatalogSort.Title -> matched.sortedBy { it.title.lowercase() }
        }
        val ordered = if (filters.descending) ascending.asReversed() else ascending
        val from = (page - 1) * PageSize
        val slice = ordered.drop(from).take(PageSize)
        return CatalogPage(
            books = slice.applyLibraryUrls(FinderFixtures.inLibrary),
            hasMore = from + PageSize < ordered.size,
        )
    }

    override suspend fun libraryUrls(urls: Collection<String>): Set<String> =
        FinderFixtures.inLibrary.intersect(urls.toSet())

    override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult {
        if (inLibrary) FinderFixtures.inLibrary.add(bookUrl) else FinderFixtures.inLibrary.remove(bookUrl)
        return MembershipResult(success = true)
    }

    // Legacy genre chips are not a plugin schema. The filter sheet uses
    // filterSchema() from the next UI phase; until then it has no genre rows.
    override fun genres(): List<String> = emptyList()

    override suspend fun filterSchema(sourceId: String): Outcome<List<PluginFilter>> {
        if (LocalSources.isLocal(sourceId)) return Outcome.Ok(emptyList())
        delay(80)
        return Outcome.Ok(FinderFixtures.filterSchema)
    }

    override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> {
        // Staggered answers: sources reply one by one, like a real fan-out.
        delay(250L + (abs(sourceId.hashCode()) % 900))
        // Local sources answer too: imported files whose title matches.
        if (LocalSources.isLocal(sourceId)) {
            val manga = sourceId == LocalSources.Manga
            return FinderFixtures.localBooks
                .filter { it.isManga == manga && it.title.contains(query.trim(), ignoreCase = true) }
                .map {
                    CatalogBook(
                        bookUrl = it.bookUrl,
                        title = it.title,
                        rating = 0f,
                        chapters = it.count,
                        genres = emptyList(),
                        ongoing = false,
                    )
                }
                .applyLibraryUrls(FinderFixtures.inLibrary)
        }
        if (sourceId == "cloud-gate") throw IllegalStateException("HTTP 403: Cloudflare check required")
        val plugin = FinderFixtures.plugins.firstOrNull { it.id == sourceId && it.installed }
            ?: return emptyList()
        return FinderFixtures.catalog(plugin)
            .filter { it.title.contains(query.trim(), ignoreCase = true) }
            .take(12)
            .applyLibraryUrls(FinderFixtures.inLibrary)
    }

    override suspend fun localBooks(kind: String): List<LocalBook> {
        delay(120)
        return FinderFixtures.localBooks.filter { (kind == "manga") == it.isManga }
    }

    override suspend fun importLocal(files: List<PickedImportFile>): ImportSummary {
        delay(200)
        var imported = 0
        var duplicates = 0
        for (file in files) {
            if (FinderFixtures.localBooks.any { it.fileName == file.fileName }) {
                duplicates++
                continue
            }
            val hash = abs(file.fileName.hashCode())
            FinderFixtures.localBooks.add(
                0,
                LocalBook(
                    bookUrl = "fake://local/${file.fileName}",
                    fileName = file.fileName,
                    title = file.fileName.substringBeforeLast('.')
                        .replace('-', ' ').replace('_', ' ')
                        .split(' ').filter { it.isNotBlank() }
                        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } },
                    format = file.format,
                    sizeLabel = if (file.format == "CBZ") "${20 + hash % 120} MB" else "${1 + hash % 9}.${hash % 10} MB",
                    count = if (file.format == "CBZ") 60 + hash % 240 else 40 + hash % 400,
                ),
            )
            imported++
        }
        return ImportSummary(imported = imported, duplicates = duplicates)
    }

    override suspend fun removeLocal(bookUrl: String) {
        FinderFixtures.localBooks.removeAll { it.bookUrl == bookUrl }
    }

    // The fake has no SAF store: folder management is a no-op and the sheet
    // simply starts empty when a preview uses this provider.
    override suspend fun localCatalogFolders(kind: String): List<LocalCatalogFolder> = emptyList()

    override suspend fun addLocalCatalogFolder(kind: String, uri: String) = Unit

    override suspend fun removeLocalCatalogFolder(kind: String, uri: String) = Unit

    override suspend fun importLocalCatalogFolder(kind: String, uri: String): ImportSummary =
        ImportSummary()

    override suspend fun importTree(uri: String): LocalImportResult? {
        delay(200)
        val name = uri.substringAfterLast('/').ifBlank { "Manga folder" }
        val bookUrl = "fake://local/tree-${name.hashCode()}"
        val existing = FinderFixtures.localBooks.firstOrNull { it.bookUrl == bookUrl }
        if (existing != null) {
            return LocalImportResult.Success(existing.bookId, existing.bookUrl, existing.title, true)
        }
        val book = LocalBook(
            bookUrl = bookUrl,
            fileName = name,
            title = name,
            format = "CBZ",
            sizeLabel = "1.0 MB",
            count = 1,
        )
        FinderFixtures.localBooks.add(0, book)
        return LocalImportResult.Success(0, bookUrl, book.title, false)
    }

    override fun baseUrl(id: String): String =
        FinderFixtures.baseUrls[id] ?: "https://$id.example"

    override suspend fun setBaseUrl(id: String, url: String) {
        FinderFixtures.baseUrls[id] = url.trim()
    }

    // WTR-Lab is the canonical schema example: the exact
    // getSettingsSchema() of wtrlab.lua — keys, labels and option values, no
    // app resources involved. Values live in AppSettings so they survive like
    // the rest of the app's state.
    override fun settingsSchema(id: String): List<PluginSetting> = when (id) {
        "wtr-lab" -> listOf(
            PluginSetting.Select(
                key = "wtrlab_mode",
                label = "Translation Mode",
                options = listOf(
                    PluginSettingOption("ai", "AI (Beta)"),
                    PluginSettingOption("raw", "Raw (Web)"),
                    PluginSettingOption("webplus", "Web+ (Beta)"),
                ),
                current = { AppSettings.wtrMode },
                onChange = { AppSettings.wtrMode = it },
            ),
            PluginSetting.Select(
                key = "wtrlab_chapter_delay",
                label = "Chapter Fetch Delay (may reduce 'Security Check' triggers)",
                options = listOf(
                    PluginSettingOption("0", "None"),
                    PluginSettingOption("2000", "2 seconds"),
                    PluginSettingOption("4000", "4 seconds"),
                    PluginSettingOption("6000", "6 seconds"),
                ),
                current = { AppSettings.wtrChapterDelayMs.toString() },
                onChange = { AppSettings.wtrChapterDelayMs = it.toIntOrNull() ?: 0 },
            ),
        )

        else -> emptyList()
    }

    override suspend fun uninstallMany(ids: Set<String>) {
        delay(500)
        ids.forEach { id ->
            FinderFixtures.pinned.remove(id)
            mutate(id) { it.copy(installedVersion = null, enabled = false) }
        }
    }

    override suspend fun extensions(): List<PluginExtension> {
        delay(150)
        return FinderFixtures.plugins.toList()
    }

    private fun mutate(id: String, change: (PluginExtension) -> PluginExtension) {
        val index = FinderFixtures.plugins.indexOfFirst { it.id == id }
        if (index >= 0) FinderFixtures.plugins[index] = change(FinderFixtures.plugins[index])
    }

    override suspend fun install(id: String): InstallReport {
        delay(800)
        mutate(id) { it.copy(installedVersion = it.availableVersion, enabled = true) }
        return InstallReport(installed = listOf(id), failed = emptyList())
    }

    override suspend fun update(id: String): InstallReport {
        delay(700)
        mutate(id) { it.copy(installedVersion = it.availableVersion) }
        return InstallReport(installed = listOf(id), failed = emptyList())
    }

    override suspend fun updateAll(): InstallReport {
        delay(900)
        val updated = FinderFixtures.plugins.filter { it.updateAvailable }.map { it.id }
        updated.forEach { id ->
            mutate(id) { it.copy(installedVersion = it.availableVersion) }
        }
        return InstallReport(installed = updated, failed = emptyList())
    }

    override suspend fun uninstall(id: String) {
        delay(400)
        FinderFixtures.pinned.remove(id)
        mutate(id) { it.copy(installedVersion = null, enabled = false) }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        mutate(id) { it.copy(enabled = enabled) }
    }

    override fun repoUrl(): String = FinderFixtures.repoUrl

    override fun defaultRepoUrl(): String = FinderFixtures.DefaultRepo

    override suspend fun refreshRepo(url: String): InstallReport {
        delay(700)
        FinderFixtures.repoUrl = url.trim().ifBlank { FinderFixtures.DefaultRepo }
        return InstallReport(emptyList(), emptyList())
    }

    override suspend fun installFromFile(fileName: String, bytes: ByteArray): PluginFileInstall {
        delay(300)
        val name = fileName.substringBeforeLast('.')
        return PluginFileInstall.Installed(id = name, name = name)
    }

    override suspend fun resetPlugin(id: String): PluginResetResult {
        delay(300)
        mutate(id) { it.copy(error = null, enabled = true) }
        return PluginResetResult(success = true)
    }

    override suspend fun pluginScript(id: String): String? {
        delay(120)
        return "-- fixture plugin $id\nfunction getCatalogList(index) return {items = {}} end"
    }

    // Fixture address scheme: https://<plugin-id>.example/<book-slug>.
    override suspend fun resolveLink(url: String): LinkResolution {
        delay(250)
        val clean = url.trim()
        val uri = runCatching { Uri.parse(clean) }.getOrNull()
        val host = uri?.host?.lowercase()
        if (uri == null || host.isNullOrBlank() || uri.scheme?.startsWith("http") != true) {
            return LinkResolution.Invalid
        }
        val pluginId = host.removePrefix("www.").substringBefore('.')
        val plugin = FinderFixtures.plugins.firstOrNull { it.id == pluginId }
        if (plugin == null || !plugin.installed) {
            return LinkResolution.UnknownHost(host = host, suggestion = plugin)
        }
        val slug = uri.pathSegments.lastOrNull { it.isNotBlank() }
            ?: return LinkResolution.Invalid
        val title = slug.replace('-', ' ').replace('_', ' ')
            .split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        val hash = abs(slug.hashCode())
        val source = CatalogSource(
            id = plugin.id,
            name = plugin.name,
            language = plugin.language,
            type = plugin.type,
            version = plugin.installedVersion.orEmpty(),
            pinned = plugin.id in FinderFixtures.pinned,
        )
        return LinkResolution.Found(
            source = source,
            book = CatalogBook(
                bookUrl = "fake://${plugin.id}/$slug",
                title = title,
                rating = if (hash % 6 == 0) 0f else 3.6f + (hash % 14) / 10f,
                chapters = 40 + hash % 500,
                genres = emptyList(),
                ongoing = hash % 4 != 0,
            ),
        )
    }

    override fun presets(sourceId: String): List<FilterPreset> =
        FinderFixtures.presets[sourceId].orEmpty()

    override fun savePreset(sourceId: String, preset: FilterPreset) {
        val current = FinderFixtures.presets[sourceId].orEmpty()
        val replaced = current.filterNot { it.name.equals(preset.name, ignoreCase = true) }
        // Limit: a NEW name beyond the max is refused by the sheet; the
        // data layer still never stores more than the max.
        FinderFixtures.presets[sourceId] = (listOf(preset) + replaced).take(FilterPreset.Max)
    }

    override fun deletePreset(sourceId: String, name: String) {
        FinderFixtures.presets[sourceId] = FinderFixtures.presets[sourceId].orEmpty()
            .filterNot { it.name.equals(name, ignoreCase = true) }
    }

    private companion object {
        const val PageSize = 18
    }
}

object FinderFixtures {
    // One fixture of every filter type, in the real flattened-schema shape
    // (Lua getFilterList -> core dotted maps -> PluginFilter).
    val filterSchema: List<PluginFilter> = listOf(
        PluginFilter(
            key = "order",
            type = PluginFilterType.Sort,
            label = "Sort by",
            options = listOf(
                PluginFilterOption("weekly", "Weekly"),
                PluginFilterOption("new", "Newest"),
                PluginFilterOption("rating", "Rating"),
            ),
            defaultValue = "weekly",
            defaultAscending = false,
        ),
        PluginFilter(
            key = "kind",
            type = PluginFilterType.Select,
            label = "Kind",
            options = listOf(
                PluginFilterOption("all", "All"),
                PluginFilterOption("novel", "Novel"),
                PluginFilterOption("manga", "Manga"),
            ),
            defaultValue = "all",
        ),
        PluginFilter(
            key = "genre",
            type = PluginFilterType.Checkbox,
            label = "Genres",
            options = listOf(
                PluginFilterOption("action", "Action"),
                PluginFilterOption("fantasy", "Fantasy"),
                PluginFilterOption("romance", "Romance"),
            ),
        ),
        PluginFilter(
            key = "status",
            type = PluginFilterType.Tristate,
            label = "Status",
            options = listOf(
                PluginFilterOption("ongoing", "Ongoing"),
                PluginFilterOption("completed", "Completed"),
            ),
        ),
        PluginFilter(
            key = "completed",
            type = PluginFilterType.Switch,
            label = "Completed only",
            defaultValue = "false",
        ),
        PluginFilter(
            key = "author",
            type = PluginFilterType.Text,
            label = "Author",
        ),
        PluginFilter(
            key = "tags",
            type = PluginFilterType.TagInput,
            label = "Tags",
            options = listOf(
                PluginFilterOption("isekai", "Isekai"),
                PluginFilterOption("wuxia", "Wuxia"),
            ),
            allowCustom = true,
        ),
    )

    // 28 options: past the 20-option threshold, so the in-section search
    // field of the genres group is visible on fixtures.
    val genres = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Mystery",
        "Romance", "Slice of life", "Supernatural", "Thriller", "Historical",
        "Horror", "Isekai", "Martial arts", "Mecha", "Psychological",
        "School life", "Sci-fi", "Sports", "Tragedy", "Wuxia", "Xianxia",
        "Harem", "Josei", "Seinen", "Shounen", "Gender bender", "Cooking",
    )

    // Canonical plugin repository (external-sources, main branch).
    const val DefaultRepo = "https://raw.githubusercontent.com/HnDK0/external-sources/main/index.yaml"
    var repoUrl = DefaultRepo

    // The repo: 9 installed (one disabled, two with updates) + 4 available.
    // WTR-Lab lives here as a regular plugin now: its options moved out of
    // Settings → Network into the plugin's own settings sheet.
    val plugins = mutableListOf(
        PluginExtension("wtr-lab", "WTR-Lab", "EN", ContentType.Novel, "1.2", "1.2", true),
        PluginExtension("toonsource", "ToonSource", "EN", ContentType.Novel, "1.4", "1.4", true),
        PluginExtension("ranobelib", "RanobeLib", "RU", ContentType.Novel, "2.1", "2.1", true),
        PluginExtension("novelbook", "Новелбук", "RU", ContentType.Novel, "1.0", "1.0", true),
        PluginExtension("wuxia-archive", "Wuxia Archive", "EN", ContentType.Novel, "3.3", "3.2", true),
        PluginExtension("readlight", "ReadLight", "EN", ContentType.Novel, "1.9", "1.9", true),
        PluginExtension("cloud-gate", "Cloud Gate", "EN", ContentType.Novel, "0.8", "0.7", true),
        PluginExtension("asura-scans", "Asura Scans", "EN", ContentType.Manga, "0.9", "0.9", true),
        PluginExtension("anime-stream", "AnimeStream", "EN", ContentType.Video, "1.1", "1.1", false),
        PluginExtension("lightnovel-pub", "LightNovel Pub", "EN", ContentType.Novel, "2.0", null, false),
        PluginExtension("reaper-hub", "Reaper Hub", "EN", ContentType.Manga, "1.3", null, false),
        PluginExtension("freelib", "Свободная библиотека", "RU", ContentType.Novel, "0.5", null, false),
        PluginExtension("videobox", "VideoBox", "EN", ContentType.Video, "0.4", null, false),
    )

    // Pins survive screen changes; the local sources start pinned, and
    // unpinning drops them into the normal order.
    val pinned = mutableSetOf(
        LocalSources.Books, LocalSources.Manga, "toonsource", "ranobelib",
    )
    val baseUrls = mutableMapOf<String, String>()

    // Fixture membership: the fake long-press mutates this set, so badges
    // survive page/search reloads within one process.
    val inLibrary = mutableSetOf<String>()

    val localBooks = mutableListOf(
        LocalBook("fake://local/worm.epub", "worm.epub", "Worm", "EPUB", "3.8 MB", 310),
        LocalBook("fake://local/mother-of-learning.fb2", "mother-of-learning.fb2", "Mother of Learning", "FB2", "2.1 MB", 108),
        LocalBook("fake://local/coiling-dragon.epub", "coiling-dragon.epub", "Coiling Dragon", "EPUB", "5.4 MB", 806),
        LocalBook("fake://local/berserk-vol-01.cbz", "berserk-vol-01.cbz", "Berserk Vol 01", "CBZ", "86 MB", 224),
        LocalBook("fake://local/vagabond-vol-03.cbz", "vagabond-vol-03.cbz", "Vagabond Vol 03", "CBZ", "74 MB", 198),
    )

    // Fixture only: deterministic "titles in library" per plugin.
    fun libraryCount(id: String): Int = abs(id.hashCode()) % 9
    var cloudGateFailedOnce = false
    val presets = mutableMapOf<String, List<FilterPreset>>()

    fun sourceName(id: String): String = plugins.firstOrNull { it.id == id }?.name ?: id

    private val enAdjectives = listOf(
        "Silent", "Crimson", "Last", "Hollow", "Eternal",
        "Broken", "Midnight", "Iron", "Glass", "Forgotten",
    )
    private val enNouns = listOf(
        "Archivist", "Tide", "Heir", "Orchard", "Compass",
        "Crown", "Lantern", "Covenant", "Harbor", "Cipher",
    )
    private val ruAdjectives = listOf(
        "Тихий", "Багровый", "Последний", "Пустой", "Вечный",
        "Сломанный", "Полночный", "Железный", "Стеклянный", "Забытый",
    )
    private val ruNouns = listOf(
        "Архивариус", "Прилив", "Наследник", "Сад", "Компас",
        "Венец", "Фонарь", "Завет", "Порт", "Шифр",
    )

    private val cache = mutableMapOf<String, List<CatalogBook>>()

    // 60 deterministic books per source; the same source always lists the
    // same books in the same order, so paging and filters are repeatable.
    fun catalog(plugin: PluginExtension): List<CatalogBook> = cache.getOrPut(plugin.id) {
        val ru = plugin.language == "RU"
        val adjectives = if (ru) ruAdjectives else enAdjectives
        val nouns = if (ru) ruNouns else enNouns
        List(60) { i ->
            // 7 is coprime with 100, so the 60 picks never repeat a title.
            val n = (i * 7 + plugin.id.length) % 100
            val title = adjectives[n / 10] + " " + nouns[n % 10]
            val url = "fake://${plugin.id}/" + title.lowercase().replace(' ', '-')
            val hash = abs(url.hashCode())
            CatalogBook(
                bookUrl = url,
                title = title,
                rating = if (hash % 6 == 0) 0f else 3.6f + (hash % 14) / 10f,
                chapters = 40 + hash % 500,
                genres = List(3) { genres[(hash + it * 3) % genres.size] }.distinct(),
                ongoing = hash % 4 != 0,
                coverUrl = "https://picsum.photos/seed/catalog-$hash/300/450",
            )
        }
    }
}
