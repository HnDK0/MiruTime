package dev.mirutime.app.features.settings.data

import android.content.SharedPreferences
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

// Values that must survive process death. Compose-only state (selection
// dialogs, scroll positions, ...) deliberately stays out of this class.
// Keys are the real persisted preference keys.
val defaultPromptPresets: List<Pair<String, String>> = listOf(
    "Literary" to "Translate {source_language} web novel text into {target_language}. Keep the author's voice, names and honorifics.",
    "Literal" to "Translate from {source_language} to {target_language} as literally as possible. Do not add or remove information.",
)

// The built-in local sources are pinned on a fresh install.
// Only a missing pref falls back here; an explicit empty list stays empty.
val defaultPinnedSources: List<String> = listOf(LocalSources.Books, LocalSources.Manga)

// ReaderStyle is intentionally absent: PrefsReaderConfigStore owns it in its
// own preferences file. The download manager's fake work list and the
// selection/dialog/draft state of settings screens are also not settings.
@Serializable
data class PersistedSettings(
    val appLanguage: String = "system",
    val downloadKeepMangaCache: Boolean = true,
    val downloadPagesAfterView: Boolean = false,
    val videoVariantByBook: Map<String, String> = emptyMap(),
    val videoAutoNext: Boolean = true,
    val videoSeekStepMs: Int = 10_000,
    val videoControllerAutoHideMs: Int = 3_500,
    val videoGestureDragThreshold: Float = 0.06f,
    val videoGestureSwappedSides: Boolean = false,
    val videoGestureDoubleTap: Boolean = true,
    val videoGestureVerticalSwipe: Boolean = true,
    val videoGestureLongPressSpeed: Boolean = true,
    val gridColumns: Int = 3,
    val listLayout: String = "grid",
    val cardStyle: CardStyle = CardStyle.Mat,
    val sourceStrip: String = "below",
    val textScale: Int = 100,
    val uiScale: Int = 100,
    val newestFirst: Boolean = true,
    val pinnedSources: List<String> = defaultPinnedSources,
    val filterPresets: Map<String, List<FilterPreset>> = emptyMap(),
    val searchHistory: Map<String, List<String>> = emptyMap(),
    val appUpdateCheck: Boolean = true,
    val libraryAutoUpdate: Boolean = true,
    val libraryLastRunAt: Long = 0L,
    val libraryIntervalHours: Int = 24,
    val updateWifiOnly: Boolean = false,
    val updateWhileCharging: Boolean = true,
    val translationGlobalMode: Boolean = false,
    val globalSource: String = "en",
    val globalTarget: String = "",
    val provider: String = "GOOGLE_PA",
    val googlePaKeys: String = "",
    val geminiKeys: String = "",
    val geminiModel: String = "gemini-2.5-flash-lite",
    val openAiKeys: String = "",
    val openAiModel: String = "gpt-4o-mini",
    val openAiBaseUrl: String = "https://api.openai.com",
    val openAiCompatibleKeys: String = "",
    val openAiCompatibleModel: String = "",
    val openAiCompatibleBaseUrl: String = "",
    val anthropicKeys: String = "",
    val anthropicModel: String = "claude-sonnet-5-5",
    val deeplKeys: String = "",
    val yandexCloudKeys: String = "",
    val yandexCloudFolderId: String = "",
    val activePrompt: String = "",
    val promptPresets: List<Pair<String, String>> = defaultPromptPresets,
    val promptEnglishLocale: Boolean = true,
    val maxOutputTokens: Int = 0,
    val timeoutSeconds: Int = 180,
    val maxAttempts: Int = 6,
    val maxCharsPerRequest: Int = 0,
    val chunkConcurrency: Int = 8,
    val useJsonSchema: Boolean = false,
    val parallelEnabled: Boolean = false,
    val parallelOrder: String = "TRANSLATION_FIRST",
    val novelPrompts: List<NovelPrompt> = emptyList(),
    val pluginTranslation: Map<String, PluginTranslation> = emptyMap(),
    val novelTranslation: Map<String, NovelTranslation> = emptyMap(),
    val translationFavorites: List<String> = emptyList(),
    val translationRecentPairs: List<TranslationPair> = emptyList(),
    val globalRegexRules: List<RegexRule> = emptyList(),
    val novelRegexRules: Map<String, List<RegexRule>> = emptyMap(),
    val nextRuleId: Long = 100L,
    val userAgent: String = "",
    val cloudflareBypass: Boolean = true,
    val massAddDelayMs: Int = 2000,
    val downloadDelayMs: Int = 2000,
    val wtrMode: String = "ai",
    val wtrChapterDelayMs: Int = 0,
    val autoBackup: Boolean = false,
    val backupDirectory: String = "",
    val backupMaxCount: Int = 5,
    val backupIntervalMinutes: Int = 1440,
    val backupIncludeImages: Boolean = true,
    val backupIncludeSettings: Boolean = true,
    val backupIncludePlugins: Boolean = true,
    val backupIncludeHistoryOnly: Boolean = false,
    val backupLastTimestamp: Long = 0L,
    val autoBackupLastTimestamp: Long = 0L,
    val exportDirectory: String = "",
) {
    // API keys must never reach a log line. The generated data-class toString
    // would print every field, so keep only non-secret values here.
    override fun toString(): String =
        "PersistedSettings(provider=$provider, translationGlobalMode=$translationGlobalMode, " +
            "globalSource=$globalSource, globalTarget=$globalTarget, " +
            "presets=${promptPresets.size}, maxOutputTokens=$maxOutputTokens, " +
            "timeoutSeconds=$timeoutSeconds, maxAttempts=$maxAttempts, " +
            "chunkConcurrency=$chunkConcurrency, appLanguage=$appLanguage, " +
            "gridColumns=$gridColumns, listLayout=$listLayout, cardStyle=$cardStyle, " +
            "pinnedSources=${pinnedSources.size}, filterPresets=${filterPresets.size}, " +
            "novelPrompts=${novelPrompts.size}, pluginTranslation=${pluginTranslation.size}, " +
            "novelTranslation=${novelTranslation.size}, favorites=${translationFavorites.size}, " +
            "recentPairs=${translationRecentPairs.size}, " +
            "globalRegexRules=${globalRegexRules.size}, novelRegexRules=${novelRegexRules.size}, " +
            "autoBackup=$autoBackup, backupMaxCount=$backupMaxCount, " +
            "backupIntervalMinutes=$backupIntervalMinutes, " +
            "backupIncludeImages=$backupIncludeImages, " +
            "backupIncludeSettings=$backupIncludeSettings, " +
            "backupIncludePlugins=$backupIncludePlugins, " +
            "backupIncludeHistoryOnly=$backupIncludeHistoryOnly, " +
            "backupLastTimestamp=$backupLastTimestamp, " +
            "autoBackupLastTimestamp=$autoBackupLastTimestamp)"
}

