package dev.mirutime.app.features.reader.data

import android.content.Context
import android.net.Uri
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.ui.TranslationProviderOption
import dev.mirutime.app.features.translation.data.ChapterTranslationPlanner
import dev.mirutime.app.features.translation.data.MetadataTranslationController
import dev.mirutime.app.features.translation.data.ReaderChaptersJob
import dev.mirutime.app.features.translation.data.TranslationController
import dev.mirutime.app.features.translation.data.activeTranslationMode
import dev.mirutime.app.features.translation.data.cacheKey
import dev.mirutime.app.features.translation.data.metadataConfigFor
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleTarget
import dev.mirutime.core.text.applyRulesFor
import dev.mirutime.feature.reader.api.ReaderAssetPort
import dev.mirutime.feature.reader.api.ReaderBookMetadata
import dev.mirutime.feature.reader.api.ReaderChapterTranslation
import dev.mirutime.feature.reader.api.ReaderSettingsPort
import dev.mirutime.feature.reader.api.ReaderTextRules
import dev.mirutime.feature.reader.api.ReaderTranslationFailure
import dev.mirutime.feature.reader.api.ReaderTranslationMode
import dev.mirutime.feature.reader.api.ReaderTranslationStatus
import dev.mirutime.feature.reader.api.ReaderTranslationPort
import dev.mirutime.app.features.reader.document.ReaderDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App settings are the single source of effective reader text rules. */
class AppReaderTextRules : ReaderTextRules {
    override fun effectiveRules(bookUrl: String?): List<RegexRule> =
        AppSettings.effectiveRules(bookUrl)

    override val revision: StateFlow<Long> = AppSettings.regexRulesRevision
    override val originalRevision: StateFlow<Long> = AppSettings.regexRulesOriginalRevision
}

/** Parallel display settings, read straight from the Compose snapshot state. */
class AppReaderSettingsPort : ReaderSettingsPort {
    override val parallelEnabled: Boolean get() = AppSettings.parallelEnabled
    override val parallelOrder: String get() = AppSettings.parallelOrder
}

/** Adapter over the app-owned asset store. */
class AppReaderAssetPort(context: Context) : ReaderAssetPort {
    private val store = ReaderAssetStore(context)

    override suspend fun importBackground(uri: Uri, previousPath: String?): ReaderAssetPort.StoredAsset? =
        store.importBackground(uri, previousPath)?.let {
            ReaderAssetPort.StoredAsset(it.path, it.displayName)
        }

    override suspend fun importFont(uri: Uri, previousPath: String?): ReaderAssetPort.StoredAsset? =
        store.importFont(uri, previousPath)?.let {
            ReaderAssetPort.StoredAsset(it.path, it.displayName)
        }

    override fun deleteBackground(path: String?) = store.deleteBackground(path)

    override fun deleteFont(path: String?) = store.deleteFont(path)
}

/**
 * Bridges the app translation controllers into the reader port. Config
 * resolution stays in app: the reader only asks for an active mode or ensures
 * a chapter, and never sees AppSettings or TranslationRequest.
 */
class AppReaderTranslationPort(
    private val controller: TranslationController,
    private val metadataController: MetadataTranslationController,
    private val scope: CoroutineScope,
    private val context: Context,
) : ReaderTranslationPort {
    override val state: StateFlow<Map<String, ReaderChapterTranslation>> =
        // Rule edits must reach the translated text immediately, not on the
        // next controller emission: the revision is a second input.
        combine(controller.state, AppSettings.regexRulesRevision) { entries, _ ->
            entries.mapValues { (_, value) ->
                ReaderChapterTranslation(
                    status = when (value.status) {
                        dev.mirutime.app.features.translation.data.ChapterTranslationStatus.Loading -> ReaderTranslationStatus.Loading
                        dev.mirutime.app.features.translation.data.ChapterTranslationStatus.Ready -> ReaderTranslationStatus.Ready
                        dev.mirutime.app.features.translation.data.ChapterTranslationStatus.Failed -> ReaderTranslationStatus.Failed
                    },
                    translations = cleanTranslatedBlocks(value.bookUrl, value.translations),
                    configKey = value.configKey,
                    bookUrl = value.bookUrl,
                    failure = when (value.failure) {
                        dev.mirutime.app.features.translation.data.ChapterTranslationFailure.LocalNetworkRequired ->
                            ReaderTranslationFailure.LocalNetworkRequired
                        dev.mirutime.app.features.translation.data.ChapterTranslationFailure.CleartextNotAllowed ->
                            ReaderTranslationFailure.CleartextNotAllowed
                        null -> null
                    },
                )
            }
        }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val metadata: StateFlow<Map<String, ReaderBookMetadata>> =
        metadataController.state
            .map { entries ->
                entries.mapValues { (_, value) ->
                    ReaderBookMetadata(
                        configKey = value.config.cacheKey(),
                        title = value.title,
                        chapterTitles = value.chapterTitles,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun activeMode(bookUrl: String): ReaderTranslationMode? =
        activeTranslationMode(bookUrl)?.let {
            ReaderTranslationMode(
                source = it.source,
                target = it.target,
                provider = it.provider,
                full = it.full,
            )
        }

    override fun providerLabel(provider: String): String =
        context.getString(TranslationProviderOption.from(provider).label)

    override fun ensureChapter(
        bookUrl: String,
        chapterIndex: Int,
        document: ReaderDocument,
    ) {
        val mode = activeTranslationMode(bookUrl) ?: return
        val config = ChapterTranslationPlanner.config(bookUrl, mode) ?: return
        ChapterTranslationPlanner.request(document, config)?.let(controller::submit)
    }

    override suspend fun preloadChapter(bookUrl: String, document: ReaderDocument) {
        val mode = activeTranslationMode(bookUrl) ?: return
        val config = ChapterTranslationPlanner.config(bookUrl, mode) ?: return
        ChapterTranslationPlanner.request(document, config)?.let { controller.preload(it) }
    }

    override fun forget(chapterUrls: Collection<String>) = controller.forget(chapterUrls)

    override fun metadataConfigKey(bookUrl: String): String? =
        metadataConfigFor(bookUrl)?.cacheKey()

    override suspend fun hydrateMetadata(bookUrl: String) {
        val config = metadataConfigFor(bookUrl) ?: return
        metadataController.hydrate(bookUrl, config)
    }

    override fun submitChapterTitles(
        bookUrl: String,
        title: String,
        chapters: List<Pair<Int, String>>,
    ) {
        val config = metadataConfigFor(bookUrl) ?: return
        metadataController.submitChapters(
            ReaderChaptersJob(
                bookUrl = bookUrl,
                title = title,
                chapters = chapters,
                config = config,
            ),
        )
    }
}

/**
 * Translation-stage cleanup: only rules that target translations (or both).
 * An unknown book falls back to the global rules only.
 */
internal fun cleanTranslatedBlocks(
    bookUrl: String,
    translations: Map<Int, String>,
): Map<Int, String> {
    if (translations.isEmpty()) return translations
    val rules = AppSettings.effectiveRules(bookUrl.takeIf { it.isNotBlank() })
    if (rules.isEmpty()) return translations
    return translations.mapValues { (_, text) ->
        applyRulesFor(rules, text, RegexRuleTarget.Translation)
    }
}
