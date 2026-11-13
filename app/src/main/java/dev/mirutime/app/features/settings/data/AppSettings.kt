package dev.mirutime.app.features.settings.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import dev.mirutime.core.text.RegexRuleTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmName

// TRANSLATION_NOVEL_PROMPTS entry. `append` = add to the global prompt,
// otherwise it replaces it for this novel.
@Serializable
data class NovelPrompt(
    val bookUrl: String,
    val title: String,
    val prompt: String,
    val append: Boolean,
)

// Per-plugin and per-novel translation maps entry.
// Empty provider = the global one; `full` = also title + description (scope
// FULL) and unlocks the hidden-title flags; empty prompt = the built-in one.
@Serializable
data class TranslationConfig(
    val enabled: Boolean = false,
    val source: String = "",
    val target: String = "",
    val provider: String = "",
    val full: Boolean = false,
    val prompt: String = "",
    val hideLibrary: Boolean = false,
    val hideHistory: Boolean = false,
    val hideCatalog: Boolean = false,
    val hideSearch: Boolean = false,
    // Full scope only: chapter titles are metadata, not chapter text.
    val chapterTitles: Boolean = true,
)

typealias PluginTranslation = TranslationConfig
typealias NovelTranslation = TranslationConfig

// One entry of TRANSLATION_RECENT_PAIRS.
@Serializable
data class TranslationPair(val source: String, val target: String)

// Storage buckets of Settings → Data. Sizes in bytes.
enum class StorageBucket { Database, Images, NovelCache, MangaCache, OfflineVideo }

// Application settings. Every value is Compose snapshot
// state, so screens recompose on change and values survive navigation.
// MiruApp loads PersistedSettings synchronously before the first screen and a
// debounced snapshotFlow writes durable changes back through SettingsStore.
// Selection sheets, dialogs and drafts stay in their screen's local state and
// never enter this object.
object AppSettings {
    // --- Appearance ---
    // APP_LANGUAGE_CODE / IS_FOLLOW_SYSTEM_LANGUAGE ("system" = follow).
    var appLanguage by mutableStateOf("system")

    // --- Downloads ---
    // DOWNLOAD_KEEP_MANGA_CACHE (keep the chapter and prefetch cache after
    // reading) / DOWNLOAD_PAGES_AFTER_VIEW
    var downloadKeepMangaCache by mutableStateOf(true)
    var downloadPagesAfterView by mutableStateOf(false)

    // --- Video player ---
    // VIDEO_VARIANT_BOOK_QUALITY_MAP: per-book stream quality label. Empty =
    // no choice yet, so the player asks when there is more than one source.
    val videoVariantByBook = mutableStateMapOf<String, String>()

    fun videoVariantForBook(bookUrl: String): String? = videoVariantByBook[bookUrl]

    fun setVideoVariantForBook(bookUrl: String, quality: String) {
        if (quality.isBlank()) {
            videoVariantByBook.remove(bookUrl)
        } else {
            videoVariantByBook[bookUrl] = quality
        }
    }

    // VIDEO_AUTO_NEXT_ENABLED: STATE_ENDED advances to the next episode.
    var videoAutoNext by mutableStateOf(true)
    // VIDEO_SEEK_STEP_MS: one double-tap / triple-tap seek step.
    var videoSeekStepMs by mutableIntStateOf(10_000)
    // VIDEO_CONTROLLER_AUTO_HIDE_MS: controls timeout. 0 = only a tap hides.
    // 3.5 s keeps the pre-setting player behavior.
    var videoControllerAutoHideMs by mutableIntStateOf(3_500)
    // VIDEO_GESTURE_DRAG_THRESHOLD: fraction of the width before a horizontal
    // drag starts seeking. 6% keeps the previous constant.
    var videoGestureDragThreshold by mutableFloatStateOf(0.06f)
    // VIDEO_GESTURE_*: side mirror and per-gesture switches.
    var videoGestureSwappedSides by mutableStateOf(false)
    var videoGestureDoubleTap by mutableStateOf(true)
    var videoGestureVerticalSwipe by mutableStateOf(true)
    var videoGestureLongPressSpeed by mutableStateOf(true)