// JVM-testable seam over SharedPreferences. Values are String/Int/Long/Boolean.
interface KeyValueStore {
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun getInt(key: String): Int?
    fun getLong(key: String): Long?
    fun getBoolean(key: String): Boolean?
    // False when the platform store refused the write (commit() failure).
    fun put(values: Map<String, Any>): Boolean
}

// Old persisted card names stay readable. The UI and renderer only use the
// approved presets; backup/restore never loses the user's choice.
internal fun migrateCardStyle(raw: String?): CardStyle = when (raw) {
    null -> CardStyle.Mat
    "CoverTitle" -> CardStyle.OnCover
    "BelowCover" -> CardStyle.Mat
    "Stats" -> CardStyle.ProgressAbove
    "Horizontal" -> CardStyle.Horizontal
    else -> CardStyle.entries.firstOrNull { it.name == raw } ?: CardStyle.Mat
}

class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getInt(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun getLong(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun getBoolean(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    override fun put(values: Map<String, Any>): Boolean {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> error("Unsupported settings value type: ${value::class.java.name}")
            }
        }
        // Callers run this on Dispatchers.IO; commit() keeps a process death
        // from dropping the last write (apply() is fire-and-forget).
        return editor.commit()
    }
}

class SettingsStore(private val values: KeyValueStore) {
    // The immediate pin write and the app's debounced snapshot writer both go
    // through this store; skip the identical second write.
    private var lastPersisted: PersistedSettings? = null

