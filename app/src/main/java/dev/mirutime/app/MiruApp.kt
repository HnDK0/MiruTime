package dev.mirutime.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.snapshotFlow
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dev.mirutime.app.core.AndroidCoverDecoder
import dev.mirutime.app.core.RecoverySweeper
import dev.mirutime.core.log.CrashReporter
import dev.mirutime.core.log.MiruFileLogger
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.di.MigrationGraph
import dev.mirutime.app.di.PluginGraph
import dev.mirutime.app.di.ReaderGraph
import dev.mirutime.app.di.TranslationGraph
import dev.mirutime.app.di.host.AndroidFileSystem
import dev.mirutime.app.features.book.data.BookEpubExporter
import dev.mirutime.app.features.book.data.PluginBookDataProvider
import dev.mirutime.app.features.book.data.PluginBookIndex
import dev.mirutime.app.features.book.data.cachedTranslationRequest
import dev.mirutime.app.features.downloads.data.DownloadEngine
import dev.mirutime.app.features.downloads.data.DownloadNotifier
import dev.mirutime.app.features.downloads.data.DownloadNotifications
import dev.mirutime.app.features.downloads.data.LocalDownloadFileStore
import dev.mirutime.app.features.downloads.data.RoomDownloadStore
import dev.mirutime.app.features.downloads.data.WorkManagerDownloadScheduler
import dev.mirutime.app.features.finder.data.AndroidSafResolver
import dev.mirutime.app.features.finder.data.LocalCatalogMetadataCache
import dev.mirutime.app.features.finder.data.PluginFinderDataProvider
import dev.mirutime.app.features.finder.data.LocalSafBookSource
import dev.mirutime.app.features.finder.data.LocalSafCache
import dev.mirutime.app.features.finder.data.LocalSafRegistry
import dev.mirutime.app.features.finder.data.LocalSourceFolders
import dev.mirutime.app.features.library.data.BookCoverStore
import dev.mirutime.app.features.library.data.LibraryBookRefreshPort
import dev.mirutime.app.features.library.data.LibraryUpdateScheduler
import dev.mirutime.app.features.library.data.LocalBookImporter
import dev.mirutime.app.features.library.data.LocalBookProvider
import dev.mirutime.app.features.migration.data.AppSettingsMigrationSettings
import dev.mirutime.app.features.migration.data.BookMigrationTargets
import dev.mirutime.app.features.migration.data.DownloadFileStoreMigrationFiles
import dev.mirutime.app.features.migration.data.MigrationSearch
import dev.mirutime.app.features.migration.data.MigrationSourceGate
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.BackupRestoreService
import dev.mirutime.app.features.settings.data.BackupService
import dev.mirutime.app.features.settings.data.PrefsReaderBackupStore
import dev.mirutime.app.features.settings.data.SettingsStore
import dev.mirutime.app.features.settings.data.SharedPreferencesKeyValueStore
import dev.mirutime.app.features.settings.data.StorageUsageService
import dev.mirutime.app.features.settings.backup.AutoBackupScheduler
import dev.mirutime.app.features.translation.data.ChapterTranslationStatus
import dev.mirutime.app.features.translation.data.DownloadTranslationQueue
import dev.mirutime.app.features.translation.data.DownloadTranslationRunner
import dev.mirutime.app.features.translation.data.RoomTranslationCache
import dev.mirutime.app.features.translation.data.RoomTranslationQueueStore
import dev.mirutime.app.features.translation.data.WorkManagerTranslationScheduler
import dev.mirutime.app.features.translation.data.composePrompt
import dev.mirutime.app.features.translation.data.pluginIdOf
import dev.mirutime.app.features.translation.data.translationMode
import dev.mirutime.app.features.translation.domain.ManagerTranslationEngine
import dev.mirutime.app.features.updates.data.AppUpdateScheduler
import dev.mirutime.app.features.video.data.VideoDownloadManager
import dev.mirutime.data.library.LibraryDatabase
import dev.mirutime.data.library.BookWriteLocks
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.bookKeyUrl
import dev.mirutime.data.library.buildLibraryDatabase
import dev.mirutime.data.library.libraryDatabaseBuilder
import dev.mirutime.data.library.migration.BookMigrationCoordinator
import dev.mirutime.imageloader.ImageCacheConfig
import dev.mirutime.imageloader.ImageMemoryTrimPolicy
import dev.mirutime.imageloader.MemoryTrim
import dev.mirutime.imageloader.buildImageLoader
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MiruApp : Application(), SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settingsStore by lazy {
        SettingsStore(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(SettingsStore.PREFS_NAME, MODE_PRIVATE),
            ),
        )
    }
    val libraryDatabase: LibraryDatabase by lazy {
        // Only debug builds may drop an unmigratable pre-release database.
        // Release builds fail loudly instead of wiping the user's library.
        buildLibraryDatabase(
            libraryDatabaseBuilder(this),
            allowDestructiveFallback = BuildConfig.DEBUG,
        )
    }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(libraryDatabase.libraryDao(), bookLocks = bookWriteLocks) { bookIds ->
            // Deleting a book must cancel its unique work before the row
            // disappears; otherwise a worker can race the cascade.
            bookIds.forEach { bookId ->
                downloadScheduler.cancel(bookId)
                translationScheduler.cancel(bookId)
            }
        }
    }
    private val recoverySweeper by lazy {
        RecoverySweeper(
            filesDir = filesDir,
            cacheDir = cacheDir,
            loadImportJobs = libraryRepository::importJobs,
            saveImportJob = libraryRepository::saveImportJob,
            loadMigrations = libraryRepository::bookMigrations,
            saveMigration = libraryRepository::saveBookMigration,
        )
    }
    // Read-only L2 view for exports; the reader keeps its own instance, both
    // are stateless wrappers over the same Room tables.
    private val translationCache by lazy { RoomTranslationCache(libraryRepository) }
    // "Download + translate": intent and worker both persist in the existing
    // chapter_translations table, so no Room schema change is needed.
    private val translationQueueStore by lazy {
        RoomTranslationQueueStore(libraryRepository)
    }
    val downloadTranslationQueue: DownloadTranslationQueue by lazy {
        DownloadTranslationQueue(translationQueueStore)
    }
    private val translationScheduler by lazy { WorkManagerTranslationScheduler(this) }
    private val downloadScheduler by lazy { WorkManagerDownloadScheduler(this) }
    val downloadTranslationRunner: DownloadTranslationRunner by lazy {
        DownloadTranslationRunner(
            store = translationQueueStore,
            engine = ManagerTranslationEngine { translationGraph.manager },
            cache = translationCache,
            files = downloadFiles,
        )
    }
    val pluginGraph: PluginGraph by lazy {
        PluginGraph(this, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
    // Library covers: one local file per bookId, fetched through the plugin
    // graph so the per-host UA/Referer registry applies to hotlink-protected
    // images.
    val coverStore: BookCoverStore by lazy {
        BookCoverStore(
            root = File(filesDir, "covers"),
            load = libraryRepository::bookCover,
            save = libraryRepository::saveBookCover,
            fetch = pluginGraph::fetchImage,
            headersFor = pluginGraph::imageHeadersFor,
            isDecodable = AndroidCoverDecoder::isDecodable,
        )
    }
    // Real download queue: Room is the state source, one unique WorkManager
    // chain per book, bytes under filesDir/downloads. Not a singleton queue.
    private val downloadFiles by lazy { LocalDownloadFileStore(File(filesDir, "downloads")) }
    // One real book provider shared by the book screen and the reader.
    private val bookWriteLocks = BookWriteLocks()

    val pluginBookProvider: PluginBookDataProvider by lazy {
        PluginBookDataProvider(
            pluginGraph,
            libraryRepository,
            coverStore,
            local = localBookProvider,
            localSaf = localSafSource,
            chapterFiles = downloadFiles,
            onBookRemoved = { bookUrl -> removeVideoDownloads(setOf(bookUrl)) },
            bookLocks = bookWriteLocks,
        )
    }
    // One Finder provider shared by the Finder tab and migration search; it
    // owns plugin lookup, error mapping and membership.
    val finderProvider: PluginFinderDataProvider by lazy {
        PluginFinderDataProvider(
            graph = pluginGraph,
            library = libraryRepository,
            importer = localBookImporter,
            localLibrary = localBookProvider,
            coverStore = coverStore,
            localFolders = localSourceFolders,
            metadataCache = localCatalogMetadata,
            registry = localSafRegistry,
            settingsStore = settingsStore,
            bookProvider = pluginBookProvider,
        )
    }
    // Migration writes DB rows and app-private files; per-novel settings live
    // in AppSettings and are flushed through the same SettingsStore.
    val migrationGraph: MigrationGraph by lazy {
        val files = DownloadFileStoreMigrationFiles(downloadFiles)
        val settings = AppSettingsMigrationSettings(
            save = { settingsStore.save(AppSettings.persistedSnapshot()) },
        )
        // One cap for every plugin call the migration flow makes.
        val sourceGate = MigrationSourceGate()
        MigrationGraph(
            coordinator = BookMigrationCoordinator(
                dao = libraryDatabase.libraryDao(),
                repository = libraryRepository,
                files = files,
                settings = settings,
                now = System::currentTimeMillis,
                bookLocks = bookWriteLocks,
            ),
            search = MigrationSearch(finderProvider, sourceGate),
            targets = BookMigrationTargets(pluginBookProvider, libraryRepository, sourceGate),
        )
    }
    // Library update checks only need a chapter count; the shell adapts the
    // book provider so the feature does not import book models.
    val libraryBookRefreshPort: LibraryBookRefreshPort by lazy {
        dev.mirutime.app.shell.BookRefreshPortAdapter(pluginBookProvider)
    }
    private val localFileSystem by lazy { AndroidFileSystem(filesDir) }
    val localBookProvider: LocalBookProvider by lazy {
        LocalBookProvider(libraryRepository, localFileSystem, filesDir)
    }
    // Persisted SAF folders; one store shared by the local catalogue scan and
    // the folder management screen.
    val localSafRegistry: LocalSafRegistry by lazy {
        LocalSafRegistry(this)
    }
    val localSourceFolders: LocalSourceFolders by lazy {
        LocalSourceFolders(this, localSafRegistry)
    }
    // Embedded metadata/covers for unimported catalogue cards. Cache-only:
    // clearing cacheDir just re-reads the SAF documents.
    val localCatalogMetadata: LocalCatalogMetadataCache by lazy {
        LocalCatalogMetadataCache(this, cacheDir)
    }
    // Direct SAF reading: archives/pages live in cacheDir and are evictable;
    // the user's document is never copied into filesDir/local_books.
    val localSafSource: LocalSafBookSource by lazy {
        val resolver = AndroidSafResolver(this)
        LocalSafBookSource(
            resolver = resolver,
            cache = LocalSafCache(File(cacheDir, "local_saf"), resolver::openInput),
            registry = localSafRegistry,
            metadataCache = localCatalogMetadata,
        )
    }
    // SAF import entry point for ImportSheet and the local catalogue.
    val localBookImporter: LocalBookImporter by lazy {
        LocalBookImporter(
            context = this,
            library = libraryRepository,
            fileSystem = localFileSystem,
            baseDir = filesDir,
            coverStore = coverStore,
        )
    }
    val readerGraph: ReaderGraph by lazy {
        ReaderGraph(
            this,
            pluginBookProvider,
            libraryRepository,
            pluginGraph::imageHeadersFor,
            { url, headers ->
                // Manga pages are larger than covers; the grid timeout would
                // cut long strips off on slow CDNs.
                pluginGraph.fetchImage("", url, headers, timeoutMs = MangaPageFetchTimeoutMs).bytes
            },
            pluginGraph.cfGate,
            pluginGraph.plainEngine,
        )
    }
    val downloadEngine: DownloadEngine by lazy {
        DownloadEngine(
            store = RoomDownloadStore(libraryRepository),
            provider = pluginBookProvider,
            files = downloadFiles,
            scheduler = downloadScheduler,
            notifier = object : DownloadNotifier {
                override fun paused(bookId: Long, title: String, progress: Float) {
                    DownloadNotifications.paused(this@MiruApp, bookId, title, progress)
                }

                override fun needsSourceAction(
                    bookId: Long,
                    title: String,
                    progress: Float,
                    sourceUrl: String?,
                ) {
                    DownloadNotifications.needsSourceAction(
                        this@MiruApp,
                        bookId,
                        title,
                        progress,
                        sourceUrl,
                    )
                }

                override fun waitingNetwork(bookId: Long, title: String, progress: Float) {
                    DownloadNotifications.waitingNetwork(this@MiruApp, bookId, title, progress)
                }

                override fun clearStatus(bookId: Long) {
                    DownloadNotifications.clearStatus(this@MiruApp, bookId)
                }
            },
            chapterDelayMs = { AppSettings.downloadDelayMs.toLong() },
            fetchPage = { bookUrl, url ->
                PluginBookIndex.sourceForBook(bookUrl)?.let { sourceId ->
                    pluginGraph.fetchImage(sourceId, url, pluginGraph.imageHeadersFor(url))
                        .takeIf { it.status in 200..299 }
                        ?.bytes
                }
            },
            translationQueue = downloadTranslationQueue,
            scheduleTranslation = { bookId -> translationScheduler.enqueue(bookId) },
            bookLocks = bookWriteLocks,
        )
    }

    /** Schedules the translation queue only when a PENDING intent exists. */
    suspend fun schedulePendingTranslations(bookId: Long) {
        val pending = libraryRepository.chapterTranslationQueueRows(bookId)
            .any { it.status.equals(DownloadTranslationQueue.STATUS_PENDING, ignoreCase = true) }
        if (pending) translationScheduler.enqueue(bookId)
    }
    // Manual backup: consistent DB snapshot + settings + in-library local
    // book files. Restore is a separate lane.
    val readerBackupStore: PrefsReaderBackupStore by lazy {
        PrefsReaderBackupStore(this, readerGraph.configStore)
    }
    val backupService: BackupService by lazy {
        BackupService(
            context = this,
            databasePath = getDatabasePath("mirutime.db"),
            library = libraryRepository,
            readerSnapshot = readerBackupStore::snapshot,
        )
    }
    // Live restore: imports into the open Room database in one transaction.
    // recoverInterruptedRestore only finishes a restore interrupted by process
    // death; a normal restore needs no restart.
    // URL-keyed per-novel settings follow a key -> real URL rewrite.
    private val copyNovelSettingsForRepair: suspend (String, String) -> Unit = { oldUrl, newUrl ->
        AppSettingsMigrationSettings(
            save = { settingsStore.save(AppSettings.persistedSnapshot()) },
        ).copyNovelSettings(oldUrl, newUrl)
    }
    val restoreService: BackupRestoreService by lazy {
        BackupRestoreService(
            context = this,
            databasePath = getDatabasePath("mirutime.db"),
            settingsStore = settingsStore,
            database = libraryDatabase,
            reloadPlugins = { pluginGraph.reload() },
            readerStore = readerBackupStore,
            clearImageCaches = ::clearImageCaches,
            repair = {
                libraryRepository.repairKeyUrls(
                    onBookKeyRewritten = copyNovelSettingsForRepair,
                )
            },
            onBookKeyRewritten = copyNovelSettingsForRepair,
        )
    }
    // Settings -> Data reads the same files the app actually writes. Video
    // downloads are media3's SimpleCache under video_downloads.
    val storageUsageService: StorageUsageService by lazy {
        val db = getDatabasePath("mirutime.db")
        StorageUsageService(
            repository = libraryRepository,
            databaseFiles = listOf(db, File("${db.path}-wal"), File("${db.path}-shm")),
            imageCacheDir = ImageCacheConfig.cacheDir(filesDir),
            clearImageLoader = ::clearImageCaches,
            videoDirs = listOf(File(filesDir, VideoDownloadManager.DIR_NAME)),
            clearVideo = {
                withContext(Dispatchers.Main.immediate) {
                    videoDownloadManager.removeAll()
                }
            },
            purgeOutOfLibraryAction = {
                val result = libraryRepository.purgeOutOfLibrary { files ->
                    localBookProvider.removeFiles(files)
                    files.forEach { downloadFiles.deleteBookFiles(it.bookId) }
                }
                if (result.deleted.isNotEmpty()) {
                    removeVideoDownloads(result.deleted)
                }
                // Media3 keeps its own index; drop rows whose book is gone.
                videoDownloadManager.removeOrphaned(
                    libraryRepository.libraryBookUrls().toSet(),
                )
            },
            filesDir = filesDir,
        )
    }
    // One media3 stack for offline video; the service and every screen share
    // this manager. Created on the main thread (its thread contract).
    val videoDownloadManager: VideoDownloadManager by lazy {
        VideoDownloadManager(this)
    }

    /** Library rows are already committed when this runs; cleanup is best-effort. */
    suspend fun removeVideoDownloads(bookUrls: Set<String>) {
        withContext(Dispatchers.Main.immediate) {
            videoDownloadManager.removeForBooks(bookUrls)
        }
    }
    // EPUB export: provider + local-cover-only + cached translations. Remote
    // artwork goes through the plugin graph (same UA/Referer registry). The
    // translation resolver reads the reader's live Ready state first and then
    // the persisted Room L2 rows with the same request key; it never submits
    // a translation job.
    val bookEpubExporter: BookEpubExporter by lazy {
        BookEpubExporter(
            provider = pluginBookProvider,
            chapterBodyLoader = { bookUrl, chapterIndex ->
                val row = libraryRepository.book(bookUrl)
                val chapter = row?.let {
                    libraryRepository.chapterByIndex(it.bookId, chapterIndex)
                }
                val body = chapter?.let { libraryRepository.chapterBody(it.chapterId) }
                val file = body?.let { stored ->
                    val direct = File(stored.path)
                    if (direct.isAbsolute) direct else File(filesDir, stored.path)
                }
                file?.takeIf { it.isFile }?.readText()
            },
            coverLoader = { bookUrl ->
                libraryRepository.book(bookUrl)?.bookId?.let { coverStore.localCoverBytes(it) }
            },
            fetchImage = { sourceId, url ->
                pluginGraph.fetchImage(sourceId, url, pluginGraph.imageHeadersFor(url))
                    .takeIf { it.status in 200..299 }
                    ?.bytes
            },
            translationsFor = { bookUrl, chapterUrl, html ->
                val mode = translationMode(
                    novel = AppSettings.novelTranslation[bookUrl]
                        ?: AppSettings.novelTranslation[bookKeyUrl(bookUrl)],
                    plugin = AppSettings.pluginTranslation[
                        dev.mirutime.app.features.translation.data.translationSourceId(bookUrl)
                    ],
                    globalEnabled = AppSettings.translationGlobalMode,
                    globalSource = AppSettings.globalSource,
                    globalTarget = AppSettings.globalTarget,
                    globalProvider = AppSettings.provider,
                )
                val live = readerGraph.translations.state.value[chapterUrl]
                    ?.takeIf { it.status == ChapterTranslationStatus.Ready }
                    ?.translations
                if (mode == null) {
                    null
                } else if (!live.isNullOrEmpty()) {
                    live
                } else {
                    val novelPrompt = AppSettings.novelPrompts.firstOrNull { it.bookUrl == bookUrl }
                    val prompt = composePrompt(
                        base = AppSettings.activePrompt,
                        override = novelPrompt?.prompt.orEmpty(),
                        append = novelPrompt?.append == true,
                    )
                    val request = cachedTranslationRequest(bookUrl, chapterUrl, html, mode, prompt)
                    if (request == null) {
                        null
                    } else {
                        try {
                            translationCache.load(request)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            },
            sourceIdFor = { PluginBookIndex.sourceForBook(it).orEmpty() },
        )
    }
    // App-level translator for the in-app browser. The reader keeps its own
    // private graph; this one is built only when a web page is opened.
    val translationGraph: TranslationGraph by lazy {
        TranslationGraph(this, pluginGraph.cfGate, pluginGraph.plainEngine)
    }

    override fun onCreate() {
        super.onCreate()
        // Verbose network/diagnostic lines exist in debug builds only; release
        // keeps warn/error/event. Core/KMP cannot read BuildConfig.
        MiruLog.debugEnabled = BuildConfig.DEBUG
        // Install logging before everything else: a startup failure must still
        // leave a FATAL record the user can export.
        MiruFileLogger.install(this)
        CrashReporter.install()
        MiruLog.i("app", startupMarker())
        // Only crash recovery runs here: live restores are applied immediately
        // from Settings. If a process death interrupted one, finish it before
        // the first provider read.
        val restoredSettings = try {
            runBlocking(Dispatchers.IO) {
                restoreService.recoverInterruptedRestore().settings
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An incompatible/corrupt DB must reach the blocking R1 screen, not
            // kill the process from a background init.
            MiruLog.e("app", "event=restore_recovery_failed", e)
            null
        }
        // Synchronous read: every screen must see saved keys/provider/backup
        // settings on its first composition. Writes stay on appScope (IO).
        AppSettings.applyPersisted(restoredSettings ?: settingsStore.load())
        startSettingsPersistence()
        PluginBookIndex.attach(this)
        appScope.launch {
            // Each step is independent: one recoverable failure must not skip
            // download reconciliation or translation re-arm for the session.
            suspend fun step(event: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MiruLog.e("app", "event=$event", e)
                }
            }
            step("startup_recovery_sweep_failed") { recoverySweeper.sweep() }
            step("startup_book_key_repair_failed") {
                val report = libraryRepository.repairKeyUrls(
                    onBookKeyRewritten = copyNovelSettingsForRepair,
                )
                if (report.changed > 0) libraryRepository.rebuildSearchIndex()
                if (report.changed + report.ambiguous + report.ambiguousSoftRemoved +
                    report.unrepairable + report.failed > 0
                ) {
                    MiruLog.event(
                        "recovery",
                        "book-key-repair",
                        "rewritten" to report.rewrittenInPlace,
                        "deleted" to report.deletedEmpty,
                        "ambiguous" to report.ambiguous,
                        "ambiguousSoft" to report.ambiguousSoftRemoved,
                        "unrepairable" to report.unrepairable,
                        "failed" to report.failed,
                    )
                }
            }
            step("startup_shelves_failed") { libraryRepository.ensureDefaultShelves() }
            step("startup_auto_backup_failed") {
                AutoBackupScheduler.sync(this@MiruApp, settingsStore.load())
            }
            step("startup_library_update_failed") {
                LibraryUpdateScheduler.sync(this@MiruApp, settingsStore.load())
            }
            step("startup_app_update_failed") {
                AppUpdateScheduler.sync(this@MiruApp, settingsStore.load())
            }
            step("startup_cover_backfill_failed") { backfillCovers() }
            step("startup_download_reconcile_failed") { downloadEngine.reconcile() }
            step("startup_translation_rearm_failed") {
                libraryRepository.bookIdsWithPendingTranslations().forEach { bookId ->
                    translationScheduler.enqueue(bookId)
                }
            }
        }
        // Warm the installed-plugin list once; screens read graph.installed.
        pluginGraph.bootstrap()
    }

    /**
     * Opens the lazy library DB once for the R1 probe. Kept here so MainActivity
     * goes through the repository instead of touching the DAO directly.
     */
    suspend fun probeLibraryDatabase() {
        libraryRepository.bookCount()
    }

    // Stable grep marker for support: app version and device in one line.
    private fun startupMarker(): String {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return "STARTUP package=$packageName version=$version " +
            "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}"
    }

    // One snapshotFlow over the persisted fields: settings screens keep
    // writing AppSettings directly, changes are coalesced by debounce and
    // committed on the IO dispatcher instead of per recomposition.
    @OptIn(FlowPreview::class)
    private fun startSettingsPersistence() {
        appScope.launch {
            snapshotFlow { AppSettings.persistedSnapshot() }
                .drop(1)
                .debounce(SETTINGS_DEBOUNCE_MS)
                .collect { settings ->
                    settingsStore.save(settings)
                    AutoBackupScheduler.sync(this@MiruApp, settings)
                    LibraryUpdateScheduler.sync(this@MiruApp, settings)
                    AppUpdateScheduler.sync(this@MiruApp, settings)
                }
        }
    }

    // Sequential, bounded pass over library books with no local cover. The
    // per-book mutex plus the local fast path mean a screen-triggered ensure
    // and this pass cannot double-fetch the same book.
    private suspend fun backfillCovers() {
        val books = runCatching { libraryRepository.libraryBooks() }.getOrNull() ?: return
        val seen = HashSet<String>()
        var attempted = 0
        for (book in books) {
            if (attempted >= MAX_COVER_BACKFILL) break
            val remote = coverStore.remoteCoverUrl(book.bookId)
                ?: book.coverUrl?.takeIf { it.isNotBlank() }
                ?: continue
            if (!seen.add(remote)) continue
            if (coverStore.localCoverUrl(book.bookId) != null) continue
            attempted++
            runCatching {
                // A persisted dead URL means the source moved the cover
                // (Hitomi token rotation): repair instead of retrying it.
                if (coverStore.needsRepair(book.bookId)) {
                    pluginBookProvider.refreshCover(book.url)
                } else {
                    coverStore.ensureCover(book.bookId, book.installId, remote)
                }
            }
        }
    }

    // Trim target for memory-pressure callbacks. Captured here instead of
    // asking SingletonImageLoader: get() would build the loader (disk cache,
    // plugin graph) inside the callback. Null until Coil's first request.
    @Volatile
    private var trimImageLoader: ImageLoader? = null

    // One Coil instance for the whole app, with the cache bounds in one place.
    // Every image goes through the plugin graph (fetchImage), so the WebView
    // cookies and per-host headers apply; the graph is resolved on the first
    // request, never during the first frame.
    override fun newImageLoader(context: Context): ImageLoader =
        buildImageLoader(context) { url, headers -> pluginGraph.fetchImage("", url, headers) }
            .also { trimImageLoader = it }

    // System memory pressure: shrink the image cache, never touch plugin
    // sessions (they own the reader/download pipeline).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val cache = trimImageLoader?.memoryCache ?: return
        val before = cache.size
        when (val action = ImageMemoryTrimPolicy.forLevel(before, level)) {
            MemoryTrim.None -> return
            MemoryTrim.Clear -> cache.clear()
            is MemoryTrim.Trim -> cache.trimToSize(action.bytes)
        }
        MiruLog.event("app", "trim-memory", "level" to level, "before" to before)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        val cache = trimImageLoader?.memoryCache ?: return
        val before = cache.size
        if (before <= 0L) return
        cache.clear()
        MiruLog.event("app", "low-memory", "before" to before)
    }

    // One owner for the shared loader's caches: a live Coil disk cache must be
    // cleared through its own API, never by deleting files under it.
    private fun clearImageCaches() {
        val loader = SingletonImageLoader.get(this)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}

// Covers per launch: enough to fill a normal first screen without turning a
// cold start into a mass download (a 50-cover pass on a slow CDN read as a
// request storm on device; the rest is repaired/backfilled later).
private const val MAX_COVER_BACKFILL = 30

// Short enough that a user cannot normally kill the process before the write;
// long enough to collapse a slider drag or a multi-field pair edit into one
// SharedPreferences commit.
private const val SETTINGS_DEBOUNCE_MS = 400L

private const val MangaPageFetchTimeoutMs = 30_000L
