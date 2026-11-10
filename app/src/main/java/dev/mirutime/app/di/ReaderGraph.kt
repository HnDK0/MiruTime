package dev.mirutime.app.di

import android.content.Context
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.reader.data.AppReaderAssetPort
import dev.mirutime.app.features.reader.data.AppReaderSettingsPort
import dev.mirutime.app.features.reader.data.AppReaderTranslationPort
import dev.mirutime.app.features.reader.data.ChapterRetranslator
import dev.mirutime.app.features.reader.data.DefaultReaderRepository
import dev.mirutime.app.features.reader.data.MangaPageFileCache
import dev.mirutime.app.features.reader.data.PrefsReaderConfigStore
import dev.mirutime.app.features.reader.data.PrefsReadingPositionRepository
import dev.mirutime.app.features.reader.data.ReadingPositionRepository
import dev.mirutime.app.features.reader.data.RoomReadingPositionRepository
import dev.mirutime.app.features.reader.data.ReaderRepository
import dev.mirutime.app.features.reader.data.TranslationOnlyDocuments
import dev.mirutime.app.features.reader.domain.ReadingSessionTracker
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.api.ReaderAssetPort
import dev.mirutime.app.features.reader.data.AppReaderTextRules
import dev.mirutime.feature.reader.api.ReaderSettingsPort
import dev.mirutime.feature.reader.api.ReaderTextRules
import dev.mirutime.feature.reader.api.ReaderTranslationPort
import dev.mirutime.feature.reader.api.ReaderUiGraph
import dev.mirutime.feature.reader.session.ReadTracker
import dev.mirutime.feature.reader.ChapterLoadStatus
import dev.mirutime.feature.reader.session.ReaderSessionStore
import dev.mirutime.feature.reader.session.TtsChapterCoordinator
import dev.mirutime.feature.reader.ui.ReaderFontCatalog
import dev.mirutime.app.features.translation.data.ChapterTranslationPlanner
import dev.mirutime.app.features.translation.data.MetadataTranslationController
import dev.mirutime.app.features.translation.data.activeTranslationMode
import dev.mirutime.app.features.translation.data.RoomMetadataTranslationStore
import dev.mirutime.app.features.translation.data.RoomTranslationCache
import dev.mirutime.app.features.translation.data.TranslationController
import dev.mirutime.app.features.translation.domain.ManagerTranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.feature.tts.AndroidTtsEngine
import dev.mirutime.feature.tts.AndroidTtsVoicePreviewer
import dev.mirutime.feature.tts.PrefsTtsSessionStore
import dev.mirutime.feature.tts.PrefsTtsSettingsStore
import dev.mirutime.feature.tts.TtsController
import dev.mirutime.feature.tts.TtsSessionStore
import dev.mirutime.feature.tts.service.TtsPlaybackService
import dev.mirutime.feature.tts.service.TtsServiceHost
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// The caller passes the real plugin-backed provider so the reader serves real
// chapter text; tests pass a fake explicitly.
class ReaderGraph(
    context: Context,
    bookProvider: BookDataProvider,
    private val libraryRepository: LibraryRepository? = null,
    private val imageHeaders: (String) -> Map<String, String> = { emptyMap() },
    // Page bytes for the tiled manga viewer (the app owns the network client).
    private val fetchImageBytes: suspend (String, Map<String, String>) -> ByteArray? =
        { _, _ -> null },
    // Process-wide CF gate shared with the host fetcher and plugin clients,
    // so a cooldown entered from the reader blocks a second WebView there too.
    cfGate: CfGate,
    // Process-wide system-DNS engine: translation may target a LAN provider.
    plainEngine: HttpClientEngine,
) : ReaderUiGraph {
    private val appContext = context.applicationContext
    // Data work (parsing, disk, translation) and UI/TTS work have different
    // thread contracts: keep them in separate scopes.
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val translationGraph = TranslationGraph(appContext, cfGate, plainEngine)

    init {
        // Warm the catalog early; a stale sys: key is rejected once it loads.
        dataScope.launch { ReaderFontCatalog.load() }
    }

    private val translationOnlyDocuments = libraryRepository?.let(::TranslationOnlyDocuments)
    val repository: ReaderRepository = DefaultReaderRepository(
        provider = bookProvider,
        library = libraryRepository,
        translationOnlyDocument = { bookUrl, chapterIndex ->
            val mode = activeTranslationMode(bookUrl)?.takeIf { it.hasPair }
            val fallback = translationOnlyDocuments
            if (mode != null && fallback != null) {
                fallback.load(bookUrl, chapterIndex, mode.source, mode.target, mode.provider)
            } else {
                null
            }
        },
    )
    override val positions: ReadingPositionRepository = libraryRepository?.let(::RoomReadingPositionRepository)
        ?: PrefsReadingPositionRepository(context.applicationContext)
    val configStore = PrefsReaderConfigStore(context.applicationContext)
    override val config: ReaderConfigStore = configStore
    private fun newSessionTracker(kind: String): ReadingSessionTracker? =
        libraryRepository?.let { repo ->
            ReadingSessionTracker(
                resolveIds = { bookUrl, chapterIndex ->
                    repo.book(bookUrl)?.let { book ->
                        val chapter = repo.chapterByIndex(book.bookId, chapterIndex)
                        book.bookId to chapter?.chapterId
                    }
                },
                saveSession = { repo.saveSession(it) },
                kind = kind,
            )
        }

    val translationEngine: TranslationEngine = ManagerTranslationEngine { translationGraph.manager }
    private val translationCache = libraryRepository?.let(::RoomTranslationCache)
    val translations = TranslationController(translationEngine, dataScope, translationCache)
    val metadataTranslations = MetadataTranslationController(
        engine = translationEngine,
        scope = dataScope,
        store = libraryRepository?.let(::RoomMetadataTranslationStore),
    )
    override val translation: ReaderTranslationPort = AppReaderTranslationPort(
        controller = translations,
        metadataController = metadataTranslations,
        retranslator = ChapterRetranslator(
            currentDocument = { _, index -> session.state.value.documents[index] },
            config = { bookUrl ->
                activeTranslationMode(bookUrl)?.let { mode ->
                    ChapterTranslationPlanner.config(bookUrl, mode)
                }
            },
            submit = { request -> translations.submitForced(request) },
        ),
        scope = dataScope,
        context = appContext,
    )
    val sessionTracker = newSessionTracker(ReadingSessionTracker.KIND_READING)
    override val ttsSessionTracker = newSessionTracker(ReadingSessionTracker.KIND_TTS)
    override val session = ReaderSessionStore(
        content = repository,
        positions = positions,
        config = config,
        readTracker = ReadTracker(repository),
        scope = dataScope,
        sessionTracker = sessionTracker,
        preloadActiveChapter = { _, document ->
            translation.preloadChapter(document.bookUrl, document)
        },
    )
    override val settings: ReaderSettingsPort = AppReaderSettingsPort()
    override val rules: ReaderTextRules = AppReaderTextRules()
    override val assets: ReaderAssetPort = AppReaderAssetPort(appContext)
    override val ttsSessions: TtsSessionStore = PrefsTtsSessionStore(appContext)
    private val ttsSettings = PrefsTtsSettingsStore(appContext)
    override val tts = TtsController(
        engine = AndroidTtsEngine(appContext),
        scope = mainScope,
        sessions = ttsSessions,
        settingsStore = ttsSettings,
        voicePreviewer = AndroidTtsVoicePreviewer(appContext),
    )
    override val ttsCoordinator = TtsChapterCoordinator(
        tts = tts,
        session = session,
        translation = translation,
        settings = settings,
        scope = mainScope,
        startPlayback = { bookTitle, chapterTitle, coverUrl, chapterIndex, chapterCount ->
            startTtsPlayback(
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
                coverUrl = coverUrl,
                chapterNumber = chapterIndex,
                chapterCount = chapterCount,
            )
        },
        stopPlayback = { stopTtsPlayback() },
    ).also { it.start() }

    override fun imageHeadersFor(url: String): Map<String, String> = imageHeaders(url)

    private val mangaPageFiles = MangaPageFileCache(
        directory = java.io.File(appContext.cacheDir, "manga_pages"),
        fetchRemote = fetchImageBytes,
        headersFor = imageHeaders,
        readLocalBytes = { url ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openInputStream(android.net.Uri.parse(url))
                        ?.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MaxLocalPageBytes) return@use null
                                out.write(buffer, 0, read)
                            }
                            out.toByteArray()
                        }
                }.getOrNull()
            }
        },
        // Local pages are files in the app's own storage: the importers write
        // them under local_books / downloads/manga, the direct SAF reader
        // under cacheDir/local_saf. They are served from disk, not copied.
        localRoots = listOf(
            java.io.File(appContext.filesDir, LocalBooksDir),
            java.io.File(appContext.filesDir, DownloadMangaDir),
            java.io.File(appContext.cacheDir, SafCacheDir),
        ),
    )

    override suspend fun mangaPageFile(url: String): java.io.File? = mangaPageFiles.file(url)

    /** Keeps TTS alive in the background; idempotent while already running. */
    override fun startTtsPlayback(
        bookTitle: String,
        chapterTitle: String,
        coverUrl: String?,
        chapterNumber: Int,
        chapterCount: Int,
    ) {
        // The service resolves the same app-scoped controller from the host;
        // after process death the host stays empty and the service stops.
        TtsServiceHost.attach(tts)
        TtsPlaybackService.start(
            appContext,
            bookTitle,
            chapterTitle,
            coverUrl,
            chapterNumber,
            chapterCount,
        )
    }

    override fun stopTtsPlayback() {
        TtsPlaybackService.stop(appContext)
        TtsServiceHost.detach(tts)
    }

    override fun stopTtsSession() {
        mainScope.launch { ttsSessionTracker?.stop() }
    }
}

private const val MaxLocalPageBytes = 64L * 1024 * 1024

// Roots of the app's own page storage: LocalBookImporter writes local_books,
// LocalDownloadFileStore writes downloads/manga, LocalSafCache writes
// cacheDir/local_saf.
private const val LocalBooksDir = "local_books"
private const val DownloadMangaDir = "downloads/manga"
private const val SafCacheDir = "local_saf"