    fun load(): PersistedSettings {
        val defaults = PersistedSettings()
        val loaded = PersistedSettings(
            appLanguage = values.getString(KEY_APP_LANGUAGE) ?: defaults.appLanguage,
            downloadKeepMangaCache = values.getBoolean(KEY_DOWNLOAD_KEEP_MANGA_CACHE)
                ?: defaults.downloadKeepMangaCache,
            downloadPagesAfterView = values.getBoolean(KEY_DOWNLOAD_PAGES_AFTER_VIEW)
                ?: defaults.downloadPagesAfterView,
            videoVariantByBook = values.getString(KEY_VIDEO_VARIANT_BY_BOOK)
                ?.let(::decodeVideoVariants)
                ?: defaults.videoVariantByBook,
            videoAutoNext = values.getBoolean(KEY_VIDEO_AUTO_NEXT) ?: defaults.videoAutoNext,
            videoSeekStepMs = values.getInt(KEY_VIDEO_SEEK_STEP_MS) ?: defaults.videoSeekStepMs,
            videoControllerAutoHideMs = values.getInt(KEY_VIDEO_CONTROLLER_AUTO_HIDE_MS)
                ?: defaults.videoControllerAutoHideMs,
            // Stored as whole percent: 0.06 -> 6. The slider only emits
            // hundredths, so the round trip is lossless.
            videoGestureDragThreshold = values.getInt(KEY_VIDEO_GESTURE_DRAG_THRESHOLD)
                ?.let { it / 100f }
                ?: defaults.videoGestureDragThreshold,
            videoGestureSwappedSides = values.getBoolean(KEY_VIDEO_GESTURE_SWAPPED_SIDES)
                ?: defaults.videoGestureSwappedSides,
            videoGestureDoubleTap = values.getBoolean(KEY_VIDEO_GESTURE_DOUBLE_TAP)
                ?: defaults.videoGestureDoubleTap,
            videoGestureVerticalSwipe = values.getBoolean(KEY_VIDEO_GESTURE_VERTICAL_SWIPE)
                ?: defaults.videoGestureVerticalSwipe,
            videoGestureLongPressSpeed = values.getBoolean(KEY_VIDEO_GESTURE_LONG_PRESS_SPEED)
                ?: defaults.videoGestureLongPressSpeed,
            gridColumns = values.getInt(KEY_GRID_COLUMNS) ?: defaults.gridColumns,
            listLayout = values.getString(KEY_LIST_LAYOUT) ?: defaults.listLayout,
            cardStyle = migrateCardStyle(values.getString(KEY_CARD_STYLE)),
            sourceStrip = values.getString(KEY_SOURCE_STRIP) ?: defaults.sourceStrip,
            // Legacy stores used UI_SCALE for the text scale. On the first
            // load after this change it becomes textScale; uiScale starts at
            // 100 and both keys are written on the next save.
            textScale = (
                if (values.contains(KEY_TEXT_SCALE)) {
                    values.getInt(KEY_TEXT_SCALE) ?: defaults.textScale
                } else {
                    values.getInt(KEY_UI_SCALE) ?: defaults.textScale
                }
                ).coerceIn(50, 150),
            uiScale = (
                if (values.contains(KEY_TEXT_SCALE)) {
                    values.getInt(KEY_UI_SCALE) ?: defaults.uiScale
                } else {
                    defaults.uiScale
                }
                ).coerceIn(50, 150),
            newestFirst = values.getBoolean(KEY_NEWEST_FIRST) ?: defaults.newestFirst,
            pinnedSources = values.getString(KEY_PINNED_SOURCES)
                ?.let(::decodeStringList)
                ?: defaults.pinnedSources,
            filterPresets = values.getString(KEY_FILTER_PRESETS)
                ?.let(::decodeFilterPresets)
                ?: defaults.filterPresets,
            searchHistory = values.getString(KEY_SEARCH_HISTORY)
                ?.let(::decodeSearchHistory)
                ?: defaults.searchHistory,
            appUpdateCheck = values.getBoolean(KEY_APP_UPDATE_CHECK) ?: defaults.appUpdateCheck,
            libraryAutoUpdate = values.getBoolean(KEY_LIBRARY_AUTO_UPDATE)
                ?: defaults.libraryAutoUpdate,
            libraryLastRunAt = values.getLong(KEY_LIBRARY_LAST_RUN)
                ?: defaults.libraryLastRunAt,
            libraryIntervalHours = values.getInt(KEY_LIBRARY_INTERVAL_HOURS)
                ?: defaults.libraryIntervalHours,
            updateWifiOnly = values.getBoolean(KEY_UPDATE_WIFI_ONLY) ?: defaults.updateWifiOnly,
            updateWhileCharging = values.getBoolean(KEY_UPDATE_WHILE_CHARGING)
                ?: defaults.updateWhileCharging,
            translationGlobalMode = values.getBoolean(KEY_TRANSLATION_GLOBAL_MODE)
                ?: defaults.translationGlobalMode,
            globalSource = values.getString(KEY_GLOBAL_SOURCE) ?: defaults.globalSource,
            globalTarget = values.getString(KEY_GLOBAL_TARGET) ?: defaults.globalTarget,
            provider = values.getString(KEY_PROVIDER) ?: defaults.provider,
            googlePaKeys = values.getString(KEY_GOOGLE_PA_KEYS) ?: defaults.googlePaKeys,
            geminiKeys = values.getString(KEY_GEMINI_KEYS) ?: defaults.geminiKeys,
            geminiModel = values.getString(KEY_GEMINI_MODEL) ?: defaults.geminiModel,
            openAiKeys = values.getString(KEY_OPENAI_KEYS) ?: defaults.openAiKeys,
            openAiModel = values.getString(KEY_OPENAI_MODEL) ?: defaults.openAiModel,
            openAiBaseUrl = values.getString(KEY_OPENAI_BASE_URL) ?: defaults.openAiBaseUrl,
            openAiCompatibleKeys = values.getString(KEY_OPENAI_COMPATIBLE_KEYS)
                ?: defaults.openAiCompatibleKeys,
            openAiCompatibleModel = values.getString(KEY_OPENAI_COMPATIBLE_MODEL)
                ?: defaults.openAiCompatibleModel,
            openAiCompatibleBaseUrl = values.getString(KEY_OPENAI_COMPATIBLE_BASE_URL)
                ?: defaults.openAiCompatibleBaseUrl,
            anthropicKeys = values.getString(KEY_ANTHROPIC_KEYS) ?: defaults.anthropicKeys,
            anthropicModel = values.getString(KEY_ANTHROPIC_MODEL) ?: defaults.anthropicModel,
            deeplKeys = values.getString(KEY_DEEPL_KEYS) ?: defaults.deeplKeys,
            yandexCloudKeys = values.getString(KEY_YANDEX_CLOUD_KEYS)
                ?: defaults.yandexCloudKeys,
            yandexCloudFolderId = values.getString(KEY_YANDEX_CLOUD_FOLDER_ID)
                ?: defaults.yandexCloudFolderId,
            activePrompt = values.getString(KEY_ACTIVE_PROMPT) ?: defaults.activePrompt,
            promptPresets = values.getString(KEY_PROMPT_PRESETS)
                ?.let(::decodePresets)
                ?: defaults.promptPresets,
            promptEnglishLocale = values.getBoolean(KEY_PROMPT_ENGLISH_LOCALE)
                ?: defaults.promptEnglishLocale,
            maxOutputTokens = values.getInt(KEY_MAX_OUTPUT_TOKENS)
                ?: defaults.maxOutputTokens,
            timeoutSeconds = values.getInt(KEY_TIMEOUT_SECONDS) ?: defaults.timeoutSeconds,
            maxAttempts = values.getInt(KEY_MAX_ATTEMPTS) ?: defaults.maxAttempts,
            maxCharsPerRequest = values.getInt(KEY_MAX_CHARS_PER_REQUEST)
                ?: defaults.maxCharsPerRequest,
            chunkConcurrency = values.getInt(KEY_CHUNK_CONCURRENCY)
                ?: defaults.chunkConcurrency,
            useJsonSchema = values.getBoolean(KEY_USE_JSON_SCHEMA) ?: defaults.useJsonSchema,
            parallelEnabled = values.getBoolean(KEY_PARALLEL_ENABLED)
                ?: defaults.parallelEnabled,
            parallelOrder = values.getString(KEY_PARALLEL_ORDER) ?: defaults.parallelOrder,
            novelPrompts = values.getString(KEY_NOVEL_PROMPTS)
                ?.let(::decodeNovelPrompts)
                ?: defaults.novelPrompts,
            pluginTranslation = values.getString(KEY_PLUGIN_TRANSLATION)
                ?.let(::decodeTranslationMap)
                ?: defaults.pluginTranslation,
            novelTranslation = values.getString(KEY_NOVEL_TRANSLATION)
                ?.let(::decodeTranslationMap)
                ?: defaults.novelTranslation,
            translationFavorites = values.getString(KEY_TRANSLATION_FAVORITES)
                ?.let(::decodeStringList)
                ?: defaults.translationFavorites,
            translationRecentPairs = values.getString(KEY_TRANSLATION_RECENT_PAIRS)
                ?.let(::decodePairs)
                ?: defaults.translationRecentPairs,
            globalRegexRules = values.getString(KEY_GLOBAL_REGEX_RULES)
                ?.let(::decodeRules)
                ?: defaults.globalRegexRules,
            novelRegexRules = values.getString(KEY_NOVEL_REGEX_RULES)
                ?.let(::decodeNovelRules)
                ?: defaults.novelRegexRules,
            nextRuleId = values.getLong(KEY_NEXT_RULE_ID) ?: defaults.nextRuleId,
            userAgent = values.getString(KEY_USER_AGENT) ?: defaults.userAgent,
            cloudflareBypass = values.getBoolean(KEY_CLOUDFLARE_BYPASS)
                ?: defaults.cloudflareBypass,
            massAddDelayMs = values.getInt(KEY_MASS_ADD_DELAY_MS) ?: defaults.massAddDelayMs,
            downloadDelayMs = values.getInt(KEY_DOWNLOAD_DELAY_MS) ?: defaults.downloadDelayMs,
            wtrMode = values.getString(KEY_WTR_MODE) ?: defaults.wtrMode,
            wtrChapterDelayMs = values.getInt(KEY_WTR_CHAPTER_DELAY_MS)
                ?: defaults.wtrChapterDelayMs,
            autoBackup = values.getBoolean(KEY_AUTO_BACKUP) ?: defaults.autoBackup,
            backupDirectory = values.getString(KEY_BACKUP_DIRECTORY) ?: defaults.backupDirectory,
            backupMaxCount = values.getInt(KEY_BACKUP_MAX_COUNT) ?: defaults.backupMaxCount,
            backupIntervalMinutes = values.getInt(KEY_BACKUP_INTERVAL_MINUTES)
                ?: defaults.backupIntervalMinutes,
            backupIncludeImages = values.getBoolean(KEY_BACKUP_INCLUDE_IMAGES)
                ?: defaults.backupIncludeImages,
            backupIncludeSettings = values.getBoolean(KEY_BACKUP_INCLUDE_SETTINGS)
                ?: defaults.backupIncludeSettings,
            backupIncludePlugins = values.getBoolean(KEY_BACKUP_INCLUDE_PLUGINS)
                ?: defaults.backupIncludePlugins,
            backupIncludeHistoryOnly = values.getBoolean(KEY_BACKUP_INCLUDE_HISTORY_ONLY)
                ?: defaults.backupIncludeHistoryOnly,
            backupLastTimestamp = values.getLong(KEY_BACKUP_LAST_TIMESTAMP)
                ?: defaults.backupLastTimestamp,
            autoBackupLastTimestamp = values.getLong(KEY_AUTO_BACKUP_LAST_TIMESTAMP)
                ?: defaults.autoBackupLastTimestamp,
            exportDirectory = values.getString(KEY_EXPORT_DIRECTORY) ?: defaults.exportDirectory,
        )
        lastPersisted = loaded
        return loaded
    }

