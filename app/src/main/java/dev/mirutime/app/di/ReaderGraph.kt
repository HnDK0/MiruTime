package dev.mirutime.app.di

import android.content.Context
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.reader.data.AppReaderAssetPort
import dev.mirutime.app.features.reader.data.AppReaderSettingsPort
import dev.mirutime.app.features.reader.data.AppReaderTranslationPort
import dev.mirutime.app.features.reader.data.DefaultReaderRepository
import dev.mirutime.app.features.reader.data.PrefsReaderConfigStore
import dev.mirutime.app.features.reader.data.PrefsReadingPositionRepository
import dev.mirutime.app.features.reader.data.ReadingPositionRepository
import dev.mirutime.app.features.reader.data.RoomReadingPositionRepository
import dev.mirutime.app.features.reader.data.ReaderRepository
import dev.mirutime.app.features.reader.domain.ReadingSessionTracker
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.api.ReaderAssetPort
import dev.mirutime.feature.reader.api.ReaderSettingsPort
import dev.mirutime.feature.reader.api.ReaderTranslationPort
import dev.mirutime.feature.reader.api.ReaderUiGraph
import dev.mirutime.feature.reader.session.ReadTracker
import dev.mirutime.feature.reader.session.ReaderSessionStore
import dev.mirutime.app.features.translation.data.MetadataTranslationController
import dev.mirutime.app.features.translation.data.RoomMetadataTranslationStore
import dev.mirutime.app.features.translation.data.RoomTranslationCache
import dev.mirutime.app.features.translation.data.TranslationController
import dev.mirutime.app.features.translation.domain.ManagerTranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.feature.tts.AndroidTtsEngine
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
    // Process-wide CF gate shared with the host fetcher and plugin clients,
    // so a cooldown entered from the reader blocks a second WebView there too.
    cfGate: CfGate,
    // Process-wide system-DNS engine: translation may target a LAN provider.
    plainEngine: HttpClientEngine,
) : ReaderUiGraph {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val translationGraph = TranslationGraph(appContext, cfGate, plainEngine)

    val repository: ReaderRepository = DefaultReaderRepository(bookProvider, libraryRepository)
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
    val translations = TranslationController(translationEngine, scope, translationCache)
    val metadataTranslations = MetadataTranslationController(
        engine = translationEngine,
        scope = scope,
        store = libraryRepository?.let(::RoomMetadataTranslationStore),
    )
    override val translation: ReaderTranslationPort = AppReaderTranslationPort(
        controller = translations,
        metadataController = metadataTranslations,
        scope = scope,
        context = appContext,
    )
    val sessionTracker = newSessionTracker(ReadingSessionTracker.KIND_READING)
    override val ttsSessionTracker = newSessionTracker(ReadingSessionTracker.KIND_TTS)
    override val session = ReaderSessionStore(
        content = repository,
        positions = positions,
        config = config,
        readTracker = ReadTracker(repository),
        scope = scope,
        sessionTracker = sessionTracker,
        preloadActiveChapter = { _, document ->
            translation.preloadChapter(document.bookUrl, document)
        },
    )
    override val settings: ReaderSettingsPort = AppReaderSettingsPort()
    override val assets: ReaderAssetPort = AppReaderAssetPort(appContext)
    override val ttsSessions: TtsSessionStore = PrefsTtsSessionStore(appContext)
    private val ttsSettings = PrefsTtsSettingsStore(appContext)
    override val tts = TtsController(AndroidTtsEngine(appContext), scope, ttsSessions, ttsSettings)

    override fun imageHeadersFor(url: String): Map<String, String> = imageHeaders(url)

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
        scope.launch { ttsSessionTracker?.stop() }
    }
}
