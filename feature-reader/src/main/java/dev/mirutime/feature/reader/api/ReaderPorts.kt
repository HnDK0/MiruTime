package dev.mirutime.feature.reader.api

import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.feature.reader.SavedReading
import kotlinx.coroutines.flow.StateFlow

/** Everything the reader session needs from book storage. */
interface ReaderContentSource {
    suspend fun book(bookUrl: String): ReaderBook?
    suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument?

    /**
     * Parses a chapter without touching reading history. Bulk translation uses
     * this so selecting ten chapters does not move "last read" ten times.
     */
    suspend fun documentForTranslation(bookUrl: String, chapterIndex: Int): ReaderDocument? =
        document(bookUrl, chapterIndex)

    /**
     * Parses a chapter behind the visible one without touching reading history.
     * Prefetch and previous-chapter loads use this so only the chapter the user
     * is actually reading moves "last read".
     */
    suspend fun documentForPrefetch(bookUrl: String, chapterIndex: Int): ReaderDocument? =
        document(bookUrl, chapterIndex)

    /**
     * Parses a chapter without touching reading history. Used when cleanup rules
     * change and the open chapter has to be re-read.
     */
    suspend fun documentForRulesReload(bookUrl: String, chapterIndex: Int): ReaderDocument? =
        document(bookUrl, chapterIndex)

    suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean)
}

/** Persistence for the last reading position. */
interface ReaderPositionStore {
    suspend fun load(bookUrl: String): SavedReading?
    suspend fun save(bookUrl: String, saved: SavedReading)
    suspend fun clear(bookUrl: String)
}

/** Records active reading intervals. */
interface ReaderSessionTracker {
    suspend fun start(bookUrl: String, chapterIndex: Int)
    suspend fun switch(bookUrl: String, chapterIndex: Int)
    suspend fun stop()
    suspend fun resume()
}

/** Effective text cleanup rules (global + per-book), owned by app settings. */
interface ReaderTextRules {
    fun effectiveRules(bookUrl: String?): List<dev.mirutime.core.text.RegexRule>

    /** Bumped on every rule edit; the translation port recomputes from it. */
    val revision: StateFlow<Long>

    /** Bumped only when original-applicable rules change; the reader re-parses. */
    val originalRevision: StateFlow<Long>
}

/** Reader-facing settings that are not part of ReaderStyle. */
interface ReaderSettingsPort {
    val parallelEnabled: Boolean
    val parallelOrder: String
}

enum class ReaderTranslationStatus { Loading, Ready, Failed }

/** Actionable failure reasons; null keeps the generic failed label. */
enum class ReaderTranslationFailure { LocalNetworkRequired, CleartextNotAllowed }

/** One chapter's translation state, mirrored from the app translation cache. */
data class ReaderChapterTranslation(
    val status: ReaderTranslationStatus,
    val translations: Map<Int, String>,
    val configKey: String,
    // Owning book: the reader must never show another book's translation.
    val bookUrl: String,
    val failure: ReaderTranslationFailure? = null,
)

/** The resolved translation pair of a book; null when translation is off. */
data class ReaderTranslationMode(
    val source: String,
    val target: String,
    val provider: String,
    val full: Boolean = false,
) {
    val hasPair: Boolean get() = source.isNotBlank() && target.isNotBlank() && source != target
}

/** Metadata translation entry already keyed by its active config. */
data class ReaderBookMetadata(
    val configKey: String,
    val title: String?,
    val chapterTitles: Map<Int, String>,
)

/** Translation state, metadata and actions the reader UI needs. */
interface ReaderTranslationPort {
    val state: StateFlow<Map<String, ReaderChapterTranslation>>
    val metadata: StateFlow<Map<String, ReaderBookMetadata>>

    fun activeMode(bookUrl: String): ReaderTranslationMode?
    fun providerLabel(provider: String): String

    /** Ensures the chapter is translated when an active config exists. */
    fun ensureChapter(bookUrl: String, chapterIndex: Int, document: ReaderDocument)

    /**
     * Publishes a cached translation for [document] before it renders. Never
     * starts the engine; a miss leaves [ensureChapter] as the fallback.
     */
    suspend fun preloadChapter(bookUrl: String, document: ReaderDocument) {}
    fun forget(chapterUrls: Collection<String>)

    /** Active FULL-mode metadata config token, or null when metadata is off. */
    fun metadataConfigKey(bookUrl: String): String?
    suspend fun hydrateMetadata(bookUrl: String)
    fun submitChapterTitles(bookUrl: String, title: String, chapters: List<Pair<Int, String>>)
}

/** Imports/removes reader background and font assets. */
interface ReaderAssetPort {
    data class StoredAsset(val path: String, val displayName: String)

    suspend fun importBackground(uri: android.net.Uri, previousPath: String? = null): StoredAsset?
    suspend fun importFont(uri: android.net.Uri, previousPath: String? = null): StoredAsset?
    fun deleteBackground(path: String?)
    fun deleteFont(path: String?)
}

/**
 * Everything the reader entry needs. The app composes this from its graphs and
 * adapters; the feature never imports app internals.
 */
interface ReaderUiGraph {
    val session: dev.mirutime.feature.reader.session.ReaderSessionStore
    val config: dev.mirutime.feature.reader.ReaderConfigStore
    val positions: ReaderPositionStore
    val translation: ReaderTranslationPort
    val settings: ReaderSettingsPort
    val assets: ReaderAssetPort
    val rules: ReaderTextRules
    val tts: dev.mirutime.feature.tts.TtsController
    val ttsCoordinator: dev.mirutime.feature.reader.session.TtsChapterCoordinator
    val ttsSessions: dev.mirutime.feature.tts.TtsSessionStore
    val ttsSessionTracker: ReaderSessionTracker?

    /**
     * Per-URL image request headers (Referer/User-Agent) for remote manga
     * pages. Empty means no headers; local files never call it.
     */
    fun imageHeadersFor(url: String): Map<String, String> = emptyMap()

    /**
     * A locally cached file for one manga page. The tiled viewer decodes from
     * a file (region decoding is not possible from a network stream), so the
     * app fetches bytes once and returns a stable path. Null means the page is
     * unavailable; the viewer shows its normal retry UI.
     */
    suspend fun mangaPageFile(url: String): java.io.File? = null

    fun startTtsPlayback(
        bookTitle: String,
        chapterTitle: String,
        coverUrl: String? = null,
        chapterNumber: Int = 0,
        chapterCount: Int = 0,
    )
    fun stopTtsPlayback()
    fun stopTtsSession()
}
