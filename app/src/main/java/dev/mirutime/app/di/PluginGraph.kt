package dev.mirutime.app.di

import android.content.Context
import android.os.Build
import dev.mirutime.app.BuildConfig
import dev.mirutime.app.R
import dev.mirutime.app.core.AppCacheConfig
import dev.mirutime.app.core.REPO_MAX_REDIRECTS
import dev.mirutime.app.core.RemoteBytes
import dev.mirutime.app.core.RepoFetch
import dev.mirutime.app.core.SafeHttp
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.di.host.AndroidFileSystem
import dev.mirutime.app.di.host.AndroidSystemInfo
import dev.mirutime.app.di.host.AppCookieStore
import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.app.di.host.PrefsKeyValueStore
import dev.mirutime.app.features.finder.domain.isNewerVersion
import dev.mirutime.app.features.network.cf.AndroidCfChallengeSolver
import dev.mirutime.app.features.network.cf.HttpCfProbe
import dev.mirutime.app.features.network.cf.UrlConnectionProbeHttp
import dev.mirutime.app.features.network.webview.AndroidWebviewFetcher
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.translation.data.LocalNetworkAccess
import dev.mirutime.app.features.finder.domain.PluginFileInstall
import dev.mirutime.app.features.finder.domain.PluginFileRejection
import dev.mirutime.app.features.finder.domain.PluginResetResult
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginDescriptor
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginHandle
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginRegistry
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.PluginState
import dev.mirutime.core.api.SUPPORTED_PLUGIN_API
import dev.mirutime.core.engine.adapter.LuaPluginSourceFactory
import dev.mirutime.core.host.CfChallengeSolver
import dev.mirutime.core.installer.IndexEntry
import dev.mirutime.core.installer.InstallResult
import dev.mirutime.core.installer.LocalPluginFailure
import dev.mirutime.core.installer.LocalPluginInspection
import dev.mirutime.core.installer.PluginIndex
import dev.mirutime.core.installer.PluginInstaller
import dev.mirutime.core.installer.ScriptMetadataParser
import dev.mirutime.core.installer.SourceFetch
import java.nio.charset.Charset
import dev.mirutime.core.installer.parsePluginIndex
import dev.mirutime.core.installer.resolveRequireLibClosure
import dev.mirutime.core.installer.toPluginMetadata
import dev.mirutime.core.network.HostOptionsRegistry
import dev.mirutime.core.network.NetworkTraceEvent
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.PluginUserAgentRegistry
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.core.network.createEngine
import dev.mirutime.core.network.formatNetworkTrace
import dev.mirutime.core.network.safeHostPath
import dev.mirutime.core.network.security.SsrfGuard
import dev.mirutime.core.network.pluginCharset
import dev.mirutime.imageloader.FetchedImage
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// One installed plugin as the runtime tracks it. `error` carries the load
// failure message so the UI can show a broken plugin instead of silently
// dropping it (a failed plugin never becomes a CatalogSource).
data class InstalledPlugin(
    val id: String,
    val name: String,
    val language: String,
    val version: String,
    val contentType: ContentType,
    val enabled: Boolean,
    val error: String?,
    // Typed cause behind `error`; the health sheet renders it directly.
    val errorType: PluginError? = null,
    // The plugin's declared site URL (metadata.baseUrl), used by Finder's
    // globe and the per-plugin settings sheet.
    val baseUrl: String = "",
    // Raw `icon` global from the script header; resolved against baseUrl when
    // a list needs the avatar. Null = the plugin declares no icon.
    val icon: String? = null,
)

// Result of one repository install pass. `failed` maps id -> reason.
data class InstallReport(
    val installed: List<String>,
    val failed: List<Pair<String, String>>,
)

// Repository catalog as the Manage extensions screen sees it. `error` is
// non-null when the root index (or a language index) could not be fetched;
// callers still get every entry that was fetched successfully. IndexEntry
// already carries the fields the UI needs (id/name/version/url/contentType/
// language).
data class RepoCatalog(
    val entries: List<IndexEntry> = emptyList(),
    // Root index `libraries:` section: shared Lua libs require_lib resolves.
    val libraries: List<IndexEntry> = emptyList(),
    val error: String? = null,
)

// One language index fetch result; failures stay isolated so a single broken
// language never aborts the rest of the catalog.
internal data class LanguageLoad(
    val entries: List<IndexEntry>,
    val libraries: List<IndexEntry>,
    val error: String?,
)

// Root catalog plus every successfully fetched language index. Libraries from
// BOTH levels are kept: a language index may publish its own require_lib set,
// and install/update/reset/refresh resolve against this one list.
internal fun mergeRepoCatalog(root: PluginIndex, loads: List<LanguageLoad>): RepoCatalog {
    val entries = loads.flatMapTo(mutableListOf()) { it.entries }
    // Older roots carry `sources:` directly instead of language files.
    for (entry in root.sources) {
        entries += entry.copy(
            language = entry.language?.trim()?.takeIf { it.isNotEmpty() } ?: "en",
        )
    }
    val libraries = (root.libraries + loads.flatMap { it.libraries }).distinctBy { it.id }
    return RepoCatalog(
        entries = entries.distinctBy { it.id },
        libraries = libraries,
        error = loads.mapNotNull { it.error }.joinToString("; ").takeIf { it.isNotEmpty() },
    )
}

/** Host text fetch with the HTTP status/error kept for typed download failures. */
data class TextFetchResult(
    val body: String?,
    val status: Int,
    val error: Throwable? = null,
)

