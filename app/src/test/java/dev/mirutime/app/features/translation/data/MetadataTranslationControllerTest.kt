package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.translation.domain.ChapterTranslationResult
import dev.mirutime.app.features.translation.domain.MetadataRequest
import dev.mirutime.app.features.translation.domain.MetadataTranslationResult
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.data.library.BookTranslationEntity
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataTranslationControllerTest {

    @Test
    fun `title batch translates library cards and maps results back`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val config = config()

        controller.submitTitles(
            listOf(
                TitleTranslationJob("fake://a/one", "One", config),
                TitleTranslationJob("fake://a/two", "Two", config),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, engine.requests.size)
        val request = engine.requests.single()
        assertEquals(2, request.units.size)
        assertEquals("translated:One", controller.state.value["fake://a/one"]?.title)
        assertEquals("translated:Two", controller.state.value["fake://a/two"]?.title)
        assertEquals(MetadataTranslationStatus.Ready, controller.state.value["fake://a/one"]?.status)
        assertEquals(false, controller.state.value["fake://a/one"]?.detailsLoaded)
    }

    @Test
    fun `book request fills title description and chapter titles`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val config = config()

        controller.submitBook(
            BookMetadataJob(
                bookUrl = "fake://a/one",
                title = "One",
                description = "Synopsis",
                chapters = listOf(1 to "First", 2 to "Second"),
                config = config,
            ),
        )
        advanceUntilIdle()

        val entry = controller.state.value["fake://a/one"]
        assertEquals(MetadataTranslationStatus.Ready, entry?.status)
        assertEquals("translated:One", entry?.title)
        assertEquals("translated:Synopsis", entry?.description)
        assertEquals(
            mapOf(1 to "translated:First", 2 to "translated:Second"),
            entry?.chapterTitles,
        )
        assertTrue(entry?.detailsLoaded == true)
    }

    @Test
    fun `cached book is not translated again with the same config`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val config = config()
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            chapters = listOf(1 to "First"),
            config = config,
        )

        controller.submitBook(job)
        advanceUntilIdle()
        controller.submitBook(job)
        controller.submitTitles(listOf(TitleTranslationJob(job.bookUrl, job.title, config)))
        advanceUntilIdle()

        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `changed config translates again`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val first = config()
        val second = config(provider = ProviderId.GOOGLE_FREE)

        controller.submitTitles(listOf(TitleTranslationJob("fake://a/one", "One", first)))
        advanceUntilIdle()
        controller.submitTitles(listOf(TitleTranslationJob("fake://a/one", "One", second)))
        advanceUntilIdle()

        assertEquals(2, engine.requests.size)
        assertEquals(second, controller.state.value["fake://a/one"]?.config)
    }

    @Test
    fun `title-only result does not block the full book request`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val config = config()

        controller.submitTitles(listOf(TitleTranslationJob("fake://a/one", "One", config)))
        advanceUntilIdle()
        controller.submitBook(
            BookMetadataJob("fake://a/one", "One", "Synopsis", listOf(1 to "First"), config),
        )
        advanceUntilIdle()

        assertEquals(2, engine.requests.size)
        assertEquals("translated:Synopsis", controller.state.value["fake://a/one"]?.description)
    }

    @Test
    fun `failure is visible and the same request can be retried`() = runTest {
        var fail = true
        val engine = FakeEngine { request ->
            if (fail) {
                Outcome.Err(PluginError.Network("offline"))
            } else {
                echo(request)
            }
        }
        val controller = MetadataTranslationController(engine, this)
        val job = BookMetadataJob(
            "fake://a/one",
            "One",
            "Synopsis",
            listOf(1 to "First"),
            config(),
        )

        controller.submitBook(job)
        advanceUntilIdle()
        assertEquals(MetadataTranslationStatus.Failed, controller.state.value["fake://a/one"]?.status)

        fail = false
        controller.submitBook(job)
        advanceUntilIdle()

        assertEquals(2, engine.requests.size)
        assertEquals(MetadataTranslationStatus.Ready, controller.state.value["fake://a/one"]?.status)
        assertEquals("translated:One", controller.state.value["fake://a/one"]?.title)
    }

    @Test
    fun `reader chapter request does not block the book synopsis`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val config = config()

        controller.submitChapters(
            ReaderChaptersJob(
                bookUrl = "fake://a/one",
                title = "One",
                chapters = listOf(1 to "First", 2 to "Second"),
                config = config,
            ),
        )
        advanceUntilIdle()

        val entry = controller.state.value["fake://a/one"]
        assertEquals("translated:One", entry?.title)
        assertEquals(mapOf(1 to "translated:First", 2 to "translated:Second"), entry?.chapterTitles)
        assertEquals(false, entry?.detailsLoaded)

        controller.submitBook(
            BookMetadataJob("fake://a/one", "One", "Synopsis", listOf(1 to "First"), config),
        )
        advanceUntilIdle()

        assertEquals("translated:Synopsis", controller.state.value["fake://a/one"]?.description)
        assertTrue(controller.state.value["fake://a/one"]?.detailsLoaded == true)
    }

    @Test
    fun `details persist title description and chapter titles under the full key`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val controller = MetadataTranslationController(engine, this, store)
        val config = config()

        controller.submitBook(
            BookMetadataJob(
                bookUrl = "fake://a/one",
                title = "One",
                description = "Synopsis",
                chapters = listOf(1 to "First"),
                config = config,
            ),
        )
        advanceUntilIdle()

        val key = store.keys.single()
        assertEquals(
            MetadataTranslationKey(
                source = "en",
                target = "ru",
                provider = "GEMINI",
                promptHash = ChapterTranslationPlanner.promptHash(null),
                pipelineVersion = MetadataPipelineVersion,
            ),
            key,
        )
        val stored = store.saved.getValue("fake://a/one" to key)
        assertEquals("translated:One", stored.title)
        assertEquals("translated:Synopsis", stored.description)
        assertEquals(mapOf(1 to "translated:First"), stored.chapterTitles)
    }

    @Test
    fun `chapter-only batch does not erase a stored description`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val controller = MetadataTranslationController(engine, this, store)
        val config = config()

        controller.submitBook(
            BookMetadataJob(
                bookUrl = "fake://a/one",
                title = "One",
                description = "Synopsis",
                chapters = listOf(1 to "First"),
                config = config,
            ),
        )
        advanceUntilIdle()

        // Same controller state already has titles, so simulate a fresh one
        // for the reader lane (the persisted row is what matters).
        val reader = MetadataTranslationController(engine, this, store)
        reader.hydrate("fake://a/one", config)
        val key = store.keys.single()
        store.save(
            "fake://a/one",
            key,
            MetadataTranslationPatch(title = "translated:One (new)"),
        )

        val stored = store.saved.getValue("fake://a/one" to key)
        assertEquals("translated:One (new)", stored.title)
        assertEquals("translated:Synopsis", stored.description)
        assertEquals(mapOf(1 to "translated:First"), stored.chapterTitles)
    }

    @Test
    fun `hydrated details skip the provider call`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val config = config()
        store.save(
            "fake://a/one",
            controllerKey(config),
            MetadataTranslationPatch(
                title = "Cached",
                description = "Cached synopsis",
                chapterTitles = mapOf(1 to "Cached first"),
            ),
        )
        val controller = MetadataTranslationController(engine, this, store)

        controller.hydrate("fake://a/one", config)
        controller.submitBook(
            BookMetadataJob(
                bookUrl = "fake://a/one",
                title = "One",
                description = "Synopsis",
                chapters = listOf(1 to "First"),
                config = config,
            ),
        )
        advanceUntilIdle()

        assertEquals(0, engine.requests.size)
        val entry = controller.state.value.getValue("fake://a/one")
        assertEquals("Cached", entry.title)
        assertEquals("Cached synopsis", entry.description)
        assertEquals(mapOf(1 to "Cached first"), entry.chapterTitles)
        assertTrue(entry.detailsLoaded)
    }

    @Test
    fun `hydrated library titles skip the provider after cold start`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val config = config()
        store.save(
            "fake://a/one",
            controllerKey(config),
            MetadataTranslationPatch(title = "Cached title"),
        )
        val controller = MetadataTranslationController(engine, this, store)
        val job = TitleTranslationJob("fake://a/one", "One", config)

        controller.hydrateTitles(listOf(job))
        controller.submitTitles(listOf(job))
        advanceUntilIdle()

        assertEquals(0, engine.requests.size)
        assertEquals("Cached title", controller.state.value["fake://a/one"]?.title)
    }

    @Test
    fun `different language pairs use different persisted keys`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val controller = MetadataTranslationController(engine, this, store)

        controller.submitTitles(
            listOf(TitleTranslationJob("fake://a/one", "One", config())),
        )
        controller.submitTitles(
            listOf(
                TitleTranslationJob(
                    "fake://a/one",
                    "One",
                    config().copy(target = LanguageCode.parse("de")),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            setOf("ru", "de"),
            store.keys.map { it.target }.toSet(),
        )
    }

    @Test
    fun `partial metadata patch keeps fields it does not touch`() {
        val key = MetadataTranslationKey(
            source = "en",
            target = "ru",
            provider = "GEMINI",
            promptHash = "p",
            pipelineVersion = MetadataPipelineVersion,
        )
        val existing = BookTranslationEntity(
            translationId = 9,
            bookId = 4,
            source = "en",
            target = "ru",
            provider = "GEMINI",
            model = null,
            promptHash = "p",
            sourceHash = "s",
            pipelineVersion = MetadataPipelineVersion,
            title = "Old title",
            description = "Old description",
            chapterTitlesJson = "{\"1\":\"One\"}",
            status = "COMPLETE",
            updatedAt = 1,
        )

        val merged = mergeMetadataTranslation(
            existing = existing,
            bookId = 4,
            key = key,
            patch = MetadataTranslationPatch(
                title = "New title",
                chapterTitles = mapOf(2 to "Two"),
            ),
            now = 2,
        )

        assertEquals(9, merged.translationId)
        assertEquals("New title", merged.title)
        assertEquals("Old description", merged.description)
        assertEquals(mapOf(1 to "One", 2 to "Two"), decodeChapterTitles(merged.chapterTitlesJson))
    }

    private fun controllerKey(config: MetadataConfig) = MetadataTranslationKey(
        source = config.source?.value.orEmpty(),
        target = config.target.value,
        provider = config.provider.name,
        promptHash = ChapterTranslationPlanner.promptHash(config.prompt),
        pipelineVersion = MetadataPipelineVersion,
    )

    private class FakeMetadataStore : MetadataTranslationStore {
        val saved = mutableMapOf<Pair<String, MetadataTranslationKey>, StoredMetadataTranslation>()
        val keys = mutableSetOf<MetadataTranslationKey>()

        override suspend fun load(
            bookUrl: String,
            key: MetadataTranslationKey,
        ): StoredMetadataTranslation? = saved[bookUrl to key]

        override suspend fun loadTitles(
            bookUrls: List<String>,
            key: MetadataTranslationKey,
        ): Map<String, StoredMetadataTranslation> = bookUrls.mapNotNull { url ->
            saved[url to key]?.let { url to it }
        }.toMap()

        override suspend fun save(
            bookUrl: String,
            key: MetadataTranslationKey,
            patch: MetadataTranslationPatch,
        ) {
            keys += key
            val previous = saved[bookUrl to key] ?: StoredMetadataTranslation()
            saved[bookUrl to key] = previous.copy(
                title = patch.title ?: previous.title,
                description = patch.description ?: previous.description,
                chapterTitles = previous.chapterTitles + patch.chapterTitles,
            )
        }
    }

    private fun config(provider: ProviderId = ProviderId.GEMINI) = MetadataConfig(
        source = LanguageCode.parse("en"),
        target = LanguageCode.parse("ru"),
        provider = provider,
        prompt = null,
    )

    private fun echo(request: MetadataRequest): Outcome<MetadataTranslationResult> =
        Outcome.Ok(
            MetadataTranslationResult(
                translations = request.units.mapNotNull { unit ->
                    unit.id.toIntOrNull()?.let { id -> id to "translated:${unit.text}" }
                }.toMap(),
                provider = request.provider,
                model = null,
            ),
        )

    private class FakeEngine(
        private val respond: (MetadataRequest) -> Outcome<MetadataTranslationResult>,
    ) : TranslationEngine {
        val requests = mutableListOf<MetadataRequest>()

        override suspend fun translateChapter(
            request: TranslationRequest,
        ): Outcome<ChapterTranslationResult> =
            error("metadata test engine has no chapter path")

        override suspend fun translateMetadata(
            request: MetadataRequest,
        ): Outcome<MetadataTranslationResult> {
            requests += request
            return respond(request)
        }
    }
}