    // --- Library (BOOKS_GRID_COLUMNS, BOOKS_LIST_LAYOUT_MODE,
    // LIBRARY_SOURCE_STRIP_POSITION, CHAPTERS_SORT_ASCENDING) ---
    var gridColumns by mutableIntStateOf(3)
    var listLayout by mutableStateOf("grid")
    // LIBRARY_SOURCE_STRIP_POSITION: the card style shared by the library,
    // the dashboard rails and the catalog grid (OnCover / BelowCover / InfoPanel).
    var cardStyle by mutableStateOf(CardStyle.Mat)
    var sourceStrip by mutableStateOf("below")
    // In-app text scale override, 50..200 step 5. 100 = system size.
    var textScale by mutableStateOf(100)
    // Whole-UI scale override, 50..200 step 5. 100 = system size.
    var uiScale by mutableStateOf(100)
    var newestFirst by mutableStateOf(true)

    // --- Finder ---
    // Icons are always on now: the FINDER_SHOW_PLUGIN_ICONS toggle is gone and
    // old stores/backups that still carry the key are simply ignored.
    // FINDER_PINNED_SOURCES: the built-in local sources start pinned.
    // An explicit empty set from the store stays empty.
    var pinnedSources by mutableStateOf<Set<String>>(defaultPinnedSources.toSet())
    // FINDER_FILTER_PRESETS, key = source id. Max FilterPreset.Max per source.
    val filterPresets = mutableStateMapOf<String, List<FilterPreset>>()
    // FINDER_SEARCH_HISTORY, key = source id or "__global__". Persisted through
    // SettingsStore like every other durable setting; no in-memory fixture.
    val searchHistory = mutableStateMapOf<String, List<String>>()

    fun searchHistoryFor(key: String): List<String> = searchHistory[key].orEmpty()

    fun setSearchHistory(key: String, history: List<String>) {
        searchHistory[key] = history
    }

    fun togglePinnedSource(sourceId: String) {
        pinnedSources = if (sourceId in pinnedSources) {
            pinnedSources - sourceId
        } else {
            pinnedSources + sourceId
        }
    }

    // --- Updates ---
    // GLOBAL_APP_UPDATER_CHECKER_ENABLED
    var appUpdateCheck by mutableStateOf(true)
    // GLOBAL_APP_AUTOMATIC_LIBRARY_UPDATES_ENABLED / _INTERVAL_HOURS
    // (our menu: on launch / 12 h / 24 h / 72 h / week).
    var libraryAutoUpdate by mutableStateOf(true)
    // Epoch millis of the last completed library update run (persisted).
    var libraryLastRunAt by androidx.compose.runtime.mutableLongStateOf(0L)
    var libraryIntervalHours by mutableIntStateOf(24)
    var updateWifiOnly by mutableStateOf(false)
    var updateWhileCharging by mutableStateOf(true)