    fun save(settings: PersistedSettings): Boolean {
        if (settings == lastPersisted) return true
        val written = values.put(
            mapOf(
                KEY_APP_LANGUAGE to settings.appLanguage,
                KEY_DOWNLOAD_KEEP_MANGA_CACHE to settings.downloadKeepMangaCache,
                KEY_DOWNLOAD_PAGES_AFTER_VIEW to settings.downloadPagesAfterView,
                KEY_VIDEO_VARIANT_BY_BOOK to encodeVideoVariants(settings.videoVariantByBook),
                KEY_VIDEO_AUTO_NEXT to settings.videoAutoNext,
                KEY_VIDEO_SEEK_STEP_MS to settings.videoSeekStepMs,
                KEY_VIDEO_CONTROLLER_AUTO_HIDE_MS to settings.videoControllerAutoHideMs,
                KEY_VIDEO_GESTURE_DRAG_THRESHOLD to
                    (settings.videoGestureDragThreshold * 100).roundToInt(),
                KEY_VIDEO_GESTURE_SWAPPED_SIDES to settings.videoGestureSwappedSides,
                KEY_VIDEO_GESTURE_DOUBLE_TAP to settings.videoGestureDoubleTap,
                KEY_VIDEO_GESTURE_VERTICAL_SWIPE to settings.videoGestureVerticalSwipe,
                KEY_VIDEO_GESTURE_LONG_PRESS_SPEED to settings.videoGestureLongPressSpeed,
                KEY_GRID_COLUMNS to settings.gridColumns,
                KEY_LIST_LAYOUT to settings.listLayout,
                KEY_CARD_STYLE to settings.cardStyle.name,
                KEY_SOURCE_STRIP to settings.sourceStrip,
                KEY_TEXT_SCALE to settings.textScale,
                KEY_UI_SCALE to settings.uiScale,
                KEY_NEWEST_FIRST to settings.newestFirst,
                KEY_PINNED_SOURCES to encodeStringList(settings.pinnedSources),
                KEY_FILTER_PRESETS to encodeFilterPresets(settings.filterPresets),
                KEY_SEARCH_HISTORY to encodeSearchHistory(settings.searchHistory),
                KEY_APP_UPDATE_CHECK to settings.appUpdateCheck,
                KEY_LIBRARY_AUTO_UPDATE to settings.libraryAutoUpdate,
                KEY_LIBRARY_LAST_RUN to settings.libraryLastRunAt,
                KEY_LIBRARY_INTERVAL_HOURS to settings.libraryIntervalHours,
                KEY_UPDATE_WIFI_ONLY to settings.updateWifiOnly,
                KEY_UPDATE_WHILE_CHARGING to settings.updateWhileCharging,
                KEY_TRANSLATION_GLOBAL_MODE to settings.translationGlobalMode,
                KEY_GLOBAL_SOURCE to settings.globalSource,
                KEY_GLOBAL_TARGET to settings.globalTarget,
                KEY_PROVIDER to settings.provider,
                KEY_GOOGLE_PA_KEYS to settings.googlePaKeys,
                KEY_GEMINI_KEYS to settings.geminiKeys,
                KEY_GEMINI_MODEL to settings.geminiModel,
                KEY_OPENAI_KEYS to settings.openAiKeys,
                KEY_OPENAI_MODEL to settings.openAiModel,
                KEY_OPENAI_BASE_URL to settings.openAiBaseUrl,
                KEY_OPENAI_COMPATIBLE_KEYS to settings.openAiCompatibleKeys,
                KEY_OPENAI_COMPATIBLE_MODEL to settings.openAiCompatibleModel,
                KEY_OPENAI_COMPATIBLE_BASE_URL to settings.openAiCompatibleBaseUrl,
                KEY_ANTHROPIC_KEYS to settings.anthropicKeys,
                KEY_ANTHROPIC_MODEL to settings.anthropicModel,
                KEY_DEEPL_KEYS to settings.deeplKeys,
                KEY_YANDEX_CLOUD_KEYS to settings.yandexCloudKeys,
                KEY_YANDEX_CLOUD_FOLDER_ID to settings.yandexCloudFolderId,
                KEY_ACTIVE_PROMPT to settings.activePrompt,
                KEY_PROMPT_PRESETS to encodePresets(settings.promptPresets),
                KEY_PROMPT_ENGLISH_LOCALE to settings.promptEnglishLocale,
                KEY_MAX_OUTPUT_TOKENS to settings.maxOutputTokens,
                KEY_TIMEOUT_SECONDS to settings.timeoutSeconds,
                KEY_MAX_ATTEMPTS to settings.maxAttempts,
                KEY_MAX_CHARS_PER_REQUEST to settings.maxCharsPerRequest,
                KEY_CHUNK_CONCURRENCY to settings.chunkConcurrency,
                KEY_USE_JSON_SCHEMA to settings.useJsonSchema,
                KEY_PARALLEL_ENABLED to settings.parallelEnabled,
                KEY_PARALLEL_ORDER to settings.parallelOrder,
                KEY_NOVEL_PROMPTS to encodeNovelPrompts(settings.novelPrompts),
                KEY_PLUGIN_TRANSLATION to encodeTranslationMap(settings.pluginTranslation),
                KEY_NOVEL_TRANSLATION to encodeTranslationMap(settings.novelTranslation),
                KEY_TRANSLATION_FAVORITES to encodeStringList(settings.translationFavorites),
                KEY_TRANSLATION_RECENT_PAIRS to encodePairs(settings.translationRecentPairs),
                KEY_GLOBAL_REGEX_RULES to encodeRules(settings.globalRegexRules),
                KEY_NOVEL_REGEX_RULES to encodeNovelRules(settings.novelRegexRules),
                KEY_NEXT_RULE_ID to settings.nextRuleId,
                KEY_USER_AGENT to settings.userAgent,
                KEY_CLOUDFLARE_BYPASS to settings.cloudflareBypass,
                KEY_MASS_ADD_DELAY_MS to settings.massAddDelayMs,
                KEY_DOWNLOAD_DELAY_MS to settings.downloadDelayMs,
                KEY_WTR_MODE to settings.wtrMode,
                KEY_WTR_CHAPTER_DELAY_MS to settings.wtrChapterDelayMs,
                KEY_AUTO_BACKUP to settings.autoBackup,
                KEY_BACKUP_DIRECTORY to settings.backupDirectory,
                KEY_BACKUP_MAX_COUNT to settings.backupMaxCount,
                KEY_BACKUP_INTERVAL_MINUTES to settings.backupIntervalMinutes,
                KEY_BACKUP_INCLUDE_IMAGES to settings.backupIncludeImages,
                KEY_BACKUP_INCLUDE_SETTINGS to settings.backupIncludeSettings,
                KEY_BACKUP_INCLUDE_PLUGINS to settings.backupIncludePlugins,
                KEY_BACKUP_INCLUDE_HISTORY_ONLY to settings.backupIncludeHistoryOnly,
                KEY_BACKUP_LAST_TIMESTAMP to settings.backupLastTimestamp,
                KEY_AUTO_BACKUP_LAST_TIMESTAMP to settings.autoBackupLastTimestamp,
                KEY_EXPORT_DIRECTORY to settings.exportDirectory,
            ),
        )
        if (written) lastPersisted = settings
        return written
    }

