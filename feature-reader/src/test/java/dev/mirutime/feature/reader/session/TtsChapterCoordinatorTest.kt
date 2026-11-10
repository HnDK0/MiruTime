package dev.mirutime.feature.reader.session

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.feature.reader.ReaderConfig
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.SavedReading
import dev.mirutime.feature.reader.api.ReaderBookMetadata
import dev.mirutime.feature.reader.api.ReaderChapterTranslation
import dev.mirutime.feature.reader.api.ReaderContentSource
import dev.mirutime.feature.reader.api.ReaderPositionStore
import dev.mirutime.feature.reader.api.ReaderSettingsPort
import dev.mirutime.feature.reader.api.ReaderTranslationMode
import dev.mirutime.feature.reader.api.ReaderTranslationPort
import dev.mirutime.feature.tts.TtsChapterDirection
import dev.mirutime.feature.tts.TtsHandoffSource
import dev.mirutime.feature.tts.TtsLanguage
import dev.mirutime.feature.tts.TtsSession
import dev.mirutime.feature.tts.TtsState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChapterCoordinatorTest {
    private val chapters = (1..3).map { ReaderChapter("book#chapter=$it", it, "Chapter $it", false) }
    private val docs = chapters.associate { chapter ->
        chapter.index to ReaderDocument(
            bookUrl = "book",
            chapterUrl = chapter.chapterUrl,
            chapterIndex = chapter.index,
            title = chapter.title,
            blocks = listOf(
                ReaderBlock(BlockId(chapter.chapterUrl, 0), BlockKind.Paragraph, "text"),
            ),
        )
    }

    private class Repo(
        private val chapters: List<ReaderChapter>,
        private val docs: Map<Int, ReaderDocument>,
    ) : ReaderContentSource {
        override suspend fun book(bookUrl: String) = ReaderBook(bookUrl, "Book", chapters)
        override suspend fun document(bookUrl: String, chapterIndex: Int) = docs[chapterIndex]
        override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    private class Cfg : ReaderConfigStore {
        private val _config = MutableStateFlow(ReaderConfig())
        override val config: StateFlow<ReaderConfig> = _config.asStateFlow()
        override fun setMode(mode: ReaderMode) = Unit
        override fun setStyle(style: ReaderStyle) = Unit
        override fun setMangaStyle(style: MangaReaderStyle) = Unit
    }

    private class Positions : ReaderPositionStore {
        private val values = mutableMapOf<String, SavedReading>()
        override suspend fun load(bookUrl: String): SavedReading? = values[bookUrl]
        override suspend fun save(bookUrl: String, saved: SavedReading) {
            values[bookUrl] = saved
        }
        override suspend fun clear(bookUrl: String) {
            values.remove(bookUrl)
        }
    }

    private class FakeTranslation(
        var activeModeValue: ReaderTranslationMode? = null,
        var configKeyValue: String? = null,
    ) : ReaderTranslationPort {
        override val state = MutableStateFlow<Map<String, ReaderChapterTranslation>>(emptyMap())
        override val metadata = MutableStateFlow<Map<String, ReaderBookMetadata>>(emptyMap())
        override fun activeMode(bookUrl: String): ReaderTranslationMode? = activeModeValue
        override fun chapterConfigKey(
            bookUrl: String,
            document: ReaderDocument,
        ): String? = configKeyValue
        override fun providerLabel(provider: String): String = ""
        override fun ensureChapter(bookUrl: String, chapterIndex: Int, document: ReaderDocument) = Unit
        override fun forget(chapterUrls: Collection<String>) = Unit
        override fun metadataConfigKey(bookUrl: String): String? = null
        override suspend fun hydrateMetadata(bookUrl: String) = Unit
        override fun submitChapterTitles(
            bookUrl: String,
            title: String,
            chapters: List<Pair<Int, String>>,
        ) = Unit
    }

    private class FakeTts : TtsHandoffSource {
        override val state = MutableStateFlow(TtsState())
        override var sessionLanguage: TtsLanguage? = null
        val switched = mutableListOf<Triple<Int, TtsLanguage, Map<Int, String>>>()
        private val requests = MutableSharedFlow<TtsChapterDirection>(extraBufferCapacity = 4)
        override val chapterRequests: SharedFlow<TtsChapterDirection> = requests.asSharedFlow()
        override var chapterEndedNaturally: Boolean = true
        override var lastStoppedSession: TtsSession? = null
        val played = mutableListOf<String>()
        val languages = mutableListOf<TtsLanguage>()
        var stops = 0
        var resumes = 0
        var accept = true

        override fun playHandoff(
            document: ReaderDocument,
            translations: Map<Int, String>,
            language: TtsLanguage,
        ): Boolean {
            if (!accept) return false
            played += document.chapterUrl
            languages += language
            return true
        }

        override fun switchLanguage(
            document: ReaderDocument,
            translations: Map<Int, String>,
            blockIndex: Int,
            language: TtsLanguage,
        ) {
            sessionLanguage = language
            switched += Triple(blockIndex, language, translations)
        }

        override fun resume() {
            resumes += 1
        }

        override fun stop() {
            stops += 1
        }

        fun request(direction: TtsChapterDirection) {
            requests.tryEmit(direction)
        }
    }

    private fun coordinator(
        tts: FakeTts,
        store: ReaderSessionStore,
        scope: kotlinx.coroutines.CoroutineScope,
        parallel: Boolean = false,
        order: String = "TRANSLATION_FIRST",
        translation: FakeTranslation = FakeTranslation(),
    ) = TtsChapterCoordinator(
        tts = tts,
        session = store,
        translation = translation,
        settings = object : ReaderSettingsPort {
            override val parallelEnabled = parallel
            override val parallelOrder = order
        },
        scope = scope,
        startPlayback = { _, _, _, _, _ -> },
        stopPlayback = {},
    )

    private fun playing(chapter: Int) = TtsState(
        status = dev.mirutime.feature.tts.TtsStatus.Playing,
        blockId = BlockId("book#chapter=$chapter", 0),
    )

    @Test
    fun `next loads the following chapter and starts it`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply { state.value = playing(1) }
        val coordinator = coordinator(tts, store, this).also { it.start() }
        runCurrent()

        tts.request(TtsChapterDirection.Next)
        advanceUntilIdle()

        assertEquals(2, store.state.value.activeChapterIndex)
        assertEquals(listOf("book#chapter=2"), tts.played)
        assertFalse(coordinator.handoffInProgress.value)
        coordinator.close()
    }

    @Test
    fun `next at the last chapter stops instead of moving`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 3, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply { state.value = playing(3) }
        val coordinator = coordinator(tts, store, this).also { it.start() }
        runCurrent()

        tts.request(TtsChapterDirection.Next)
        advanceUntilIdle()

        assertEquals(3, store.state.value.activeChapterIndex)
        assertEquals(1, tts.stops)
        coordinator.close()
    }

    @Test
    fun `a rejected hand-off returns to the spoken chapter`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply {
            state.value = playing(1)
            accept = false
        }
        val coordinator = coordinator(tts, store, this).also { it.start() }
        runCurrent()

        tts.request(TtsChapterDirection.Next)
        advanceUntilIdle()

        assertEquals(1, store.state.value.activeChapterIndex)
        assertEquals(emptyList<String>(), tts.played)
        coordinator.close()
    }

    @Test
    fun `hand-off speaks the original in parallel original first`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply { state.value = playing(1) }
        val coordinator = coordinator(
            tts,
            store,
            this,
            parallel = true,
            order = "ORIGINAL_FIRST",
        ).also { it.start() }
        runCurrent()

        tts.request(TtsChapterDirection.Next)
        advanceUntilIdle()

        assertEquals(listOf("book#chapter=2"), tts.played)
        assertEquals(listOf(TtsLanguage.Original), tts.languages)
        coordinator.close()
    }

    @Test
    fun `hand-off speaks the translation in parallel translation first`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply { state.value = playing(1) }
        val translation = FakeTranslation(
            activeModeValue = ReaderTranslationMode("en", "ru", "GOOGLE_PA"),
        ).apply {
            state.value = mapOf(
                "book#chapter=2" to ReaderChapterTranslation(
                    status = dev.mirutime.feature.reader.api.ReaderTranslationStatus.Ready,
                    translations = mapOf(0 to "перевод"),
                    configKey = "k",
                    bookUrl = "book",
                ),
            )
        }
        val coordinator = coordinator(
            tts,
            store,
            this,
            parallel = true,
            order = "TRANSLATION_FIRST",
            translation = translation,
        ).also { it.start() }
        runCurrent()

        tts.request(TtsChapterDirection.Next)
        advanceUntilIdle()

        assertEquals(listOf(TtsLanguage.Translation), tts.languages)
        coordinator.close()
    }

    @Test
    fun `a translation arriving while playing switches the spoken language`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply {
            state.value = playing(1)
            sessionLanguage = TtsLanguage.Original
        }
        val translation = FakeTranslation(
            activeModeValue = ReaderTranslationMode("en", "ru", "GOOGLE_PA"),
        )
        val coordinator = coordinator(
            tts,
            store,
            this,
            parallel = true,
            order = "TRANSLATION_FIRST",
            translation = translation,
        ).also { it.start() }
        runCurrent()

        // Nothing translated yet: the session keeps the original language.
        assertTrue(tts.switched.isEmpty())

        translation.state.value = mapOf(
            "book#chapter=1" to ReaderChapterTranslation(
                status = dev.mirutime.feature.reader.api.ReaderTranslationStatus.Ready,
                translations = mapOf(0 to "перевод"),
                configKey = "k",
                bookUrl = "book",
            ),
        )
        advanceUntilIdle()

        // The translator lives outside the screen composition, so this also
        // covers auto-advance with the screen off.
        assertEquals(1, tts.switched.size)
        assertEquals(0, tts.switched.single().first)
        assertEquals(TtsLanguage.Translation, tts.switched.single().second)
        assertEquals(mapOf(0 to "перевод"), tts.switched.single().third)
        coordinator.close()
    }

    @Test
    fun `a translation arriving with translation off keeps the original language`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply {
            state.value = playing(1)
            sessionLanguage = TtsLanguage.Original
        }
        val translation = FakeTranslation()
        val coordinator = coordinator(
            tts,
            store,
            this,
            parallel = true,
            order = "TRANSLATION_FIRST",
            translation = translation,
        ).also { it.start() }
        runCurrent()

        // No active pair: even a cached entry must not switch the voice.
        translation.state.value = mapOf(
            "book#chapter=1" to ReaderChapterTranslation(
                status = dev.mirutime.feature.reader.api.ReaderTranslationStatus.Ready,
                translations = mapOf(0 to "перевод"),
                configKey = "k",
                bookUrl = "book",
            ),
        )
        advanceUntilIdle()

        assertTrue(tts.switched.isEmpty())
        coordinator.close()
    }

    @Test
    fun `hand-off ignores a translation from another parse`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply { state.value = playing(1) }
        val translation = FakeTranslation(
            activeModeValue = ReaderTranslationMode("en", "ru", "GOOGLE_PA"),
            configKeyValue = "current",
        ).apply {
            state.value = mapOf(
                "book#chapter=2" to ReaderChapterTranslation(
                    status = dev.mirutime.feature.reader.api.ReaderTranslationStatus.Ready,
                    translations = mapOf(0 to "перевод"),
                    configKey = "old-parse",
                    bookUrl = "book",
                ),
            )
        }
        val coordinator = coordinator(
            tts,
            store,
            this,
            parallel = true,
            order = "TRANSLATION_FIRST",
            translation = translation,
        ).also { it.start() }
        runCurrent()

        tts.request(TtsChapterDirection.Next)
        advanceUntilIdle()

        // The painted screen would hide this entry too: it must not be spoken.
        assertEquals(listOf(TtsLanguage.Original), tts.languages)
        coordinator.close()
    }

    @Test
    fun `hand-off keeps the original language when the chapter has no translation`() = runTest {
        val repo = Repo(chapters, docs)
        val store = ReaderSessionStore(repo, Positions(), Cfg(), ReadTracker(repo), this)
        store.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val tts = FakeTts().apply { state.value = playing(1) }
        val coordinator = coordinator(
            tts,
            store,
            this,
            parallel = true,
            order = "TRANSLATION_FIRST",
        ).also { it.start() }
        runCurrent()

        tts.request(TtsChapterDirection.Next)
        advanceUntilIdle()

        // No translated text yet: the voice must not claim a translation.
        assertEquals(listOf(TtsLanguage.Original), tts.languages)
        coordinator.close()
    }
}