// App-scoped plugin runtime: owns one HostOptionsRegistry shared by the
// factory and the registry (registering them separately makes the per-host
// CF/UA presets silently dead), one installer and one registry. Sources are
// built lazily on first use; the plugin scripts live under
// filesDir/mirutime_plugins/lua_plugins and shared libraries under
// .../lua_libs.
class PluginGraph(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val systemInfo = AndroidSystemInfo
    private val appContext = context.applicationContext
    private val cookieStore = AppCookieStore.get()
    private val keyValueStore = PrefsKeyValueStore(context)
    private val fileSystem = AndroidFileSystem(File(context.filesDir, ROOT_DIR))
    private val repoStore = RepoCatalogStore(fileSystem)
    private val engine = createEngine(MiruCoreLogger)
    // System-DNS engine: repo/update (and, later, images/translation) must
    // reach LAN/private hosts. The plugin engine keeps SafeDns; this one is
    // shared, never created twice.
    internal val plainEngine = createEngine(MiruCoreLogger, safeDns = false)
    private val hostOptions = HostOptionsRegistry()
    // ONE CF gate for the whole process: the host fetcher, every Lua client
    // (via LuaPluginSourceFactory), the translation graphs and plain
    // repo/image clients. A cooldown entered by one request fast-fails every
    // other request to the same normalized host instead of opening a second
    // WebView. Accessor is internal: it is app wiring, not a public API.
    internal val cfGate = CfGate()
    // Plugin HTTP: URL-only. Hostname -> address checks run in the OkHttp
    // engine's SafeDns hook, so a broken pre-flight DNS lookup can no longer
    // reject a request the engine would have completed.
    private val ssrfGuard = SsrfGuard()
    // WebView/CF have no SafeDns equivalent: keep the pre-connect address check
    // (fail closed on DNS failure) for those surfaces.
    private val cfSsrfGuard = SsrfGuard(SafeHttp.resolver)
    // Repo/index/install and app-update fetches: one plain-engine client with
    // the shared gate and cookie store, 5 MB body cap and 10 redirect hops.
    // No solver: these lanes must never open a WebView (every call also passes
    // solveChallenges=false). URL policy still runs on each hop.
    private val remoteClient = PluginHttpClient(
        metadata = HOST_METADATA,
        systemInfo = systemInfo,
        cookieStore = cookieStore,
        logger = MiruCoreLogger,
        engine = plainEngine,
        gate = cfGate,
        maxResponseBytes = MAX_BYTES,
        maxRedirects = REPO_MAX_REDIRECTS,
    )
    private val repoFetch = RepoFetch(::fetchRemoteBytes)
    // One visible WebView challenge at a time; the same solver is shared by
    // the host fetcher and every plugin client so a clearance obtained for one
    // request is available to the other (both use the same cookie store).
    // Exposed inside the app module for the notification "Open source" bridge.
    internal val cfSolver: CfChallengeSolver = AndroidCfChallengeSolver(
        context = context.applicationContext,
        probe = HttpCfProbe(UrlConnectionProbeHttp()),
        enabled = { AppSettings.cloudflareBypass },
    )
    // Host-side fetcher (chapter pages): shares the engine, cookie store and
    // per-host UA/Referer registry with the plugin runtime, so a plugin's own
    // headers apply to the pages the host downloads on its behalf.
    // Debug builds emit one redacted `MiruTime.net` line per attempt; release
    // passes null, so the client computes no trace fields at all.
    private val networkTrace: ((NetworkTraceEvent) -> Unit)? = if (BuildConfig.DEBUG) {
        { event -> MiruCoreLogger.d("net", formatNetworkTrace(event)) }
    } else {
        null
    }
    // Plugin images (covers, source icons): same plain engine, gate, cookie
    // store and per-host UA/Referer registry as repo/update, capped at
    // MAX_IMAGE_BYTES. No solver and ssrfGuard=null: an image is never a
    // CF-solve trigger, and every call passes solveChallenges=false.
    private val imageClient = PluginHttpClient(
        metadata = HOST_METADATA,
        systemInfo = systemInfo,
        cookieStore = cookieStore,
        logger = MiruCoreLogger,
        engine = plainEngine,
        gate = cfGate,
        ssrfGuard = null,
        maxResponseBytes = MAX_IMAGE_BYTES,
        hostOptions = hostOptions,
        trace = networkTrace,
    )
    private val hostClient = PluginHttpClient(
        metadata = HOST_METADATA,
        systemInfo = systemInfo,
        cookieStore = cookieStore,
        logger = MiruCoreLogger,
        engine = engine,
        gate = cfGate,
        solver = cfSolver,
        ssrfGuard = ssrfGuard,
        hostOptions = hostOptions,
        trace = networkTrace,
    )
    private val sourceFactory = LuaPluginSourceFactory(
        systemInfo = systemInfo,
        cookieStore = cookieStore,
        keyValueStore = keyValueStore,
        logger = MiruCoreLogger,
        engine = engine,
        fileSystem = fileSystem,
        libDir = LIB_DIR,
        hostOptions = hostOptions,
        gate = cfGate,
        solver = cfSolver,
        ssrfGuard = ssrfGuard,
        trace = networkTrace,
        // Hidden WebView per source (dood's Turnstile click); the fetcher
        // resolves the plugin's UA preset, so it is created per source id.
        webviewFetcher = { sourceId ->
            AndroidWebviewFetcher(
                context = context.applicationContext,
                sourceId = sourceId,
                ssrfGuard = cfSsrfGuard,
                // Same process gate as the HTTP lane: a host already cooling
                // down returns null instead of opening a second hidden page.
                cfGate = cfGate,
            )
        },
    )
    private val registry = PluginRegistry(sourceFactory, hostOptions)
    private val installer = PluginInstaller(
        fileSystem = fileSystem,
        fetcher = ::fetchBytes,
        installDir = INSTALL_DIR,
        libDir = LIB_DIR,
        // URL policy only: the engine's SafeDns hook owns address checks in the
        // app lane, so the installer's URL-only guard is off here.
        ssrfGuard = null,
    )

    init {
        // Settings -> Network UA is the app-wide fallback for every request
        // (host option > plugin preset > this > browser default). The lambda
        // reads the live setting, so edits apply without a restart and the CF
        // WebView resolves the same value via userAgentFor().
        PluginUserAgentRegistry.setAppUserAgentProvider { AppSettings.userAgent }
    }

    private val handles = mutableMapOf<String, PluginHandle>()
    // Guards `handles`: sourceOnIo runs on IO while uninstall/install-entry run
    // on main, so duplicate installs (and dispose-while-in-use) must serialize.
    private val handlesMutex = Mutex()
    // Runtime load failures survive rescans (scan only sees disk and would
    // otherwise wipe them) until a successful load or reinstall clears them.
    private val runtimeErrors = ConcurrentHashMap<String, PluginError>()
    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    // Persisted so a custom repository survives process restarts; blank or
    // missing storage falls back to the canonical default.
    private val _repoUrl = MutableStateFlow(
        keyValueStore.get(REPO_NS, REPO_KEY)?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_REPO_URL,
    )

    // Successful catalog fetches only (memory mirror of the disk snapshot);
    // setRepoUrl invalidates it. Volatile because setRepoUrl runs on the main
    // thread while fetches run on IO.
    @Volatile
    private var repoCache: CachedRepoCatalog? = null
    private val repoMutex = Mutex()

    // Disk scan is a one-time bootstrap cost. installedNow() reuses the
    // completed scan; a caller before bootstrap still gets exactly one scan.
    @Volatile
    private var scanned = false
    private val scanMutex = Mutex()

    fun repoUrl(): String = _repoUrl.value

    // Per-host Referer/User-Agent for a cover URL, read from the same registry
    // the engine uses for plugin requests (hotlink-protected images). A host
    // with no registered UA still gets the browser default (never a bare
    // okhttp UA); an unparseable URL gets no headers. Never throws.
    fun imageHeadersFor(url: String): Map<String, String> = imageHeadersFor(url, hostOptions)

    /**
     * Headers for a media3 request: the plugin's per-host Referer/UA plus the
     * shared cookie store as one Cookie header. The player applies the map to
     * the playlist/file and every HLS/DASH segment, matching the plugin video
     * contract (`headers` are per stream, cookies come from the app store).
     * Never throws; a missing cookie simply means an unauthenticated stream.
     */
    suspend fun streamHeadersFor(url: String): Map<String, String> = withContext(Dispatchers.IO) {
        val headers = LinkedHashMap<String, String>()
        headers.putAll(imageHeadersFor(url))
        val cookies = runCatching { cookieStore.get(url) }.getOrDefault(emptyList())
        if (cookies.isNotEmpty()) {
            headers.putIfAbsent(
                "Cookie",
                cookies.joinToString("; ") { "${it.name}=${it.value}" },
            )
        }
        headers
    }

    // Hotlink-protected covers (hitomi's *.gold-usergeneratedcontent.net) need
    // the plugin's own base URL as Referer. Register it per cover host so
    // imageHeadersFor() supplies it at load time. Never throws.
    fun registerCoverReferers(sourceId: String, coverUrls: List<String>, baseUrl: String) {
        val referer = baseUrl.trim()
        if (referer.isEmpty()) return
        for (coverUrl in coverUrls) {
            if (coverUrl.isBlank()) continue
            val host = runCatching { URI(coverUrl).host.orEmpty() }
                .getOrNull()
                ?.let(::normalizeImageHost)
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            hostOptions.setReferer(host, sourceId, referer)
        }
    }

    // Host-resolved browser UA for the out-of-band CF bridge ("Open source"):
    // the WebView must present the same UA the plugin/host client sends, or
    // the cf_clearance it yields is bound to a different fingerprint. Uses the
    // same precedence as PluginRequestBuilder for the host client (per-host
    // override, then the UA preset registry, then the browser default).
    fun userAgentFor(url: String): String? {
        val host = runCatching { URI(url).host.orEmpty() }
            .getOrNull()
            ?.let(::normalizeImageHost)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return hostOptions.userAgent(host) ?: PluginUserAgentRegistry.resolve(null, host)
    }

    val defaultRepoUrl: String get() = DEFAULT_REPO_URL

    fun setRepoUrl(url: String) {
        val normalized = url.trim().ifBlank { DEFAULT_REPO_URL }
        _repoUrl.value = normalized
        keyValueStore.set(REPO_NS, REPO_KEY, normalized)
        repoCache = null
    }

    // Runs the initial disk scan once, off the caller's thread.
    fun bootstrap() {
        scope.launch { scan() }
    }

    // Enumerates installed scripts and rebuilds the list. Never throws: a
    // broken header only means a default metadata fallback for that file.
    // Serialized so installedNow() cannot race the bootstrap scan into a
    // second pass.
    suspend fun scan() = withContext(Dispatchers.IO) {
        scanMutex.withLock { scanOnIo() }
    }

    // Installed rows for the local-first paint: no network, and no redundant
    // rescan once bootstrap completed.
    suspend fun installedNow(): List<InstalledPlugin> {
        if (!scanned) scan()
        return installed.value
    }

    private suspend fun scanOnIo() {
        val plugins = fileSystem.list(INSTALL_DIR)
            .filter { it.endsWith(".lua") }
            .mapNotNull { path ->
                val id = path.substringAfterLast('/').removeSuffix(".lua").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val bytes = fileSystem.read(path) ?: return@mapNotNull null
                val code = bytes.decodeToString()
                val metadata = runCatching {
                    ScriptMetadataParser.parse(code)
                        .toPluginMetadata(id, SUPPORTED_PLUGIN_API, sha256Hex(bytes))
                }.getOrNull() ?: fallbackMetadata(id, bytes)
                // Compile-only check (never calls the chunk): a syntax-broken
                // file shows up as unhealthy immediately after startup/scan,
                // not only when a source is first opened.
                val error = runtimeErrors[id] ?: installer.validateScript(code, id)
                    ?.let { PluginError.ScriptError(it) }
                InstalledPlugin(
                    // The file stem is the canonical id: it is the installer's
                    // storage key and the id the index published. metadata.id
                    // inside the script can differ in case, which would break
                    // the file lookup in source().
                    id = id,
                    name = metadata.name,
                    language = metadata.language,
                    version = metadata.version,
                    contentType = metadata.contentType,
                    enabled = keyValueStore.get(ENABLED_NS, id)?.toBooleanStrictOrNull() ?: true,
                    error = error?.let { errorMessage(id, it) },
                    errorType = error,
                    baseUrl = metadata.baseUrl,
                    icon = metadata.icon,
                )
            }
        _installed.value = plugins.sortedBy { it.name.lowercase() }
        scanned = true
        MiruLog.event(
            "plugins",
            "scan",
            "installed" to plugins.size,
            "broken" to plugins.count { it.error != null },
            "enabled" to plugins.count { it.enabled },
        )
    }

    // Memory/disk repository catalog with no network and no TTL. Used for the
    // local-first paint; a foreign-URL or corrupt file is simply empty.
    suspend fun repoCatalogCached(): RepoCatalog = withContext(Dispatchers.IO) {
        val indexUrl = repoUrl()
        val memory = repoCache
        if (memory != null && memory.repoUrl == indexUrl) return@withContext memory.catalog
        val disk = repoStore.read(indexUrl) ?: return@withContext RepoCatalog()
        repoCache = disk
        disk.catalog
    }

    // Repository catalog for the extension lists: the root index plus every
    // language index it publishes. A fresh memory/disk catalog wins; otherwise
    // the network is consulted. Partial success (some language index failed but
    // entries arrived) is cached too. Never throws: failures land in `error`,
    // cancellation is propagated.
    suspend fun repoCatalog(force: Boolean = false): RepoCatalog = withContext(Dispatchers.IO) {
        repoMutex.withLock {
            val indexUrl = repoUrl()
            val cached = repoCache?.takeIf { it.repoUrl == indexUrl }
                ?: repoStore.read(indexUrl)?.also { repoCache = it }
            if (!force && cached != null &&
                AppCacheConfig.isFresh(cached.storedAt, AppCacheConfig.REPO_CACHE_TTL_MS)
            ) {
                MiruLog.event("plugins", "repo-cache", "hit" to true, "entries" to cached.catalog.entries.size)
                return@withLock cached.catalog
            }
            MiruLog.event("plugins", "repo-cache", "hit" to false, "force" to force)
            val loaded = loadRepoCatalog(indexUrl)
            // Skip the write when the URL changed mid-fetch (the newer URL's
            // fetch must win, not this stale one) and when nothing arrived.
            if ((loaded.entries.isNotEmpty() || loaded.libraries.isNotEmpty()) && repoUrl() == indexUrl) {
                val snapshot = CachedRepoCatalog(
                    repoUrl = indexUrl,
                    storedAt = System.currentTimeMillis(),
                    catalog = loaded,
                )
                repoCache = snapshot
                repoStore.write(snapshot)
            }
            loaded
        }
    }

    private suspend fun loadRepoCatalog(indexUrl: String): RepoCatalog {
        val root = try {
            parsePluginIndex(fetchBytes(indexUrl).decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "failed to fetch $indexUrl"
            MiruCoreLogger.e(TAG, "repo catalog failed: $reason", e)
            return RepoCatalog(error = reason)
        }
        // Every published language is fetched concurrently; one failing index
        // is recorded and the remaining languages still land in the catalog.
        val loads = coroutineScope {
            root.languages.map { language ->
                async {
                    val code = language.id.trim().lowercase()
                    try {
                        val index = parsePluginIndex(fetchBytes(language.url).decodeToString())
                        LanguageLoad(
                            entries = index.sources.map { entry ->
                                entry.copy(
                                    language = entry.language?.trim()?.takeIf { it.isNotEmpty() } ?: code,
                                )
                            },
                            libraries = index.libraries,
                            error = null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val reason = e.message ?: "failed to fetch ${language.url}"
                        MiruCoreLogger.e(TAG, "repo language '$code' failed: $reason", e)
                        LanguageLoad(entries = emptyList(), libraries = emptyList(), error = "${language.id}: $reason")
                    }
                }
            }.awaitAll()
        }
        return mergeRepoCatalog(root, loads)
    }

    // Installs exactly one repository entry. Unknown ids fail cleanly; when
    // the whole catalog is empty because its fetch failed (LAN grant, offline),
    // that real reason wins over a misleading "not found".
    suspend fun install(id: String): InstallReport = withContext(Dispatchers.IO) {
        val catalog = repoCatalog()
        val entry = catalog.entries.firstOrNull { it.id == id }
        if (entry == null) {
            val reason = catalog.error?.takeIf { catalog.entries.isEmpty() }
                ?: buildString {
                    append("plugin '$id' not found in repository")
                    catalog.error?.let { append(": ").append(it) }
                }
            MiruCoreLogger.w(TAG, reason)
            return@withContext InstallReport(emptyList(), listOf(id to reason))
        }
        installEntries(listOf(entry), catalog.libraries)
    }

    // Reinstalls only entries whose catalog version is strictly newer than the
    // installed one; plugins removed from the repository are left untouched.
    suspend fun updateAllInstalled(): InstallReport = withContext(Dispatchers.IO) {
        val installedVersions = installed.value.associate { it.id to it.version }
        val catalog = repoCatalog()
        val targets = catalog.entries.filter { entry ->
            isNewerVersion(entry.version, installedVersions[entry.id])
        }
        if (targets.isEmpty()) {
            val failed = catalog.error?.let { listOf(REPO_FAILURE_KEY to it) }.orEmpty()
            return@withContext InstallReport(emptyList(), failed)
        }
        installEntries(targets, catalog.libraries)
    }

    // Installs a .lua the user picked through SAF. Validation (size, UTF-8,
    // id, syntax) runs before any write; an existing id is never overwritten.
    suspend fun installFromFile(fileName: String, bytes: ByteArray): PluginFileInstall =
        withContext(Dispatchers.IO) {
            when (val inspection = installer.inspectLocal(fileName, bytes)) {
                is LocalPluginInspection.Invalid -> PluginFileInstall.Rejected(
                    reason = inspection.reason.toFileRejection(),
                    detail = inspection.detail,
                )
                is LocalPluginInspection.Valid -> {
                    if (fileSystem.exists("$INSTALL_DIR/${inspection.id}.lua")) {
                        return@withContext PluginFileInstall.Rejected(
                            PluginFileRejection.DUPLICATE,
                            "plugin '${inspection.id}' is already installed",
                        )
                    }
                    when (val result = installer.commitLocal(inspection, bytes)) {
                        is InstallResult.Success -> {
                            runtimeErrors.remove(inspection.id)
                            keyValueStore.set(ENABLED_NS, inspection.id, true.toString())
                            handlesMutex.withLock { handles.remove(inspection.id)?.dispose() }
                            scan()
                            MiruLog.event("plugins", "install-file", "ok" to true, "plugin" to inspection.id)
                            PluginFileInstall.Installed(inspection.id, inspection.name)
                        }
                        is InstallResult.Failed -> PluginFileInstall.Rejected(
                            PluginFileRejection.WRITE,
                            result.reason,
                        )
                    }
                }
            }
        }

    // Reinstalls the repository copy of a broken plugin. Only meaningful when
    // the repo still publishes the id; callers gate the action on that.
    suspend fun resetFromRepo(id: String): PluginResetResult = withContext(Dispatchers.IO) {
        val catalog = repoCatalog()
        val entry = catalog.entries.firstOrNull { it.id == id }
            ?: return@withContext PluginResetResult(
                success = false,
                // A failed catalog fetch is the real reason, not a missing id.
                detail = catalog.error?.takeIf { catalog.entries.isEmpty() }
                    ?: "plugin '$id' is not in the repository",
            )
        val report = installEntries(listOf(entry), catalog.libraries)
        val installed = id in report.installed
        if (installed) {
            keyValueStore.set(ENABLED_NS, id, true.toString())
            // installEntries already dropped the loaded handle and rescanned.
        }
        val detail = report.failed.joinToString("; ") { "${it.first}: ${it.second}" }
        MiruLog.event("plugins", "reset", "ok" to (installed && report.failed.isEmpty()), "plugin" to id)
        PluginResetResult(
            success = installed && report.failed.isEmpty(),
            detail = detail,
        )
    }

    // Read-only script text for the viewer; null when the file is gone.
    suspend fun scriptText(id: String): String? = withContext(Dispatchers.IO) {
        fileSystem.read("$INSTALL_DIR/$id.lua")?.decodeToString()
    }

    // Installs exactly the given library entries (a resolved require_lib
    // closure) and reports every failure. Unchanged libs are a no-op inside
    // the installer (sha match = no network). Any lib that actually changed
    // invalidates all loaded handles: they compiled and bound the previous
    // content, so new sessions must reload from disk. Targeted by design:
    // callers pass the closure, never the repository's whole library list.
    internal suspend fun syncLibraries(libraries: List<IndexEntry>): List<Pair<String, String>> {
        if (libraries.isEmpty()) return emptyList()
        val pending = librariesNeedingInstall(libraries, installer::installedLibrarySha256)
            .mapTo(mutableSetOf()) { it.id }
        val results = try {
            installer.installLibraries(libraries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "library install failed"
            MiruCoreLogger.e(TAG, "library install failed: $reason", e)
            return libraries.map { it.id to reason }
        }
        val failed = mutableListOf<Pair<String, String>>()
        var reload = false
        libraries.zip(results).forEach { (entry, result) ->
            when (result) {
                is InstallResult.Success -> if (entry.id in pending) reload = true
                is InstallResult.Failed -> {
                    failed += entry.id to result.reason
                    MiruCoreLogger.w(TAG, "library '${entry.id}' failed: ${result.reason}")
                }
            }
        }
        if (reload) {
            handlesMutex.withLock {
                handles.values.forEach { it.dispose() }
                handles.clear()
            }
            MiruLog.event("plugins", "libs-reload", "libraries" to pending.size)
        }
        return failed
    }

    // Refresh/URL-change path: only the installed scripts are scanned, and
    // only their require_lib closure is synced. Libraries used by catalog
    // entries that are not installed are never downloaded.
    internal suspend fun syncLibrariesForInstalled(
        libraries: List<IndexEntry>,
    ): List<Pair<String, String>> {
        val scripts = fileSystem.list(INSTALL_DIR)
            .filter { it.endsWith(".lua") }
            .mapNotNull { fileSystem.read(it)?.decodeToString() }
        return syncInstalledLibraryClosure(scripts, libraries, ::fetchBytes, ::syncLibraries)
    }

    private suspend fun installEntries(
        entries: List<IndexEntry>,
        libraries: List<IndexEntry>,
    ): InstallReport {
        val installed = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val pending = mutableListOf<Pair<IndexEntry, SourceFetch.Success>>()
        for (entry in entries) {
            val source = try {
                installer.fetchSource(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceFetch.Failed(e.message ?: "install failed")
            }
            when (source) {
                is SourceFetch.Failed -> {
                    failed += entry.id to source.reason
                    MiruCoreLogger.w(TAG, "install '${entry.id}' failed: ${source.reason}")
                }
                is SourceFetch.Success -> {
                    pending += entry to source
                }
            }
        }
        if (pending.isNotEmpty()) {
            // Library code is fetched once per URL and reused by every
            // script's own closure resolution (per-script attribution: one
            // broken dependency must not fail unrelated plugins).
            val libraryCode = mutableMapOf<String, String>()
            val libraryErrors = mutableMapOf<String, String>()
            val cachedFetch: suspend (String) -> ByteArray = { libUrl ->
                libraryErrors[libUrl]?.let { error("library fetch failed: $it") }
                libraryCode[libUrl]?.let { it.encodeToByteArray() } ?: run {
                    try {
                        fetchBytes(libUrl).also { libraryCode[libUrl] = it.decodeToString() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        libraryErrors[libUrl] = e.message ?: "fetch failed"
                        throw e
                    }
                }
            }
            for ((entry, source) in pending) {
                val result = installWithLibraries(
                    scriptCode = source.code,
                    libraries = libraries,
                    fetch = cachedFetch,
                    installLibraries = ::syncLibraries,
                    commit = { installer.commitSource(entry, source) },
                )
                when (result) {
                    is InstallResult.Success -> {
                        installed += entry.id
                        runtimeErrors.remove(entry.id)
                        // Drop the loaded handle so an update takes effect on
                        // the next source() instead of after a process restart.
                        handlesMutex.withLock { handles.remove(entry.id)?.dispose() }
                    }
                    is InstallResult.Failed -> {
                        failed += entry.id to result.reason
                        MiruCoreLogger.w(TAG, "install '${entry.id}' failed: ${result.reason}")
                    }
                }
            }
        }
        scan()
        MiruLog.event(
            "plugins",
            "install",
            "ok" to failed.isEmpty(),
            "installed" to installed.size,
            "failed" to failed.size,
        )
        return InstallReport(installed, failed)
    }

    // Lazily loads a plugin on first use. Returns null (never throws) on any
    // failure, recording the message on the installed entry for the UI.
    // Off the main thread: the plugin chunk and its sandbox run here.
    suspend fun source(id: String): PluginSource? = withContext(Dispatchers.IO) { sourceOnIo(id) }

    private suspend fun sourceOnIo(id: String): PluginSource? = handlesMutex.withLock {
        // Only a successfully loaded source is cached; a failed handle is
        // retried so a reinstall can recover without an app restart.
        handles[id]?.source?.let { return it }
        val bytes = fileSystem.read("$INSTALL_DIR/$id.lua")
        if (bytes == null) {
            recordError(id, PluginError.ScriptError("plugin file missing"))
            return null
        }
        return try {
            val code = bytes.decodeToString()
            val metadata = ScriptMetadataParser.parse(code)
                .toPluginMetadata(id, SUPPORTED_PLUGIN_API, sha256Hex(bytes))
            // The Manage-extensions override must drive the runtime too, not
            // just the globe: requests and chapter-URL resolution go through
            // this metadata.
            val override = baseUrlOverride(id)
            val descriptor = PluginDescriptor(
                metadata = if (override != null) metadata.copy(baseUrl = override) else metadata,
                script = code,
            )
            val handle = registry.install(descriptor)
            handles[id] = handle
            when (val state = handle.state.value) {
                is PluginState.Failed -> {
                    recordError(id, state.error ?: PluginError.ScriptError(state.reason))
                    MiruCoreLogger.e(TAG, "plugin '$id' failed to load: ${state.reason}", null)
                    null
                }
                PluginState.Loaded -> {
                    clearError(id)
                    handle.source
                }
            }
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "load failed"
            recordError(id, PluginError.ScriptError(reason))
            MiruCoreLogger.e(TAG, "plugin '$id' failed to load", e)
            null
        }
    }

    // Fetches a page's body text on the host's behalf — the chapter-reader
    // contract: the host downloads the HTML, then the plugin parses it. Returns
    // null on any failure (never throws). Off the main thread.
    suspend fun fetchText(sourceId: String, url: String, charset: String? = null): String? =
        fetchTextResult(sourceId, url, charset).body

    suspend fun fetchTextResult(
        sourceId: String,
        url: String,
        charset: String? = null,
    ): TextFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val response = hostClient.get(
                    sourceId,
                    url,
                    operation = "fetchText",
                    argNames = listOf("url"),
                )
                val code = response.status.value
                if (code !in 200..299) {
                    MiruCoreLogger.w(TAG, "fetchText HTTP $code for ${safeHostPath(url)}")
                    TextFetchResult(body = null, status = code)
                } else {
                    val bytes = response.bodyAsBytes()
                    // Declared plugin charset first (69shuba: GBK), then the
                    // server's Content-Type charset, then UTF-8.
                    val body = decodeFetchedText(bytes, charset, response.headers[HttpHeaders.ContentType])
                    TextFetchResult(body = body, status = code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ktor exceptions embed the full URL in their message:
                // log the class only, never the throwable.
                MiruCoreLogger.e(TAG, "fetchText failed for ${safeHostPath(url)} error=${e::class.simpleName}")
                TextFetchResult(body = null, status = 0, error = e)
            }
        }

    // Host-side image fetch for library covers. Same plain-engine client,
    // cookie store and per-host UA/Referer registry as fetchText; the body is
    // returned only for 2xx so a 304/404 never leaks an HTML error page into
    // the cover store. Transport failures surface as status 0 (transient),
    // never as a throw.
    // A detected challenge is returned as-is (solveChallenges = false): images
    // are not a CF-solve trigger, and the store already applies the domain-aware
    // cookies the WebView wrote.
    suspend fun fetchImage(
        sourceId: String,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = COVER_FETCH_TIMEOUT_MS,
    ): FetchedImage = withContext(Dispatchers.IO) {
        try {
            val response = imageClient.get(
                sourceId,
                url,
                headers,
                // One attempt inside ten seconds: a cover is not worth the
                // status ladder, and a dead CDN must not hold the grid.
                timeoutMs = timeoutMs,
                operation = "fetchImage",
                argNames = listOf("url"),
                solveChallenges = false,
            )
            val code = response.status.value
            FetchedImage(
                status = code,
                bytes = if (code in 200..299) response.bodyAsBytes() else null,
                etag = response.headers["ETag"],
                lastModified = response.headers["Last-Modified"],
                headers = response.headers.entries()
                    .associate { (name, values) -> name to values.joinToString(", ") },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MiruCoreLogger.e(TAG, "fetchImage failed for ${safeHostPath(url)} error=${e::class.simpleName}")
            FetchedImage(status = 0)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        keyValueStore.set(ENABLED_NS, id, enabled.toString())
        _installed.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    // Per-plugin base URL edited in Manage extensions. Kept beside the
    // installed scripts so the override survives rescans and reinstalls.
    fun baseUrlOverride(id: String): String? =
        keyValueStore.get(BASE_URL_NS, id)?.trim()?.takeIf { it.isNotEmpty() }

    fun setBaseUrlOverride(id: String, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            keyValueStore.remove(BASE_URL_NS, id)
        } else {
            keyValueStore.set(BASE_URL_NS, id, trimmed)
        }
    }

    suspend fun invalidateSource(id: String) {
        handlesMutex.withLock { handles.remove(id)?.dispose() }
    }

    /**
     * Drops every loaded handle and rescans disk. Used after a restore writes
     * plugin files so the UI and the runtime see them immediately.
     */
    suspend fun reload() {
        handlesMutex.withLock {
            handles.values.forEach { it.dispose() }
            handles.clear()
        }
        runtimeErrors.clear()
        repoCache = null
        scan()
    }

    suspend fun uninstall(id: String) {
        handlesMutex.withLock { handles.remove(id)?.dispose() }
        runtimeErrors.remove(id)
        fileSystem.delete("$INSTALL_DIR/$id.lua")
        keyValueStore.remove(ENABLED_NS, id)
        _installed.update { list -> list.filterNot { it.id == id } }
    }

    // id -> load error for the plugins that failed; folded into InstalledPlugin
    // for the UI, exposed for any consumer that wants a flat map.
    fun loadErrors(): Map<String, String> =
        installed.value.mapNotNull { plugin -> plugin.error?.let { plugin.id to it } }.toMap()

    private fun recordError(id: String, error: PluginError) {
        runtimeErrors[id] = error
        val message = errorMessage(id, error)
        _installed.update { list ->
            list.map { if (it.id == id) it.copy(error = message, errorType = error) else it }
        }
    }

    private fun clearError(id: String) {
        runtimeErrors.remove(id)
        _installed.update { list ->
            list.map { if (it.id == id) it.copy(error = null, errorType = null) else it }
        }
    }

    // Raw byte fetch for repo/index/install/libs: plain engine (system DNS),
    // 5 MB body cap and a hand-walked redirect chain that keeps this lane's own
    // "HTTP <code> for <url>" / "too many redirects for <url>" strings.
    // Every repo lane (catalog, install, update, library sync) ends here, so
    // the Android 17+ LAN grant is checked once and fails before the transport
    // timeout instead of only on the catalog-refresh path.
    private suspend fun fetchBytes(url: String): ByteArray {
        if (LocalNetworkAccess.isRequired(url, Build.VERSION.SDK_INT, LocalNetworkAccess.isGranted(appContext))) {
            MiruLog.event("plugins", "repo-fetch", "ok" to false, "reason" to "local-network")
            throw IllegalStateException(appContext.getString(R.string.st_net_local_network_repo_required))
        }
        return repoFetch.fetch(url)
    }

    /**
     * One request over the plain remote client (shared gate/cookie store, no
     * solver, 5 MB cap). [followRedirects] = false lets [RepoFetch] own the
     * walk; the app-update lane follows the client's 10-hop chain instead.
     */
    suspend fun fetchRemote(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = TIMEOUT_MS,
        followRedirects: Boolean = true,
    ): RemoteBytes = withContext(Dispatchers.IO) {
        val response = remoteClient.get(
            sourceId = "remote",
            url = url,
            headers = headers,
            timeoutMs = timeoutMs,
            followRedirects = followRedirects,
            solveChallenges = false,
        )
        RemoteBytes(
            status = response.status.value,
            bytes = response.bodyAsBytes(),
            headers = response.headers.entries()
                .associate { (name, values) -> name to values.joinToString(", ") },
            finalUrl = response.request.url.toString(),
            location = response.headers["Location"],
        )
    }

    private suspend fun fetchRemoteBytes(url: String): RemoteBytes =
        fetchRemote(url, followRedirects = false)

    private fun fallbackMetadata(id: String, bytes: ByteArray): PluginMetadata = PluginMetadata(
        id = id,
        name = id,
        baseUrl = "",
        contentType = ContentType.NOVEL,
        language = "en",
        api = SUPPORTED_PLUGIN_API,
        version = "1.0.0",
        sha256 = sha256Hex(bytes),
    )
}

// Chapter-body decoding for the host fetch. Priority is the canonical
// pluginCharset order (plugin-declared charset, then the Content-Type one,
// then UTF-8): `fetchText` used to return bodyAsText(), which honored the
// server charset, so a GBK/Big5 page without a Lua `charset` global must not
// regress to mojibake. A charset name the JVM cannot resolve falls back to
// UTF-8 instead of failing the chapter.
internal fun decodeFetchedText(
    bytes: ByteArray,
    declaredCharset: String?,
    contentTypeHeader: String?,
): String {
    // ISO-8859-1 maps bytes 1:1, so an ASCII <meta charset> in the first KB
    // survives the sniff (pluginCharset reads at most 1024 chars).
    val htmlHead = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
    val name = pluginCharset(declaredCharset, contentTypeHeader, htmlHead)
    return runCatching { String(bytes, Charset.forName(name)) }
        .getOrElse { bytes.decodeToString() }
}

internal data class LibraryClosure(
    val entries: List<IndexEntry>,
    val failures: List<Pair<String, String>>,
)

// Fail-closed install of one already-fetched source: resolve its require_lib
// closure, install the dependencies, then commit. A missing/cyclic/failed
// library returns Failed WITHOUT writing the script — a committed script whose
// library never landed would look installed and then break at load time.
internal suspend fun installWithLibraries(
    scriptCode: String,
    libraries: List<IndexEntry>,
    fetch: suspend (String) -> ByteArray,
    installLibraries: suspend (List<IndexEntry>) -> List<Pair<String, String>>,
    commit: () -> InstallResult,
): InstallResult {
    val closure = try {
        resolveLibraryClosure(listOf(scriptCode), libraries, fetch)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return InstallResult.Failed("required library scan failed: ${e.message ?: "unknown"}")
    }
    if (closure.failures.isNotEmpty()) {
        return InstallResult.Failed(closure.failures.joinToString("; ") { "${it.first}: ${it.second}" })
    }
    val failures = try {
        installLibraries(closure.entries)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        listOf(REPO_FAILURE_KEY to (e.message ?: "library install failed"))
    }
    if (failures.isNotEmpty()) {
        return InstallResult.Failed(failures.joinToString("; ") { "${it.first}: ${it.second}" })
    }
    return commit()
}

// Resolves the require_lib closure of [scripts] against the catalog
// [libraries]. Libraries outside the closure are never fetched or installed.
// Missing ids and cycles become failures; the remaining entries stay in
// dependency order.
internal suspend fun resolveLibraryClosure(
    scripts: List<String>,
    libraries: List<IndexEntry>,
    fetch: suspend (String) -> ByteArray,
): LibraryClosure {
    val byId = libraries.associateBy { it.id }
    val fetchFailures = mutableMapOf<String, String>()
    val closure = resolveRequireLibClosure(scripts) { id ->
        val entry = byId[id] ?: return@resolveRequireLibClosure null
        try {
            fetch(entry.url).decodeToString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fetchFailures[id] = e.message ?: "fetch failed"
            null
        }
    }
    val failures = buildList {
        for (id in closure.missing) {
            add(
                id to (
                    fetchFailures[id]?.let { "library '$id' fetch failed: $it" }
                        ?: "library '$id' is not in the repository"
                    ),
            )
        }
        for (cycle in closure.cycles) {
            add(cycle.joinToString(" -> ") to "library cycle detected")
        }
    }
    return LibraryClosure(
        entries = closure.order.mapNotNull { byId[it] },
        failures = failures,
    )
}

// Installed-only refresh: sync the closure of the scripts that are actually on
// disk. A repository library no installed plugin requires is never downloaded.
internal suspend fun syncInstalledLibraryClosure(
    pluginScripts: List<String>,
    libraries: List<IndexEntry>,
    fetch: suspend (String) -> ByteArray,
    install: suspend (List<IndexEntry>) -> List<Pair<String, String>>,
): List<Pair<String, String>> {
    if (pluginScripts.isEmpty()) return emptyList()
    val closure = resolveLibraryClosure(pluginScripts, libraries, fetch)
    return closure.failures + install(closure.entries)
}

// Canonical plugin repository (external-sources, main branch).
const val DEFAULT_REPO_URL =
    "https://raw.githubusercontent.com/HnDK0/external-sources/main/index.yaml"

private const val TAG = "PluginGraph"
private const val ROOT_DIR = "mirutime_plugins"
private const val INSTALL_DIR = "lua_plugins"
private const val LIB_DIR = "lua_libs"
private const val ENABLED_NS = "plugins_enabled"
private const val BASE_URL_NS = "plugin_base_url"
private const val REPO_NS = "repo"
private const val REPO_KEY = "url"
// Key used in InstallReport.failed for the root index itself.
internal const val REPO_FAILURE_KEY = "<repo>"
private const val TIMEOUT_MS = 30_000L
// Covers/pages: short budget, one attempt (see fetchImage).
private const val COVER_FETCH_TIMEOUT_MS = 10_000L
private const val MAX_BYTES = 5L * 1024 * 1024
// Image bodies only: a cover at this size is far past any real page asset.
private const val MAX_IMAGE_BYTES = 16L * 1024 * 1024

// Generic metadata for HOST-initiated fetches (chapter pages): per-host UA and
// Referer come from HostOptionsRegistry, so this carries no plugin specifics.
private val HOST_METADATA = PluginMetadata(
    id = "host",
    name = "host",
    baseUrl = "",
    contentType = ContentType.NOVEL,
    language = "en",
    api = SUPPORTED_PLUGIN_API,
    version = "1.0.0",
    sha256 = "",
)

private fun LocalPluginFailure.toFileRejection(): PluginFileRejection = when (this) {
    LocalPluginFailure.EMPTY -> PluginFileRejection.EMPTY
    LocalPluginFailure.TOO_LARGE -> PluginFileRejection.TOO_LARGE
    LocalPluginFailure.INVALID_UTF8 -> PluginFileRejection.INVALID_UTF8
    LocalPluginFailure.INVALID_ID -> PluginFileRejection.INVALID_ID
    LocalPluginFailure.SYNTAX -> PluginFileRejection.SYNTAX
}

// Plain-text report line for the installed-list error gate and diagnostics; the
// health sheet renders errorType itself. Same builder as every surface, so the
// raw ScriptError.detail/Network.cause never reaches this string.
private fun errorMessage(id: String, error: PluginError): String =
    buildPluginErrorReport(error, sourceId = id, operation = "load").detailText

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// Pure decision: a library is pending when its catalog sha is absent (the
// installer reports the mandatory-sha failure) or differs from the installed
// file's sha.
internal fun librariesNeedingInstall(
    libraries: List<IndexEntry>,
    installedSha256: (String) -> String?,
): List<IndexEntry> = libraries.filter { entry ->
    val expected = entry.sha256
    expected == null || !expected.equals(installedSha256(entry.id), ignoreCase = true)
}

// Same lookup as PluginGraph.imageHeadersFor, kept pure so the header port can
// be tested without an Android Context or a built plugin graph.
internal fun imageHeadersFor(url: String, hostOptions: HostOptionsRegistry): Map<String, String> {
    val host = runCatching { URI(url).host.orEmpty() }
        .getOrNull()
        ?.let(::normalizeImageHost)
        .orEmpty()
    if (host.isEmpty()) return emptyMap()
    val headers = LinkedHashMap<String, String>(2)
    hostOptions.referer(host)?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
    headers["User-Agent"] = hostOptions.userAgent(host)?.takeIf { it.isNotBlank() }
        ?: PluginUserAgentRegistry.resolve(null, host)
    return headers
}

// Same host key the core network package uses (lowercase, drop "www.", drop
// the port) so a lookup hits the entries the engine registered.
private fun normalizeImageHost(raw: String): String =
    raw.lowercase().removePrefix("www.").substringBefore(":")