    // --- Translation ---
    // TRANSLATION_GLOBAL_MODE, GLOBAL_TRANSLATION_PREFERRED_SOURCE / _TARGET
    var translationGlobalMode by mutableStateOf(false)
    var globalSource by mutableStateOf("en")
    var globalTarget by mutableStateOf("")
    // TRANSLATION_PROVIDER: one of the ten ProviderId entries (GOOGLE_PA,
    // GOOGLE_FREE, GEMINI, OPENAI, OPENAI_COMPATIBLE, ANTHROPIC, DEEPL,
    // YANDEX_CLOUD, YANDEX_FREE, YANDEX_PA). Keys are newline-separated lists;
    // the resolver splits them into TranslationProviderConfig.apiKeys.
    var provider by mutableStateOf("GOOGLE_PA")
    var googlePaKeys by mutableStateOf("")
    var geminiKeys by mutableStateOf("")
    var geminiModel by mutableStateOf("gemini-2.5-flash-lite")
    var openAiKeys by mutableStateOf("")
    var openAiModel by mutableStateOf("gpt-4o-mini")
    var openAiBaseUrl by mutableStateOf("https://api.openai.com")
    var openAiCompatibleKeys by mutableStateOf("")
    var openAiCompatibleModel by mutableStateOf("")
    var openAiCompatibleBaseUrl by mutableStateOf("")
    var anthropicKeys by mutableStateOf("")
    var anthropicModel by mutableStateOf("claude-sonnet-5-5")
    var deeplKeys by mutableStateOf("")
    var yandexCloudKeys by mutableStateOf("")
    var yandexCloudFolderId by mutableStateOf("")
    // TRANSLATION_ACTIVE_SYSTEM_PROMPT ("" = built-in), _PROMPT_PRESETS,
    // _PROMPT_USE_ENGLISH_LOCALE
    var activePrompt by mutableStateOf("")
    val promptPresets = mutableStateListOf<Pair<String, String>>().apply {
        addAll(defaultPromptPresets)
    }
    var promptEnglishLocale by mutableStateOf(true)
    // TRANSLATION_MAX_OUTPUT_TOKENS (0 = do not send), request timeout in
    // seconds, retry attempts, character budget per request (0 = auto),
    // concurrent chunks (1..32, YANDEX_PA caps at 16) and JSON-schema mode
    // (hidden for now, always false).
    var maxOutputTokens by mutableIntStateOf(0)
    var timeoutSeconds by mutableIntStateOf(180)
    var maxAttempts by mutableIntStateOf(6)
    var maxCharsPerRequest by mutableIntStateOf(0)
    var chunkConcurrency by mutableIntStateOf(8)
    var useJsonSchema by mutableStateOf(false)
    // TRANSLATION_PARALLEL_ENABLED / _ORDER (TRANSLATION_FIRST | ORIGINAL_FIRST)
    var parallelEnabled by mutableStateOf(false)
    var parallelOrder by mutableStateOf("TRANSLATION_FIRST")
    // TRANSLATION_NOVEL_PROMPTS. Starts empty: a novel prompt is user data,
    // not a bundled sample.
    val novelPrompts = mutableStateListOf<NovelPrompt>()
    // TRANSLATION_PLUGIN_* maps, key = plugin id
    val pluginTranslation = mutableStateMapOf<String, PluginTranslation>()

    // TRANSLATION_BOOK_LANG_PAIR / TRANSLATION_BOOK_ENABLED_MAP, key = bookUrl.
    // Starts empty: per-book behavior is opt-in.
    val novelTranslation = mutableStateMapOf<String, NovelTranslation>()

    // TRANSLATION_FAVORITE_LANGUAGES / TRANSLATION_RECENT_PAIRS:
    // pinned ISO codes and up to five recent pairs, newest first. Both are user
    // history and start empty.
    val translationFavorites = mutableStateListOf<String>()
    val translationRecentPairs = mutableStateListOf<TranslationPair>()

    fun toggleTranslationFavorite(code: String) {
        if (!translationFavorites.remove(code)) translationFavorites.add(code)
    }

    // Records a complete pair as the newest recent one (dedupe, max 5).
    // Same-language pairs are not usable and never enter the list.
    fun recordTranslationPair(source: String, target: String) {
        if (source.isBlank() || target.isBlank() || source == target) return
        translationRecentPairs.removeAll { it.source == source && it.target == target }
        translationRecentPairs.add(0, TranslationPair(source, target))
        if (translationRecentPairs.size > 5) {
            translationRecentPairs.removeRange(5, translationRecentPairs.size)
        }
    }

    fun removeTranslationPair(pair: TranslationPair) {
        translationRecentPairs.remove(pair)
    }

    // --- Regex cleanup (USER_REGEX_CLEANUP_RULES[_PER_NOVEL]) ---
    val globalRegexRules = mutableStateListOf<RegexRule>()
    val novelRegexRules = mutableStateMapOf<String, List<RegexRule>>()
    // Apply-to is a property of the whole list in the compact editor, not of a
    // single row. The stored rule scope is projected from it when rules run.
    var globalRegexApplyTo by mutableStateOf(RegexRuleScope.Both)
        private set
    val novelRegexApplyTo = mutableStateMapOf<String, RegexRuleScope>()
    private var nextRuleId = 100L
    fun newRuleId(): Long = nextRuleId++

    // --- Network ---
    // SCRAPER_USER_AGENT ("" = default), CLOUDFLARE_BYPASS_ENABLED,
    // MASS_ADD_DELAY_MS, DOWNLOAD_DELAY_MS; WTR-Lab's own plugin settings
    // (wtrlab_mode / wtrlab_chapter_delay — plugin schema, not app prefs)
    var userAgent by mutableStateOf("")
    var cloudflareBypass by mutableStateOf(true)
    var massAddDelayMs by mutableIntStateOf(2000)
    var downloadDelayMs by mutableIntStateOf(2000)
    var wtrMode by mutableStateOf("ai")
    var wtrChapterDelayMs by mutableIntStateOf(0)

