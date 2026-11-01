package dev.mirutime.app.di

import android.content.Context
import android.os.Build
import dev.mirutime.app.di.host.AndroidSystemInfo
import dev.mirutime.app.di.host.AppCookieStore
import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.translation.data.LocalNetworkAccess
import dev.mirutime.app.features.translation.data.TranslationSettingsSnapshot
import dev.mirutime.app.features.translation.data.resolveProviderConfigs
import dev.mirutime.app.features.translation.data.translationUrlError
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.SUPPORTED_PLUGIN_API
import dev.mirutime.core.network.PluginHttpClient
import dev.mirutime.core.network.cf.CfGate
import dev.mirutime.translator.CoreTranslationHttpClient
import dev.mirutime.translator.TranslationManager
import dev.mirutime.translator.TranslationManagerImpl
import io.ktor.client.engine.HttpClientEngine

private const val SOURCE_ID = "translation"

// `gate` is the process-wide CfGate owned by PluginGraph: translation only
// reads cooldown state, it never solves (no solver is wired below). The cookie
// store is the same process-wide WebView-backed one the plugin client uses, so
// a provider host only ever sees cookies scoped to its own domain.
class TranslationGraph(context: Context, gate: CfGate, engine: HttpClientEngine) {
    private val appContext = context.applicationContext
    private val cookieStore = AppCookieStore.get()

    // The HTTP client is independent of provider settings; only the manager's
    // provider configs have to be rebuilt.
    // `engine` is PluginGraph.plainEngine (system DNS): the OpenAI-compatible
    // provider may point at a user-run local server (Ollama/LM Studio on
    // 10.0.2.2 or 192.168.x), which SafeDns would reject. The plugin engine
    // keeps the address gate.
    private val http: CoreTranslationHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoreTranslationHttpClient(
            PluginHttpClient(
                metadata = TRANSLATION_METADATA,
                systemInfo = AndroidSystemInfo,
                cookieStore = cookieStore,
                logger = MiruCoreLogger,
                engine = engine,
                gate = gate,
            ),
            sourceId = SOURCE_ID,
            // One transport guard covers batch, metadata, title and detection:
            // a LAN endpoint without the grant or a public http base URL fails
            // before Ktor's retries/timeouts instead of surfacing a timeout.
            preflight = ::endpointError,
        )
    }

    private fun endpointError(url: String) = translationUrlError(
        url = url,
        openAiBaseUrl = AppSettings.openAiBaseUrl,
        compatibleBaseUrl = AppSettings.openAiCompatibleBaseUrl,
        sdkInt = Build.VERSION.SDK_INT,
        granted = LocalNetworkAccess.isGranted(appContext),
    )

    private data class Cached(
        val snapshot: TranslationSettingsSnapshot,
        val manager: TranslationManager,
    )

    @Volatile
    private var cached: Cached? = null

    /**
     * Current manager. Rebuilt only when the settings snapshot changes (key,
     * model, budget, concurrency), never per request: callers take it at the
     * start of a browser/reader session and keep it for that session.
     */
    val manager: TranslationManager
        get() {
            cached?.let { if (it.snapshot == settingsSnapshot()) return it.manager }
            return synchronized(this) {
                // Re-read inside the lock: another thread may have published a
                // newer snapshot while this one was waiting, and publishing a
                // manager paired with the older one would silently regress it.
                val snapshot = settingsSnapshot()
                val current = cached
                if (current != null && current.snapshot == snapshot) {
                    current.manager
                } else {
                    TranslationManagerImpl(
                        http = http,
                        configs = resolveProviderConfigs(snapshot),
                    ).also { cached = Cached(snapshot, it) }
                }
            }
        }
}

private fun settingsSnapshot() = TranslationSettingsSnapshot(
    googlePaKeys = AppSettings.googlePaKeys,
    geminiKeys = AppSettings.geminiKeys,
    geminiModel = AppSettings.geminiModel,
    openAiKeys = AppSettings.openAiKeys,
    openAiModel = AppSettings.openAiModel,
    openAiBaseUrl = AppSettings.openAiBaseUrl,
    openAiCompatibleKeys = AppSettings.openAiCompatibleKeys,
    openAiCompatibleModel = AppSettings.openAiCompatibleModel,
    openAiCompatibleBaseUrl = AppSettings.openAiCompatibleBaseUrl,
    anthropicKeys = AppSettings.anthropicKeys,
    anthropicModel = AppSettings.anthropicModel,
    deeplKeys = AppSettings.deeplKeys,
    yandexCloudKeys = AppSettings.yandexCloudKeys,
    yandexCloudFolderId = AppSettings.yandexCloudFolderId,
    maxOutputTokens = AppSettings.maxOutputTokens,
    timeoutSeconds = AppSettings.timeoutSeconds,
    maxAttempts = AppSettings.maxAttempts,
    maxCharsPerRequest = AppSettings.maxCharsPerRequest,
    chunkConcurrency = AppSettings.chunkConcurrency,
)

private val TRANSLATION_METADATA = PluginMetadata(
    id = SOURCE_ID,
    name = "Translation",
    baseUrl = "https://translate.googleapis.com",
    contentType = ContentType.NOVEL,
    language = "en",
    api = SUPPORTED_PLUGIN_API,
    version = "1.0.0",
    sha256 = "",
)
