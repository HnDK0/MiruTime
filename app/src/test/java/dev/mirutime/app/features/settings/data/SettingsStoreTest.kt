package dev.mirutime.app.features.settings.data

import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun emptyStoreUsesDefaults() {
        val loaded = SettingsStore(InMemoryKeyValueStore()).load()

        assertEquals(PersistedSettings(), loaded)
        assertEquals("en", loaded.globalSource)
        assertEquals("GOOGLE_PA", loaded.provider)
        assertEquals(defaultPromptPresets, loaded.promptPresets)
        assertEquals(180, loaded.timeoutSeconds)
        assertEquals(1440, loaded.backupIntervalMinutes)
        assertTrue(loaded.novelPrompts.isEmpty())
        assertTrue(loaded.pluginTranslation.isEmpty())
        assertTrue(loaded.novelTranslation.isEmpty())
        assertTrue(loaded.translationFavorites.isEmpty())
        assertTrue(loaded.translationRecentPairs.isEmpty())
        assertTrue(loaded.globalRegexRules.isEmpty())
        assertTrue(loaded.novelRegexRules.isEmpty())
        assertEquals(defaultPinnedSources, loaded.pinnedSources)
        assertTrue(loaded.filterPresets.isEmpty())
        assertTrue(loaded.searchHistory.isEmpty())
        assertEquals(3_500, loaded.videoControllerAutoHideMs)
        assertEquals(0.06f, loaded.videoGestureDragThreshold, 0.0001f)
        assertTrue(loaded.novelPrompts.none { it.bookUrl.startsWith("fake://") })
        assertTrue(loaded.novelTranslation.keys.none { it.startsWith("fake://") })
        assertFalse(loaded.toString().contains("fake://"))
    }

    @Test
    fun missingKeysKeepDefaultsAndPresentKeysWin() {
        val values = InMemoryKeyValueStore()
        values.put(mapOf("TRANSLATION_PROVIDER" to "GEMINI", "BACKUP_MAX_COUNT" to 2))

        val loaded = SettingsStore(values).load()

        assertEquals("GEMINI", loaded.provider)
        assertEquals(2, loaded.backupMaxCount)
        assertEquals(PersistedSettings().geminiModel, loaded.geminiModel)
        assertEquals(PersistedSettings().backupIntervalMinutes, loaded.backupIntervalMinutes)
        assertTrue(loaded.backupIncludeImages)
    }

    @Test
    fun legacyUiScaleBecomesTextScaleOnFirstLoad() {
        val values = InMemoryKeyValueStore()
        values.put(mapOf("UI_SCALE" to 150))

        val loaded = SettingsStore(values).load()

        assertEquals(150, loaded.textScale)
        assertEquals(100, loaded.uiScale)
    }

    @Test
    fun roundTripSurvivesANewStoreInstance() {
        val values = InMemoryKeyValueStore()
        val settings = PersistedSettings(
            appLanguage = "ru",
            downloadKeepMangaCache = false,
            downloadPagesAfterView = true,
            gridColumns = 5,
            listLayout = "list",
            cardStyle = CardStyle.ProgressAbove,
            sourceStrip = "top",
            newestFirst = false,
            pinnedSources = listOf("local-books", "wtr-lab"),
            filterPresets = mapOf(
                "wtr-lab" to listOf(
                    FilterPreset(
                        name = "Fantasy; with: punctuation",
                        filters = CatalogFilters(
                            query = "dragon",
                            sort = CatalogSort.Latest,
                            descending = false,
                            genres = setOf("Fantasy", "Action"),
                            ongoingOnly = true,
                        ),
                    ),
                ),
                "local-books" to listOf(FilterPreset("All", CatalogFilters())),
            ),
            searchHistory = mapOf(
                "local-books" to listOf("Dragon", "cultivation"),
                "__global__" to listOf("Solo Leveling"),
            ),
            appUpdateCheck = false,
            libraryAutoUpdate = false,
            libraryIntervalHours = 72,
            updateWifiOnly = true,
            updateWhileCharging = false,
            translationGlobalMode = true,
            globalSource = "ja",
            globalTarget = "ru",
            provider = "OPENAI_COMPATIBLE",
            googlePaKeys = "google-key",
            geminiKeys = "gemini-key",
            geminiModel = "gemini-2.0-flash-exp",
            openAiKeys = "openai-key",
            openAiModel = "gpt-4o",
            openAiBaseUrl = "https://proxy.example",
            openAiCompatibleKeys = "compatible-key",
            openAiCompatibleModel = "local-model",
            openAiCompatibleBaseUrl = "http://localhost:8080/v1",
            anthropicKeys = "anthropic-key",
            anthropicModel = "claude-test",
            deeplKeys = "deepl-key",
            yandexCloudKeys = "yandex-key",
            yandexCloudFolderId = "folder-42",
            activePrompt = "Keep names: {source_language} -> {target_language}",
            promptPresets = listOf(
                "Colon: preset" to "line one\nline two: still one prompt",
                "Empty" to "",
            ),
            promptEnglishLocale = false,
            maxOutputTokens = 4096,
            timeoutSeconds = 30,
            maxAttempts = 2,
            maxCharsPerRequest = 12000,
            chunkConcurrency = 3,
            useJsonSchema = true,
            parallelEnabled = true,
            parallelOrder = "ORIGINAL_FIRST",
            novelPrompts = listOf(
                NovelPrompt(
                    bookUrl = "https://example.com/book;with:punctuation",
                    title = "Line\nbreak",
                    prompt = "Keep \"Archive\"\nand: punctuation; intact",
                    append = true,
                ),
            ),
            pluginTranslation = mapOf(
                "plugin:one" to TranslationConfig(
                    enabled = true,
                    source = "ja",
                    target = "ru",
                    provider = "GEMINI",
                    full = true,
                    prompt = "a;b:c",
                    hideLibrary = true,
                    hideHistory = false,
                    hideCatalog = true,
                    hideSearch = false,
                    chapterTitles = false,
                ),
            ),
            novelTranslation = mapOf(
                "https://example.com/novel" to TranslationConfig(
                    enabled = true,
                    source = "en",
                    target = "de",
                ),
                "https://example.com/off" to TranslationConfig(enabled = false, full = true),
            ),
            translationFavorites = listOf("en", "ru", "ja"),
            translationRecentPairs = listOf(
                TranslationPair("en", "ru"),
                TranslationPair("zh", "en"),
            ),
            globalRegexRules = listOf(
                RegexRule(
                    id = 7,
                    pattern = "(?i)translated by .*",
                    replacement = "",
                    description = "Credits; with: punctuation",
                    enabled = true,
                    wholeWords = false,
                ),
            ),
            novelRegexRules = mapOf(
                "https://example.com/novel" to listOf(
                    RegexRule(8, "\\s{2,}", " ", "Collapse", enabled = false, wholeWords = true),
                ),
            ),
            globalRegexApplyTo = RegexRuleScope.Translation,
            novelRegexApplyTo = mapOf("https://example.com/novel" to RegexRuleScope.Original),
            nextRuleId = 200L,
            userAgent = "MiruTest/1.0",
            cloudflareBypass = false,
            massAddDelayMs = 1500,
            downloadDelayMs = 2500,
            wtrMode = "manual",
            wtrChapterDelayMs = 750,
            autoBackup = true,
            backupDirectory = "content://tree/backups",
            backupMaxCount = 9,
            backupIntervalMinutes = 60,
            backupIncludeImages = true,
            backupIncludeSettings = false,
            backupIncludePlugins = false,
            backupIncludeHistoryOnly = true,
            backupLastTimestamp = 1_700_000_000_000L,
            autoBackupLastTimestamp = 1_700_000_100_000L,
            exportDirectory = "content://tree/exports",
        )

        SettingsStore(values).save(settings)
        val loaded = SettingsStore(values).load()

        assertEquals(settings, loaded)
        assertEquals(CardStyle.ProgressAbove, loaded.cardStyle)
        assertEquals("https://example.com/book;with:punctuation", loaded.novelPrompts.single().bookUrl)
        assertEquals("Keep \"Archive\"\nand: punctuation; intact", loaded.novelPrompts.single().prompt)
        assertEquals("a;b:c", loaded.pluginTranslation.getValue("plugin:one").prompt)
        assertFalse(loaded.pluginTranslation.getValue("plugin:one").chapterTitles)
        assertEquals(listOf("local-books", "wtr-lab"), loaded.pinnedSources)
        assertEquals("Fantasy; with: punctuation", loaded.filterPresets.getValue("wtr-lab").single().name)
        assertEquals(setOf("Fantasy", "Action"), loaded.filterPresets.getValue("wtr-lab").single().filters.genres)
        assertEquals(listOf("Dragon", "cultivation"), loaded.searchHistory["local-books"])
        assertEquals(listOf("Solo Leveling"), loaded.searchHistory["__global__"])
        assertEquals(7L, loaded.globalRegexRules.single().id)
        assertEquals(RegexRuleScope.Translation, loaded.globalRegexApplyTo)
        assertEquals(
            RegexRuleScope.Original,
            loaded.novelRegexApplyTo.getValue("https://example.com/novel"),
        )
        assertEquals(200L, loaded.nextRuleId)
        assertFalse(loaded.toString().contains("fake://"))
    }

    @Test
    fun videoPlayerSettingsRoundTrip() {
        val values = InMemoryKeyValueStore()
        val settings = PersistedSettings(
            videoVariantByBook = mapOf(
                "https://example.com/show?a=1" to "1080p",
                "https://example.com/other" to "720p",
            ),
            videoAutoNext = false,
            videoSeekStepMs = 5_000,
            videoControllerAutoHideMs = 15_000,
            videoGestureDragThreshold = 0.12f,
            videoGestureSwappedSides = true,
            videoGestureDoubleTap = false,
            videoGestureVerticalSwipe = false,
            videoGestureLongPressSpeed = false,
        )

        SettingsStore(values).save(settings)
        val loaded = SettingsStore(values).load()

        assertEquals(settings, loaded)
        assertEquals("1080p", loaded.videoVariantByBook["https://example.com/show?a=1"])
        assertEquals(5_000, loaded.videoSeekStepMs)
        assertEquals(15_000, loaded.videoControllerAutoHideMs)
        assertEquals(0.12f, loaded.videoGestureDragThreshold, 0.0001f)
    }

    @Test
    fun emptyCollectionsRoundTripAsEmptyNotFixtures() {
        val values = InMemoryKeyValueStore()
        val settings = PersistedSettings(
            novelPrompts = emptyList(),
            pluginTranslation = emptyMap(),
            novelTranslation = emptyMap(),
            translationFavorites = emptyList(),
            translationRecentPairs = emptyList(),
            globalRegexRules = emptyList(),
            novelRegexRules = emptyMap(),
            pinnedSources = emptyList(),
            filterPresets = emptyMap(),
        )

        SettingsStore(values).save(settings)
        val loaded = SettingsStore(values).load()

        assertEquals(settings, loaded)
        assertTrue(loaded.novelPrompts.isEmpty())
        assertTrue(loaded.pluginTranslation.isEmpty())
        assertTrue(loaded.novelTranslation.isEmpty())
        assertTrue(loaded.translationFavorites.isEmpty())
        assertTrue(loaded.translationRecentPairs.isEmpty())
        assertTrue(loaded.globalRegexRules.isEmpty())
        assertTrue(loaded.novelRegexRules.isEmpty())
        assertTrue(loaded.pinnedSources.isEmpty())
        assertTrue(loaded.filterPresets.isEmpty())
    }

    @Test
    fun missingPinnedPrefSeedsLocalsButExplicitEmptyStaysEmpty() {
        val missing = SettingsStore(InMemoryKeyValueStore()).load()
        assertEquals(listOf("local-books", "local-manga"), missing.pinnedSources)

        val explicit = InMemoryKeyValueStore()
        SettingsStore(explicit).save(PersistedSettings(pinnedSources = emptyList()))
        assertTrue(SettingsStore(explicit).load().pinnedSources.isEmpty())
    }

    @Test
    fun pinnedSourcesToggleRevertsToTheLocalDefaults() {
        val previous = AppSettings.persistedSnapshot()
        val values = InMemoryKeyValueStore()
        try {
            AppSettings.applyPersisted(PersistedSettings())
            assertEquals(setOf("local-books", "local-manga"), AppSettings.pinnedSources)

            AppSettings.togglePinnedSource("local-books")
            AppSettings.togglePinnedSource("wtr-lab")
            assertEquals(setOf("local-manga", "wtr-lab"), AppSettings.pinnedSources)

            assertTrue(SettingsStore(values).save(AppSettings.persistedSnapshot()))
            assertEquals(
                listOf("local-manga", "wtr-lab"),
                SettingsStore(values).load().pinnedSources,
            )
        } finally {
            AppSettings.applyPersisted(previous)
        }
    }

    @Test
    fun identicalSaveSkipsTheSecondWrite() {
        val values = InMemoryKeyValueStore()
        val store = SettingsStore(values)
        val settings = PersistedSettings(pinnedSources = emptyList())

        assertTrue(store.save(settings))
        assertTrue(store.save(settings))
        assertEquals(1, values.writes)
    }

    @Test
    fun failedSaveReportsFailureAndKeepsThePreviousSnapshot() {
        val values = InMemoryKeyValueStore(failWrites = true)
        val store = SettingsStore(values)

        assertFalse(store.save(PersistedSettings(pinnedSources = emptyList())))
        assertEquals(0, values.writes)
    }

    @Test
    fun apiKeysNeverReachLogsOrToString() {
        val values = InMemoryKeyValueStore()
        val secrets = listOf(
            "google-secret",
            "gemini-secret",
            "openai-secret",
            "compatible-secret",
            "anthropic-secret",
            "deepl-secret",
            "yandex-secret",
        )
        val settings = PersistedSettings(
            googlePaKeys = secrets[0],
            geminiKeys = secrets[1],
            openAiKeys = secrets[2],
            openAiCompatibleKeys = secrets[3],
            anthropicKeys = secrets[4],
            deeplKeys = secrets[5],
            yandexCloudKeys = secrets[6],
        )

        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(captured, true))
        System.setErr(PrintStream(captured, true))
        try {
            SettingsStore(values).save(settings)
            SettingsStore(values).load()
            println(settings.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        val output = captured.toString()
        secrets.forEach { secret -> assertFalse("leaked $secret in: $output", output.contains(secret)) }
        assertFalse(settings.toString().contains("secret"))
    }

    @Test
    fun loadAppliesValuesToAppSettings() {
        val previous = AppSettings.persistedSnapshot()
        val values = InMemoryKeyValueStore()
        val saved = PersistedSettings(
            provider = "ANTHROPIC",
            anthropicKeys = "anthropic-key",
            globalTarget = "ru",
            translationGlobalMode = true,
            autoBackup = true,
            backupDirectory = "content://tree/backups",
            backupIncludeSettings = true,
            backupIncludeHistoryOnly = true,
            exportDirectory = "content://tree/exports",
            activePrompt = "custom prompt",
            promptPresets = listOf("Only" to "Only prompt"),
            chunkConcurrency = 4,
            appLanguage = "de",
            gridColumns = 4,
            cardStyle = CardStyle.Horizontal,
            novelPrompts = listOf(NovelPrompt("url", "Title", "Prompt", append = false)),
            novelTranslation = mapOf("url" to TranslationConfig(enabled = true)),
            translationFavorites = listOf("de"),
            translationRecentPairs = listOf(TranslationPair("en", "de")),
            globalRegexRules = listOf(
                RegexRule(11, "a", "b", "Rule", enabled = true, wholeWords = true),
            ),
            novelRegexRules = mapOf(
                "url" to listOf(RegexRule(12, "c", "d", "Novel rule", enabled = false, wholeWords = false)),
            ),
            globalRegexApplyTo = RegexRuleScope.Both,
            novelRegexApplyTo = mapOf("url" to RegexRuleScope.Both),
            nextRuleId = 300L,
            userAgent = "agent",
            wtrMode = "manual",
            pinnedSources = listOf("local-manga", "alpha"),
            filterPresets = mapOf("alpha" to listOf(FilterPreset("Recent", CatalogFilters()))),
            searchHistory = mapOf(
                "alpha" to listOf("Dragon"),
                "__global__" to listOf("Solo"),
            ),
        )
        try {
            SettingsStore(values).save(saved)
            AppSettings.applyPersisted(SettingsStore(values).load())

            assertEquals("ANTHROPIC", AppSettings.provider)
            assertEquals("anthropic-key", AppSettings.anthropicKeys)
            assertEquals("ru", AppSettings.globalTarget)
            assertEquals(true, AppSettings.translationGlobalMode)
            assertEquals(true, AppSettings.autoBackup)
            assertEquals("content://tree/backups", AppSettings.backupDirectory)
            assertEquals(true, AppSettings.backupIncludeHistoryOnly)
            assertEquals("content://tree/exports", AppSettings.exportDirectory)
            assertEquals("custom prompt", AppSettings.activePrompt)
            assertEquals(listOf("Only" to "Only prompt"), AppSettings.promptPresets.toList())
            assertEquals(4, AppSettings.chunkConcurrency)
            assertEquals("de", AppSettings.appLanguage)
            assertEquals(4, AppSettings.gridColumns)
            assertEquals(CardStyle.Horizontal, AppSettings.cardStyle)
            assertEquals(listOf("de"), AppSettings.translationFavorites.toList())
            assertEquals(listOf(TranslationPair("en", "de")), AppSettings.translationRecentPairs.toList())
            assertEquals(11L, AppSettings.globalRegexRules.single().id)
            assertEquals(12L, AppSettings.novelRegexRules.getValue("url").single().id)
            assertEquals("agent", AppSettings.userAgent)
            assertEquals("manual", AppSettings.wtrMode)
            assertEquals(setOf("local-manga", "alpha"), AppSettings.pinnedSources)
            assertEquals(listOf("Recent"), AppSettings.filterPresets.getValue("alpha").map { it.name })
            assertEquals(listOf("Dragon"), AppSettings.searchHistoryFor("alpha"))
            assertEquals(listOf("Solo"), AppSettings.searchHistoryFor("__global__"))
            assertEquals(saved, AppSettings.persistedSnapshot())
            assertEquals(300L, AppSettings.newRuleId())
        } finally {
            AppSettings.applyPersisted(previous)
        }
    }

    @Test
    fun `ui scale is clamped on restore`() {
        val previous = AppSettings.persistedSnapshot()
        try {
            AppSettings.applyPersisted(PersistedSettings(uiScale = 500))
            assertEquals(150, AppSettings.uiScale)
            AppSettings.applyPersisted(PersistedSettings(uiScale = 10))
            assertEquals(50, AppSettings.uiScale)
        } finally {
            AppSettings.applyPersisted(previous)
        }
    }

    @Test
    fun `text and ui scales are independent and clamped`() {
        val previous = AppSettings.persistedSnapshot()
        try {
            AppSettings.applyPersisted(PersistedSettings(textScale = 150, uiScale = 100))
            assertEquals(150, AppSettings.textScale)
            assertEquals(100, AppSettings.uiScale)
            AppSettings.applyPersisted(PersistedSettings(textScale = 500, uiScale = 10))
            assertEquals(150, AppSettings.textScale)
            assertEquals(50, AppSettings.uiScale)
        } finally {
            AppSettings.applyPersisted(previous)
        }
    }

    @Test
    fun `rule scopes survive a store round trip`() {
        val rules = listOf(
            RegexRule(1, "a", "", "", true, false, RegexRuleScope.Original),
            RegexRule(2, "b", "", "", true, false, RegexRuleScope.Translation),
            RegexRule(3, "c", "", "", true, false, RegexRuleScope.Both),
        )
        val store = SettingsStore(InMemoryKeyValueStore())
        store.save(PersistedSettings(globalRegexRules = rules))

        val loaded = store.load()

        assertEquals(rules, loaded.globalRegexRules)
        assertEquals(rules.map { it.scope }, loaded.globalRegexRules.map { it.scope })
    }

    @Test
    fun `legacy rule records without a scope decode as both`() {
        // Records written before scopes existed have six fields, no seventh.
        val legacy = SettingsStore.encodeRecords(
            listOf(listOf("1", "a", "", "", "true", "false")),
        )

        val rules = SettingsStore.decodeRules(legacy)

        assertEquals(RegexRuleScope.Both, rules.single().scope)
    }

    private class InMemoryKeyValueStore(
        private val failWrites: Boolean = false,
    ) : KeyValueStore {
        private val values = linkedMapOf<String, Any>()
        var writes = 0
            private set

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun getString(key: String): String? = values[key] as? String

        override fun getInt(key: String): Int? = values[key] as? Int

        override fun getLong(key: String): Long? = values[key] as? Long

        override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

        override fun put(values: Map<String, Any>): Boolean {
            if (failWrites) return false
            writes++
            this.values.putAll(values)
            return true
        }
    }
}