    // --- Backup (BACKUP_AUTO_*, EXPORT_DIRECTORY_URI) ---
    var autoBackup by mutableStateOf(false)
    var backupDirectory by mutableStateOf("")
    var backupMaxCount by mutableIntStateOf(5)
    var backupIntervalMinutes by mutableIntStateOf(1440)
    var backupIncludeImages by mutableStateOf(true)
    var backupIncludeSettings by mutableStateOf(true)
    var backupIncludePlugins by mutableStateOf(true)
    var backupIncludeHistoryOnly by mutableStateOf(false)
    var backupLastTimestamp by mutableLongStateOf(0L)
    var autoBackupLastTimestamp by mutableLongStateOf(0L)
    var exportDirectory by mutableStateOf("")

    // Effective regex rules of a novel: global + its own, each list projected
    // with its own list-level apply-to target.
    fun effectiveRules(novelUrl: String?): List<RegexRule> =
        project(globalRegexRules, globalRegexApplyTo) +
            project(
                novelUrl?.let { novelRegexRules[it] }.orEmpty(),
                novelUrl?.let { novelRegexApplyTo[it] ?: globalRegexApplyTo }
                    ?: globalRegexApplyTo,
            )

    fun regexApplyTo(novelUrl: String?): RegexRuleScope =
        novelUrl?.let { novelRegexApplyTo[it] } ?: globalRegexApplyTo

    @JvmName("updateGlobalRegexApplyTo")
    fun setGlobalRegexApplyTo(target: RegexRuleScope) {
        if (globalRegexApplyTo == target) return
        val before = originalProjection(globalRegexRules, globalRegexApplyTo)
        globalRegexApplyTo = target
        _regexRulesRevision.update { it + 1 }
        if (before != originalProjection(globalRegexRules, target)) {
            _regexRulesOriginalRevision.update { it + 1 }
        }
    }

    fun setNovelRegexApplyTo(url: String, target: RegexRuleScope) {
        val previous = novelRegexApplyTo[url] ?: globalRegexApplyTo
        if (previous == target) return
        val rules = novelRegexRules[url].orEmpty()
        val before = originalProjection(rules, previous)
        novelRegexApplyTo[url] = target
        _regexRulesRevision.update { it + 1 }
        if (before != originalProjection(rules, target)) {
            _regexRulesOriginalRevision.update { it + 1 }
        }
    }

    private fun bumpRegexRevisions() {
        _regexRulesRevision.update { it + 1 }
        _regexRulesOriginalRevision.update { it + 1 }
    }

    private fun project(rules: List<RegexRule>, scope: RegexRuleScope): List<RegexRule> =
        rules.map { it.copy(scope = scope) }

    // Bumped on every real rule edit: the reader's translation port recomputes
    // its cleaned map from it.
    private val _regexRulesRevision = MutableStateFlow(0L)
    val regexRulesRevision: StateFlow<Long> = _regexRulesRevision.asStateFlow()

    // Bumped only when the original-applicable projection changes: only then
    // does the open chapter have to be re-parsed.
    private val _regexRulesOriginalRevision = MutableStateFlow(0L)
    val regexRulesOriginalRevision: StateFlow<Long> = _regexRulesOriginalRevision.asStateFlow()

    // Single writers for the public rule lists: direct clear()/addAll() skips
    // the revisions above and leaves the reader showing a stale chapter.
    fun replaceGlobalRules(rules: List<RegexRule>) {
        applyRuleLists(rules, novelRegexRules.toMap())
    }

    fun replaceNovelRules(url: String, rules: List<RegexRule>?) {
        val nextNovel = novelRegexRules.toMap().let { current ->
            if (rules.isNullOrEmpty()) current - url else current + (url to rules)
        }
        if (rules.isNullOrEmpty()) novelRegexApplyTo.remove(url)
        applyRuleLists(globalRegexRules.toList(), nextNovel)
    }