    companion object {
        const val PREFS_NAME = "mirutime_settings"

        private const val KEY_APP_LANGUAGE = "APP_LANGUAGE_CODE"
        private const val KEY_DOWNLOAD_KEEP_MANGA_CACHE = "DOWNLOAD_KEEP_MANGA_CACHE"
        private const val KEY_DOWNLOAD_PAGES_AFTER_VIEW = "DOWNLOAD_PAGES_AFTER_VIEW"
        private const val KEY_VIDEO_VARIANT_BY_BOOK = "VIDEO_VARIANT_BOOK_QUALITY_MAP"
        private const val KEY_VIDEO_AUTO_NEXT = "VIDEO_AUTO_NEXT_ENABLED"
        private const val KEY_VIDEO_SEEK_STEP_MS = "VIDEO_SEEK_STEP_MS"
        private const val KEY_VIDEO_CONTROLLER_AUTO_HIDE_MS = "VIDEO_CONTROLLER_AUTO_HIDE_MS"
        private const val KEY_VIDEO_GESTURE_DRAG_THRESHOLD = "VIDEO_GESTURE_DRAG_THRESHOLD"
        private const val KEY_VIDEO_GESTURE_SWAPPED_SIDES = "VIDEO_GESTURE_SWAPPED_SIDES"
        private const val KEY_VIDEO_GESTURE_DOUBLE_TAP = "VIDEO_GESTURE_DOUBLE_TAP"
        private const val KEY_VIDEO_GESTURE_VERTICAL_SWIPE = "VIDEO_GESTURE_VERTICAL_SWIPE"
        private const val KEY_VIDEO_GESTURE_LONG_PRESS_SPEED = "VIDEO_GESTURE_LONG_PRESS_SPEED"
        private const val KEY_GRID_COLUMNS = "BOOKS_GRID_COLUMNS"
        private const val KEY_LIST_LAYOUT = "BOOKS_LIST_LAYOUT_MODE"
        private const val KEY_CARD_STYLE = "LIBRARY_SOURCE_STRIP_POSITION"
        private const val KEY_SOURCE_STRIP = "LIBRARY_SOURCE_STRIP"
        private const val KEY_UI_SCALE = "UI_SCALE"
        private const val KEY_TEXT_SCALE = "TEXT_SCALE"
        private const val KEY_NEWEST_FIRST = "CHAPTERS_SORT_ASCENDING"
        private const val KEY_PINNED_SOURCES = "FINDER_PINNED_SOURCES"
        private const val KEY_FILTER_PRESETS = "FINDER_FILTER_PRESETS"
        private const val KEY_SEARCH_HISTORY = "FINDER_SEARCH_HISTORY"
        private const val KEY_APP_UPDATE_CHECK = "GLOBAL_APP_UPDATER_CHECKER_ENABLED"
        private const val KEY_LIBRARY_AUTO_UPDATE = "GLOBAL_APP_AUTOMATIC_LIBRARY_UPDATES_ENABLED"
        private const val KEY_LIBRARY_LAST_RUN = "GLOBAL_APP_LIBRARY_LAST_RUN_AT"
        private const val KEY_LIBRARY_INTERVAL_HOURS = "GLOBAL_APP_AUTOMATIC_LIBRARY_UPDATES_INTERVAL_HOURS"
        private const val KEY_UPDATE_WIFI_ONLY = "GLOBAL_APP_UPDATES_WIFI_ONLY"
        private const val KEY_UPDATE_WHILE_CHARGING = "GLOBAL_APP_UPDATES_WHILE_CHARGING"
        private const val KEY_TRANSLATION_GLOBAL_MODE = "TRANSLATION_GLOBAL_MODE"
        private const val KEY_GLOBAL_SOURCE = "GLOBAL_TRANSLATION_PREFERRED_SOURCE"
        private const val KEY_GLOBAL_TARGET = "GLOBAL_TRANSLATION_PREFERRED_TARGET"
        private const val KEY_PROVIDER = "TRANSLATION_PROVIDER"
        private const val KEY_GOOGLE_PA_KEYS = "TRANSLATION_GOOGLE_PA_KEYS"
        private const val KEY_GEMINI_KEYS = "TRANSLATION_GEMINI_KEYS"
        private const val KEY_GEMINI_MODEL = "TRANSLATION_GEMINI_MODEL"
        private const val KEY_OPENAI_KEYS = "TRANSLATION_OPENAI_KEYS"
        private const val KEY_OPENAI_MODEL = "TRANSLATION_OPENAI_MODEL"
        private const val KEY_OPENAI_BASE_URL = "TRANSLATION_OPENAI_BASE_URL"
        private const val KEY_OPENAI_COMPATIBLE_KEYS = "TRANSLATION_OPENAI_COMPATIBLE_KEYS"
        private const val KEY_OPENAI_COMPATIBLE_MODEL = "TRANSLATION_OPENAI_COMPATIBLE_MODEL"
        private const val KEY_OPENAI_COMPATIBLE_BASE_URL = "TRANSLATION_OPENAI_COMPATIBLE_BASE_URL"
        private const val KEY_ANTHROPIC_KEYS = "TRANSLATION_ANTHROPIC_KEYS"
        private const val KEY_ANTHROPIC_MODEL = "TRANSLATION_ANTHROPIC_MODEL"
        private const val KEY_DEEPL_KEYS = "TRANSLATION_DEEPL_KEYS"
        private const val KEY_YANDEX_CLOUD_KEYS = "TRANSLATION_YANDEX_CLOUD_KEYS"
        private const val KEY_YANDEX_CLOUD_FOLDER_ID = "TRANSLATION_YANDEX_CLOUD_FOLDER_ID"
        private const val KEY_ACTIVE_PROMPT = "TRANSLATION_ACTIVE_SYSTEM_PROMPT"
        private const val KEY_PROMPT_PRESETS = "TRANSLATION_PROMPT_PRESETS"
        private const val KEY_PROMPT_ENGLISH_LOCALE = "TRANSLATION_PROMPT_USE_ENGLISH_LOCALE"
        private const val KEY_MAX_OUTPUT_TOKENS = "TRANSLATION_MAX_OUTPUT_TOKENS"
        private const val KEY_TIMEOUT_SECONDS = "TRANSLATION_TIMEOUT_SECONDS"
        private const val KEY_MAX_ATTEMPTS = "TRANSLATION_MAX_ATTEMPTS"
        private const val KEY_MAX_CHARS_PER_REQUEST = "TRANSLATION_MAX_CHARS_PER_REQUEST"
        private const val KEY_CHUNK_CONCURRENCY = "TRANSLATION_CHUNK_CONCURRENCY"
        private const val KEY_USE_JSON_SCHEMA = "TRANSLATION_USE_JSON_SCHEMA"
        private const val KEY_PARALLEL_ENABLED = "TRANSLATION_PARALLEL_ENABLED"
        private const val KEY_PARALLEL_ORDER = "TRANSLATION_PARALLEL_ORDER"
        private const val KEY_NOVEL_PROMPTS = "TRANSLATION_NOVEL_PROMPTS"
        private const val KEY_PLUGIN_TRANSLATION = "TRANSLATION_PLUGIN_MAP"
        private const val KEY_NOVEL_TRANSLATION = "TRANSLATION_BOOK_ENABLED_MAP"
        private const val KEY_TRANSLATION_FAVORITES = "TRANSLATION_FAVORITE_LANGUAGES"
        private const val KEY_TRANSLATION_RECENT_PAIRS = "TRANSLATION_RECENT_PAIRS"
        private const val KEY_GLOBAL_REGEX_RULES = "USER_REGEX_CLEANUP_RULES"
        private const val KEY_NOVEL_REGEX_RULES = "USER_REGEX_CLEANUP_RULES_PER_NOVEL"
        private const val KEY_NEXT_RULE_ID = "USER_REGEX_CLEANUP_NEXT_ID"
        private const val KEY_USER_AGENT = "SCRAPER_USER_AGENT"
        private const val KEY_CLOUDFLARE_BYPASS = "CLOUDFLARE_BYPASS_ENABLED"
        private const val KEY_MASS_ADD_DELAY_MS = "MASS_ADD_DELAY_MS"
        private const val KEY_DOWNLOAD_DELAY_MS = "DOWNLOAD_DELAY_MS"
        private const val KEY_WTR_MODE = "WTRLAB_MODE"
        private const val KEY_WTR_CHAPTER_DELAY_MS = "WTRLAB_CHAPTER_DELAY"
        private const val KEY_AUTO_BACKUP = "BACKUP_AUTO_ENABLED"
        private const val KEY_BACKUP_DIRECTORY = "BACKUP_DIRECTORY_URI"
        private const val KEY_BACKUP_MAX_COUNT = "BACKUP_MAX_COUNT"
        private const val KEY_BACKUP_INTERVAL_MINUTES = "BACKUP_INTERVAL_MINUTES"
        private const val KEY_BACKUP_INCLUDE_IMAGES = "BACKUP_INCLUDE_IMAGES"
        private const val KEY_BACKUP_INCLUDE_SETTINGS = "BACKUP_INCLUDE_SETTINGS"
        private const val KEY_BACKUP_INCLUDE_PLUGINS = "BACKUP_INCLUDE_PLUGINS"
        private const val KEY_BACKUP_INCLUDE_HISTORY_ONLY = "BACKUP_INCLUDE_HISTORY_ONLY"
        private const val KEY_BACKUP_LAST_TIMESTAMP = "BACKUP_LAST_TIMESTAMP"
        private const val KEY_AUTO_BACKUP_LAST_TIMESTAMP = "BACKUP_AUTO_LAST_TIMESTAMP"
        private const val KEY_EXPORT_DIRECTORY = "EXPORT_DIRECTORY_URI"

        // Length-prefixed encoding keeps arbitrary prompt text (colons,
        // newlines, separators) intact without adding a JSON dependency.
        // Kept on the original per-field format so presets written before the
        // rest of the settings snapshot became durable still load.
        internal fun encodePresets(presets: List<Pair<String, String>>): String =
            buildString {
                presets.forEach { (name, prompt) ->
                    append(name.length).append(':').append(name)
                    append(prompt.length).append(':').append(prompt)
                }
            }

        internal fun decodePresets(raw: String): List<Pair<String, String>> {
            val result = ArrayList<Pair<String, String>>()
            var index = 0
            while (index < raw.length) {
                val nameColon = raw.indexOf(':', index)
                if (nameColon < 0) break
                val nameLength = raw.substring(index, nameColon).toIntOrNull() ?: break
                val nameStart = nameColon + 1
                val nameEnd = nameStart + nameLength
                if (nameEnd > raw.length) break
                val promptColon = raw.indexOf(':', nameEnd)
                if (promptColon < 0) break
                val promptLength = raw.substring(nameEnd, promptColon).toIntOrNull() ?: break
                val promptStart = promptColon + 1
                val promptEnd = promptStart + promptLength
                if (promptEnd > raw.length) break
                result += raw.substring(nameStart, nameEnd) to raw.substring(promptStart, promptEnd)
                index = promptEnd
            }
            return result
        }

        internal fun encodeStringList(values: List<String>): String =
            encodeRecords(values.map(::listOf))

        internal fun decodeStringList(raw: String): List<String> =
            decodeRecords(raw).mapNotNull { it.firstOrNull() }

        internal fun encodeVideoVariants(map: Map<String, String>): String =
            encodeRecords(map.map { (bookUrl, quality) -> listOf(bookUrl, quality) })

        internal fun decodeVideoVariants(raw: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            decodeRecords(raw).forEach { fields ->
                if (fields.size < 2 || fields[0].isBlank()) return@forEach
                result[fields[0]] = fields[1]
            }
            return result
        }

        internal fun encodePairs(pairs: List<TranslationPair>): String =
            encodeRecords(pairs.map { listOf(it.source, it.target) })

        internal fun decodePairs(raw: String): List<TranslationPair> =
            decodeRecords(raw).mapNotNull { fields ->
                if (fields.size < 2) null else TranslationPair(fields[0], fields[1])
            }

        internal fun encodeRules(rules: List<RegexRule>): String =
            encodeRecords(rules.map(::ruleFields))

        internal fun decodeRules(raw: String): List<RegexRule> =
            decodeRecords(raw).mapNotNull(::ruleFromFields)

        internal fun encodeNovelPrompts(prompts: List<NovelPrompt>): String =
            encodeRecords(
                prompts.map { listOf(it.bookUrl, it.title, it.prompt, it.append.toString()) },
            )

        internal fun decodeNovelPrompts(raw: String): List<NovelPrompt> =
            decodeRecords(raw).mapNotNull { fields ->
                if (fields.size < 4) return@mapNotNull null
                NovelPrompt(
                    bookUrl = fields[0],
                    title = fields[1],
                    prompt = fields[2],
                    append = fields[3].toBooleanStrictOrNull() ?: false,
                )
            }

        internal fun encodeTranslationMap(map: Map<String, TranslationConfig>): String =
            encodeRecords(map.map { (key, config) -> listOf(key) + translationFields(config) })

        internal fun decodeTranslationMap(raw: String): Map<String, TranslationConfig> {
            val result = linkedMapOf<String, TranslationConfig>()
            decodeRecords(raw).forEach { fields ->
                if (fields.size < 11) return@forEach
                result[fields[0]] = translationFromFields(fields.drop(1))
            }
            return result
        }

        internal fun encodeNovelRules(map: Map<String, List<RegexRule>>): String =
            encodeRecords(
                map.flatMap { (key, rules) -> rules.map { listOf(key) + ruleFields(it) } },
            )

        internal fun encodeFilterPresets(map: Map<String, List<FilterPreset>>): String =
            encodeRecords(
                map.flatMap { (sourceId, presets) ->
                    presets.map { listOf(sourceId) + presetFields(it) }
                },
            )

        internal fun encodeSearchHistory(map: Map<String, List<String>>): String =
            encodeRecords(
                map.flatMap { (key, history) -> history.map { listOf(key, it) } },
            )

        internal fun decodeSearchHistory(raw: String): Map<String, List<String>> {
            val result = linkedMapOf<String, MutableList<String>>()
            decodeRecords(raw).forEach { fields ->
                if (fields.size < 2) return@forEach
                result.getOrPut(fields[0]) { mutableListOf() }.add(fields[1])
            }
            return result
        }

        internal fun decodeFilterPresets(raw: String): Map<String, List<FilterPreset>> {
            val result = linkedMapOf<String, MutableList<FilterPreset>>()
            decodeRecords(raw).forEach { fields ->
                if (fields.size < 6) return@forEach
                val preset = presetFromFields(fields.drop(1)) ?: return@forEach
                result.getOrPut(fields[0]) { mutableListOf() }.add(preset)
            }
            return result
        }

        internal fun decodeNovelRules(raw: String): Map<String, List<RegexRule>> {
            val result = linkedMapOf<String, MutableList<RegexRule>>()
            decodeRecords(raw).forEach { fields ->
                if (fields.size < 7) return@forEach
                val rule = ruleFromFields(fields.drop(1)) ?: return@forEach
                result.getOrPut(fields[0]) { mutableListOf() }.add(rule)
            }
            return result
        }

        // Old records end after the genre list. New records append this marker
        // and the plugin values so old presets keep decoding unchanged.
        private const val PluginValuesMarker = "\u0000plugin-values"

        private fun presetFields(preset: FilterPreset): List<String> {
            val pluginValues = preset.filters.pluginValues.filterValues { it.isNotEmpty() }
            val pluginEntries = pluginValues.toSortedMap().entries.flatMap { (key, values) ->
                listOf(key, values.size.toString()) + values
            }
            return listOf(
                preset.name,
                preset.filters.sort.name,
                preset.filters.descending.toString(),
                preset.filters.ongoingOnly.toString(),
                preset.filters.query,
            ) + preset.filters.genres.toList() +
                listOf(PluginValuesMarker, pluginValues.size.toString()) +
                pluginEntries
        }

        private fun presetFromFields(fields: List<String>): FilterPreset? {
            if (fields.size < 5) return null
            val marker = (5 until fields.size).firstOrNull { fields[it] == PluginValuesMarker }
            val genresEnd = marker ?: fields.size
            val pluginValues = if (marker == null) {
                emptyMap()
            } else {
                decodePluginValues(fields, marker + 1)
            }
            return FilterPreset(
                name = fields[0],
                filters = CatalogFilters(
                    query = fields[4],
                    sort = CatalogSort.entries.firstOrNull { it.name == fields[1] }
                        ?: CatalogSort.Popular,
                    descending = fields[2].toBooleanStrictOrNull() ?: true,
                    ongoingOnly = fields[3].toBooleanStrictOrNull() ?: false,
                    genres = fields.subList(5, genresEnd).filter { it.isNotBlank() }.toSet(),
                    pluginValues = pluginValues,
                ),
            )
        }

        private fun decodePluginValues(
            fields: List<String>,
            start: Int,
        ): Map<String, List<String>> {
            val count = fields.getOrNull(start)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val result = linkedMapOf<String, List<String>>()
            var index = start + 1
            repeat(count) {
                val key = fields.getOrNull(index++) ?: return@repeat
                val size = fields.getOrNull(index++)?.toIntOrNull()?.coerceAtLeast(0) ?: return@repeat
                val end = minOf(index + size, fields.size)
                val values = fields.subList(index, end)
                index = end
                if (key.isNotBlank() && values.isNotEmpty()) result[key] = values.toList()
            }
            return result
        }

        private fun ruleFields(rule: RegexRule): List<String> = listOf(
            rule.id.toString(),
            rule.pattern,
            rule.replacement,
            rule.description,
            rule.enabled.toString(),
            rule.wholeWords.toString(),
            rule.scope.name,
        )

        private fun ruleFromFields(fields: List<String>): RegexRule? {
            if (fields.size < 6) return null
            return RegexRule(
                id = fields[0].toLongOrNull() ?: return null,
                pattern = fields[1],
                replacement = fields[2],
                description = fields[3],
                enabled = fields[4].toBooleanStrictOrNull() ?: true,
                wholeWords = fields[5].toBooleanStrictOrNull() ?: false,
                // Records written before rule scopes existed mean NoveLA's
                // "apply to both" behaviour.
                scope = fields.getOrNull(6)
                    ?.let { runCatching { RegexRuleScope.valueOf(it) }.getOrNull() }
                    ?: RegexRuleScope.Both,
            )
        }

        private fun translationFields(config: TranslationConfig): List<String> = listOf(
            config.enabled.toString(),
            config.source,
            config.target,
            config.provider,
            config.full.toString(),
            config.prompt,
            config.hideLibrary.toString(),
            config.hideHistory.toString(),
            config.hideCatalog.toString(),
            config.hideSearch.toString(),
            config.chapterTitles.toString(),
        )

        private fun translationFromFields(fields: List<String>): TranslationConfig =
            TranslationConfig(
                enabled = fields[0].toBooleanStrictOrNull() ?: false,
                source = fields[1],
                target = fields[2],
                provider = fields[3],
                full = fields[4].toBooleanStrictOrNull() ?: false,
                prompt = fields[5],
                hideLibrary = fields[6].toBooleanStrictOrNull() ?: false,
                hideHistory = fields[7].toBooleanStrictOrNull() ?: false,
                hideCatalog = fields[8].toBooleanStrictOrNull() ?: false,
                hideSearch = fields[9].toBooleanStrictOrNull() ?: false,
                // Older records have no chapter-titles field: translate as before.
                chapterTitles = fields.getOrNull(10)?.toBooleanStrictOrNull() ?: true,
            )

        // Format: <records>;<fields>;<len>:<value>... Values may contain any
        // character, including ':' and ';', because only the length matters.
        internal fun encodeRecords(records: List<List<String>>): String = buildString {
            append(records.size).append(';')
            records.forEach { fields ->
                append(fields.size).append(';')
                fields.forEach { field -> append(field.length).append(':').append(field) }
            }
        }

        internal fun decodeRecords(raw: String): List<List<String>> {
            val result = ArrayList<List<String>>()
            var index = 0

            fun readInt(): Int? {
                val separator = raw.indexOf(';', index)
                if (separator < 0) return null
                val value = raw.substring(index, separator).toIntOrNull() ?: return null
                index = separator + 1
                return value
            }

            val recordCount = readInt() ?: return emptyList()
            if (recordCount < 0) return emptyList()
            repeat(recordCount) {
                val fieldCount = readInt() ?: return result
                if (fieldCount < 0) return result
                val fields = ArrayList<String>(fieldCount)
                repeat(fieldCount) {
                    val colon = raw.indexOf(':', index)
                    if (colon < 0) return result
                    val length = raw.substring(index, colon).toIntOrNull() ?: return result
                    if (length < 0) return result
                    val start = colon + 1
                    val end = start + length
                    if (end > raw.length) return result
                    fields += raw.substring(start, end)
                    index = end
                }
                result += fields
            }
            return result
        }
    }
}