    private fun applyRuleLists(
        global: List<RegexRule>,
        novel: Map<String, List<RegexRule>>,
    ) {
        val nextGlobal = global.toList()
        val nextNovel = novel.toMap()
        val previousGlobal = globalRegexRules.toList()
        val previousNovel = novelRegexRules.toMap()
        if (previousGlobal == nextGlobal && previousNovel == nextNovel) return
        val originalChanged =
            originalProjection(previousGlobal, globalRegexApplyTo) !=
                originalProjection(nextGlobal, globalRegexApplyTo) ||
                novelOriginalProjection(previousNovel) != novelOriginalProjection(nextNovel)
        globalRegexRules.clear()
        globalRegexRules.addAll(nextGlobal)
        novelRegexRules.clear()
        novelRegexRules.putAll(nextNovel)
        _regexRulesRevision.update { it + 1 }
        if (originalChanged) _regexRulesOriginalRevision.update { it + 1 }
    }

    private fun originalProjection(
        rules: List<RegexRule>,
        scope: RegexRuleScope,
    ): List<RegexRule> =
        if (scope == RegexRuleScope.Translation) {
            emptyList()
        } else {
            rules.filter { it.enabled && it.pattern.isNotBlank() }
        }

    private fun novelOriginalProjection(
        map: Map<String, List<RegexRule>>,
    ): Map<String, List<RegexRule>> = map.mapValues { (url, rules) ->
        originalProjection(rules, novelRegexApplyTo[url] ?: globalRegexApplyTo)
    }

    private fun deriveApplyTo(rules: List<RegexRule>): RegexRuleScope {
        val scopes = rules.map { it.scope }.distinct()
        return scopes.singleOrNull() ?: RegexRuleScope.Both
    }

    // Persisted fields only. Used by MiruApp's snapshotFlow writer and by tests;
    // Compose-only state (selection sheets, scroll, ...) never enters the store.
    fun persistedSnapshot(): PersistedSettings = PersistedSettings(
        appLanguage = appLanguage,
        downloadKeepMangaCache = downloadKeepMangaCache,
        downloadPagesAfterView = downloadPagesAfterView,
        videoVariantByBook = videoVariantByBook.toMap(),
        videoAutoNext = videoAutoNext,
        videoSeekStepMs = videoSeekStepMs,
        videoControllerAutoHideMs = videoControllerAutoHideMs,
        videoGestureDragThreshold = videoGestureDragThreshold,
        videoGestureSwappedSides = videoGestureSwappedSides,
        videoGestureDoubleTap = videoGestureDoubleTap,
        videoGestureVerticalSwipe = videoGestureVerticalSwipe,
        videoGestureLongPressSpeed = videoGestureLongPressSpeed,
        gridColumns = gridColumns,
        listLayout = listLayout,
        cardStyle = cardStyle,
        sourceStrip = sourceStrip,
        textScale = textScale,
        uiScale = uiScale,
        newestFirst = newestFirst,
        pinnedSources = pinnedSources.toList(),
        filterPresets = filterPresets.toMap(),
        searchHistory = searchHistory.toMap(),
        appUpdateCheck = appUpdateCheck,
        libraryAutoUpdate = libraryAutoUpdate,
        libraryLastRunAt = libraryLastRunAt,
        libraryIntervalHours = libraryIntervalHours,
        updateWifiOnly = updateWifiOnly,
        updateWhileCharging = updateWhileCharging,
        translationGlobalMode = translationGlobalMode,
        globalSource = globalSource,
        globalTarget = globalTarget,
        provider = provider,
        googlePaKeys = googlePaKeys,
        geminiKeys = geminiKeys,
        geminiModel = geminiModel,
        openAiKeys = openAiKeys,
        openAiModel = openAiModel,
        openAiBaseUrl = openAiBaseUrl,
        openAiCompatibleKeys = openAiCompatibleKeys,
        openAiCompatibleModel = openAiCompatibleModel,
        openAiCompatibleBaseUrl = openAiCompatibleBaseUrl,
        anthropicKeys = anthropicKeys,
        anthropicModel = anthropicModel,
        deeplKeys = deeplKeys,
        yandexCloudKeys = yandexCloudKeys,
        yandexCloudFolderId = yandexCloudFolderId,
        activePrompt = activePrompt,
        promptPresets = promptPresets.toList(),
        promptEnglishLocale = promptEnglishLocale,
        maxOutputTokens = maxOutputTokens,
        timeoutSeconds = timeoutSeconds,
        maxAttempts = maxAttempts,
        maxCharsPerRequest = maxCharsPerRequest,
        chunkConcurrency = chunkConcurrency,
        useJsonSchema = useJsonSchema,
        parallelEnabled = parallelEnabled,
        parallelOrder = parallelOrder,
        novelPrompts = novelPrompts.toList(),
        pluginTranslation = pluginTranslation.toMap(),
        novelTranslation = novelTranslation.toMap(),
        translationFavorites = translationFavorites.toList(),
        translationRecentPairs = translationRecentPairs.toList(),
        globalRegexRules = globalRegexRules.toList(),
        novelRegexRules = novelRegexRules.toMap(),
        globalRegexApplyTo = globalRegexApplyTo,
        novelRegexApplyTo = novelRegexApplyTo.toMap(),
        nextRuleId = nextRuleId,
        userAgent = userAgent,
        cloudflareBypass = cloudflareBypass,
        massAddDelayMs = massAddDelayMs,
        downloadDelayMs = downloadDelayMs,
        wtrMode = wtrMode,
        wtrChapterDelayMs = wtrChapterDelayMs,
        autoBackup = autoBackup,
        backupDirectory = backupDirectory,
        backupMaxCount = backupMaxCount,
        backupIntervalMinutes = backupIntervalMinutes,
        backupIncludeImages = backupIncludeImages,
        backupIncludeSettings = backupIncludeSettings,
        backupIncludePlugins = backupIncludePlugins,
        backupIncludeHistoryOnly = backupIncludeHistoryOnly,
        backupLastTimestamp = backupLastTimestamp,
        autoBackupLastTimestamp = autoBackupLastTimestamp,
        exportDirectory = exportDirectory,
    )

    // Startup path: call before any screen reads settings. Values missing
    // from the store keep the current defaults.
    fun applyPersisted(settings: PersistedSettings) {
        appLanguage = settings.appLanguage
        downloadKeepMangaCache = settings.downloadKeepMangaCache
        downloadPagesAfterView = settings.downloadPagesAfterView
        videoVariantByBook.clear()
        videoVariantByBook.putAll(settings.videoVariantByBook)
        videoAutoNext = settings.videoAutoNext
        videoSeekStepMs = settings.videoSeekStepMs
        videoControllerAutoHideMs = settings.videoControllerAutoHideMs
        videoGestureDragThreshold = settings.videoGestureDragThreshold
        videoGestureSwappedSides = settings.videoGestureSwappedSides
        videoGestureDoubleTap = settings.videoGestureDoubleTap
        videoGestureVerticalSwipe = settings.videoGestureVerticalSwipe
        videoGestureLongPressSpeed = settings.videoGestureLongPressSpeed
        gridColumns = settings.gridColumns
        listLayout = settings.listLayout
        cardStyle = settings.cardStyle
        sourceStrip = settings.sourceStrip
        textScale = settings.textScale.coerceIn(50, 150)
        uiScale = settings.uiScale.coerceIn(50, 150)
        newestFirst = settings.newestFirst
        pinnedSources = settings.pinnedSources.toSet()
        filterPresets.clear()
        filterPresets.putAll(settings.filterPresets)
        searchHistory.clear()
        searchHistory.putAll(settings.searchHistory)
        appUpdateCheck = settings.appUpdateCheck
        libraryAutoUpdate = settings.libraryAutoUpdate
        libraryLastRunAt = settings.libraryLastRunAt
        libraryIntervalHours = settings.libraryIntervalHours
        updateWifiOnly = settings.updateWifiOnly
        updateWhileCharging = settings.updateWhileCharging
        translationGlobalMode = settings.translationGlobalMode
        globalSource = settings.globalSource
        globalTarget = settings.globalTarget
        provider = settings.provider
        googlePaKeys = settings.googlePaKeys
        geminiKeys = settings.geminiKeys
        geminiModel = settings.geminiModel
        openAiKeys = settings.openAiKeys
        openAiModel = settings.openAiModel
        openAiBaseUrl = settings.openAiBaseUrl
        openAiCompatibleKeys = settings.openAiCompatibleKeys
        openAiCompatibleModel = settings.openAiCompatibleModel
        openAiCompatibleBaseUrl = settings.openAiCompatibleBaseUrl
        anthropicKeys = settings.anthropicKeys
        anthropicModel = settings.anthropicModel
        deeplKeys = settings.deeplKeys
        yandexCloudKeys = settings.yandexCloudKeys
        yandexCloudFolderId = settings.yandexCloudFolderId
        activePrompt = settings.activePrompt
        promptPresets.clear()
        promptPresets.addAll(settings.promptPresets)
        promptEnglishLocale = settings.promptEnglishLocale
        maxOutputTokens = settings.maxOutputTokens
        timeoutSeconds = settings.timeoutSeconds
        maxAttempts = settings.maxAttempts
        maxCharsPerRequest = settings.maxCharsPerRequest
        chunkConcurrency = settings.chunkConcurrency
        useJsonSchema = settings.useJsonSchema
        parallelEnabled = settings.parallelEnabled
        parallelOrder = settings.parallelOrder
        novelPrompts.clear()
        novelPrompts.addAll(settings.novelPrompts)
        pluginTranslation.clear()
        pluginTranslation.putAll(settings.pluginTranslation)
        novelTranslation.clear()
        novelTranslation.putAll(settings.novelTranslation)
        translationFavorites.clear()
        translationFavorites.addAll(settings.translationFavorites)
        translationRecentPairs.clear()
        translationRecentPairs.addAll(settings.translationRecentPairs)
        // A restore can land while the reader is open: one write updates the
        // port and — only when the original changes — the open chapter.
        val previousGlobalApplyTo = globalRegexApplyTo
        val previousNovelApplyTo = novelRegexApplyTo.toMap()
        globalRegexApplyTo = settings.globalRegexApplyTo
            ?: deriveApplyTo(settings.globalRegexRules)
        novelRegexApplyTo.clear()
        novelRegexApplyTo.putAll(settings.novelRegexApplyTo)
        settings.novelRegexRules.forEach { (url, rules) ->
            if (url !in novelRegexApplyTo) novelRegexApplyTo[url] = deriveApplyTo(rules)
        }
        applyRuleLists(settings.globalRegexRules, settings.novelRegexRules)
        if (previousGlobalApplyTo != globalRegexApplyTo ||
            previousNovelApplyTo != novelRegexApplyTo.toMap()
        ) {
            bumpRegexRevisions()
        }
        nextRuleId = settings.nextRuleId
        userAgent = settings.userAgent
        cloudflareBypass = settings.cloudflareBypass
        massAddDelayMs = settings.massAddDelayMs
        downloadDelayMs = settings.downloadDelayMs
        wtrMode = settings.wtrMode
        wtrChapterDelayMs = settings.wtrChapterDelayMs
        autoBackup = settings.autoBackup
        backupDirectory = settings.backupDirectory
        backupMaxCount = settings.backupMaxCount
        backupIntervalMinutes = settings.backupIntervalMinutes
        backupIncludeImages = settings.backupIncludeImages
        backupIncludeSettings = settings.backupIncludeSettings
        backupIncludePlugins = settings.backupIncludePlugins
        backupIncludeHistoryOnly = settings.backupIncludeHistoryOnly
        backupLastTimestamp = settings.backupLastTimestamp
        autoBackupLastTimestamp = settings.autoBackupLastTimestamp
        exportDirectory = settings.exportDirectory
    }
}

// ISO 639-1 languages offered by the pickers; names come from the device
// locale, so they are localized for free.
object SettingsLanguages {
    // Only locales that actually have string resources. Offering a language
    // that silently falls back to English would be a dead choice.
    // "in" is Android's resource qualifier for Indonesian (`values-in`):
    // Locale normalizes it to "id", and the platform maps that back to the folder.
    val app = listOf("en", "ru", "es", "pt", "in", "vi", "fr", "de", "it", "tr")
    val translation = listOf(
        "en", "zh", "ja", "ko", "ru", "uk", "es", "fr", "de", "it", "pt", "tr",
        "ar", "vi", "th", "id", "ms", "pl", "hi", "bn", "fil", "nl", "cs", "el",
    )
}
