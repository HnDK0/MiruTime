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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `details batch translates the source status and persists it`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val controller = MetadataTranslationController(engine, this, store)
        val config = config()
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            status = "连载中",
            chapters = listOf(1 to "First"),
            config = config,
        )

        controller.submitBook(job)
        advanceUntilIdle()

        val entry = controller.state.value["fake://a/one"]
        assertEquals("translated:连载中", entry?.translatedStatus)
        assertEquals("连载中", entry?.translatedStatusSource)
        assertEquals(
            "-1",
            engine.requests.single().units.single { it.text == "连载中" }.id,
        )
        val stored = store.saved.getValue("fake://a/one" to store.keys.single())
        assertEquals("translated:连载中", stored.translatedStatus)
        assertEquals("连载中", stored.translatedStatusSource)

        // A cached status counts as done; a second submit must not re-request.
        controller.submitBook(job)
        advanceUntilIdle()
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `empty status translation is attempted once, not every open`() = runTest {
        val engine = FakeEngine { request ->
            Outcome.Ok(
                MetadataTranslationResult(
                    translations = request.units.mapNotNull { unit ->
                        unit.id.toIntOrNull()?.let { id ->
                            id to if (id == -1) "" else "translated:${unit.text}"
                        }
                    }.toMap(),
                    provider = request.provider,
                    model = null,
                ),
            )
        }
        val controller = MetadataTranslationController(engine, this)
        val config = config()
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            chapters = emptyList(),
            config = config,
            status = "连载中",
        )

        controller.submitBook(job)
        advanceUntilIdle()
        controller.submitBook(job)
        advanceUntilIdle()

        assertEquals(1, engine.requests.size)
        val entry = controller.state.value["fake://a/one"]
        assertEquals(null, entry?.translatedStatus)
        assertEquals("连载中", entry?.translatedStatusSource)
    }

    @Test
    fun `changed source status retranslates instead of keeping the old one`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val config = config()
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            chapters = emptyList(),
            config = config,
            status = "连载中",
        )

        controller.submitBook(job)
        advanceUntilIdle()
        assertEquals("translated:连载中", controller.state.value["fake://a/one"]?.translatedStatus)

        controller.submitBook(job.copy(status = "已完结"))
        advanceUntilIdle()

        assertEquals(2, engine.requests.size)
        val entry = controller.state.value["fake://a/one"]
        assertEquals("translated:已完结", entry?.translatedStatus)
        assertEquals("已完结", entry?.translatedStatusSource)
    }

    @Test
    fun `details batch translates genres in order and persists them`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val controller = MetadataTranslationController(engine, this, store)
        val config = config()
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            chapters = emptyList(),
            config = config,
            genres = listOf("Action", " Fantasy ", ""),
        )

        controller.submitBook(job)
        advanceUntilIdle()

        val entry = controller.state.value["fake://a/one"]
        assertEquals(listOf("translated:Action", "translated:Fantasy"), entry?.genres)
        assertEquals(listOf("Action", "Fantasy"), entry?.translatedGenresSource)
        assertEquals(
            listOf("-2", "-3"),
            engine.requests.single().units.filter { it.text == "Action" || it.text == "Fantasy" }
                .map { it.id },
        )
        val stored = store.saved.getValue("fake://a/one" to store.keys.single())
        assertEquals(listOf("translated:Action", "translated:Fantasy"), stored.translatedGenres)
        assertEquals(listOf("Action", "Fantasy"), stored.translatedGenresSource)

        controller.submitBook(job)
        advanceUntilIdle()
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `changed genre source retranslates instead of keeping the old list`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            chapters = emptyList(),
            config = config(),
            genres = listOf("Action"),
        )

        controller.submitBook(job)
        advanceUntilIdle()
        assertEquals(listOf("translated:Action"), controller.state.value["fake://a/one"]?.genres)

        controller.submitBook(job.copy(genres = listOf("Action", "Drama")))
        advanceUntilIdle()

        assertEquals(2, engine.requests.size)
        val entry = controller.state.value["fake://a/one"]
        assertEquals(listOf("Action", "Drama"), entry?.translatedGenresSource)
        assertEquals(listOf("translated:Action", "translated:Drama"), entry?.genres)
    }

    @Test
    fun `changed genres during a running batch request the new list`() = runTest {
        val engine = GatedEngine()
        val controller = MetadataTranslationController(engine, this)
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            chapters = emptyList(),
            config = config(),
            genres = listOf("Action"),
        )

        controller.submitBook(job)
        advanceUntilIdle()
        assertEquals(1, engine.requests.size)

        // Same book+config, different genre list: the new list must not be
        // swallowed by the queue key of the batch already in flight.
        controller.submitBook(job.copy(genres = listOf("Action", "Drama")))
        advanceUntilIdle()

        engine.release()
        advanceUntilIdle()
        // The stale batch is discarded; the entry is still waiting for the new one.
        assertEquals(MetadataTranslationStatus.Loading, controller.state.value["fake://a/one"]?.status)
        assertEquals(emptyList<String>(), controller.state.value["fake://a/one"]?.genres)

        engine.release()
        advanceUntilIdle()
        val entry = controller.state.value["fake://a/one"]
        assertEquals(listOf("Action", "Drama"), entry?.translatedGenresSource)
        assertEquals(listOf("GEMINI:Action", "GEMINI:Drama"), entry?.genres)
    }

    @Test
    fun `hydrated genres stay with their own language pair`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val ru = config()
        val de = ru.copy(target = LanguageCode.parse("de"))
        store.save(
            "fake://a/one",
            controllerKey(ru),
            MetadataTranslationPatch(
                translatedGenres = listOf("Фэнтези"),
                translatedGenresSource = listOf("Fantasy"),
            ),
        )
        store.save(
            "fake://a/one",
            controllerKey(de),
            MetadataTranslationPatch(
                translatedGenres = listOf("Fantasie"),
                translatedGenresSource = listOf("Fantasy"),
            ),
        )
        val controller = MetadataTranslationController(engine, this, store)

        controller.hydrate("fake://a/one", ru)
        assertEquals(ru, controller.state.value["fake://a/one"]?.config)
        assertEquals(listOf("Фэнтези"), controller.state.value["fake://a/one"]?.genres)

        controller.hydrate("fake://a/one", de)
        assertEquals(de, controller.state.value["fake://a/one"]?.config)
        assertEquals(listOf("Fantasie"), controller.state.value["fake://a/one"]?.genres)

        controller.hydrate("fake://a/one", ru)
        assertEquals(ru, controller.state.value["fake://a/one"]?.config)
        assertEquals(listOf("Фэнтези"), controller.state.value["fake://a/one"]?.genres)
    }

    @Test
    fun `cached details without a status request the status once`() = runTest {
        val config = config()
        val store = FakeMetadataStore()
        store.saved["fake://a/one" to controllerKey(config)] = StoredMetadataTranslation(
            title = "Заголовок",
            description = "Описание",
        )
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this, store)

        controller.hydrate("fake://a/one", config)
        controller.submitBook(
            BookMetadataJob(
                bookUrl = "fake://a/one",
                title = "One",
                description = "Synopsis",
                chapters = emptyList(),
                config = config,
                status = "连载中",
            ),
        )
        advanceUntilIdle()
        controller.submitBook(
            BookMetadataJob(
                bookUrl = "fake://a/one",
                title = "One",
                description = "Synopsis",
                chapters = emptyList(),
                config = config,
                status = "连载中",
            ),
        )
        advanceUntilIdle()

        // The pre-status cache must refetch exactly once, then stop.
        assertEquals(1, engine.requests.size)
        assertEquals("translated:连载中", controller.state.value["fake://a/one"]?.translatedStatus)
    }

    @Test
    fun `chapter titles can be switched off while details still translate`() = runTest {
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
                includeChapterTitles = false,
            ),
        )
        advanceUntilIdle()

        val request = engine.requests.single()
        assertEquals(2, request.units.size)
        assertEquals(setOf("0", "1"), request.units.map { it.id }.toSet())
        val entry = controller.state.value["fake://a/one"]
        assertEquals("translated:One", entry?.title)
        assertEquals("translated:Synopsis", entry?.description)
        assertTrue(entry?.chapterTitles?.isEmpty() != false)
    }

    @Test
    fun `turning chapter titles on after a details-only run requests them`() = runTest {
        val engine = FakeEngine(::echo)
        val controller = MetadataTranslationController(engine, this)
        val config = config()
        val job = BookMetadataJob(
            bookUrl = "fake://a/one",
            title = "One",
            description = "Synopsis",
            chapters = listOf(1 to "First", 2 to "Second"),
            config = config,
        )

        controller.submitBook(job.copy(includeChapterTitles = false))
        advanceUntilIdle()
        controller.submitBook(job.copy(includeChapterTitles = true))
        advanceUntilIdle()

        assertEquals(2, engine.requests.size)
        assertEquals(4, engine.requests.last().units.size)
        assertEquals(
            mapOf(1 to "translated:First", 2 to "translated:Second"),
            controller.state.value["fake://a/one"]?.chapterTitles,
        )
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
    fun `hydrated history titles are not re-requested beyond the cache limit`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val config = config()
        // The History screen hydrates up to 500 rows; the state cache must hold
        // them all so submitTitles never re-requests a persisted title.
        val jobs = (1..500).map { index ->
            TitleTranslationJob("fake://history/$index", "Original $index", config)
        }
        jobs.forEach { job ->
            store.save(
                job.bookKey,
                controllerKey(config),
                MetadataTranslationPatch(title = "Cached ${job.title}"),
            )
        }
        val controller = MetadataTranslationController(engine, this, store)

        controller.hydrateTitles(jobs)
        controller.submitTitles(jobs)
        advanceUntilIdle()

        assertTrue(engine.requests.isEmpty())
        assertEquals("Cached Original 1", controller.state.value["fake://history/1"]?.title)
        assertEquals("Cached Original 500", controller.state.value["fake://history/500"]?.title)
    }

    @Test
    fun `persisted titles survive an evicted cache without a provider call`() = runTest {
        val engine = FakeEngine(::echo)
        val store = FakeMetadataStore()
        val config = config()
        // 600 > CACHE_LIMIT: hydrate evicts the oldest keys, so the decision to
        // re-request must come from the store, not only from _state.
        val jobs = (1..600).map { index ->
            TitleTranslationJob("fake://evicted/$index", "Original $index", config)
        }
        jobs.forEach { job ->
            store.save(
                job.bookKey,
                controllerKey(config),
                MetadataTranslationPatch(title = "Cached ${job.title}"),
            )
        }
        val controller = MetadataTranslationController(engine, this, store)

        controller.hydrateTitles(jobs)
        controller.submitTitles(jobs)
        advanceUntilIdle()

        assertTrue(engine.requests.isEmpty())
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
    fun `stale metadata batch never paints a newer request`() = runTest {
        val engine = GatedEngine()
        val controller = MetadataTranslationController(engine, this)
        val first = config(provider = ProviderId.GEMINI)
        val second = config(provider = ProviderId.OPENAI)
        val bookUrl = "fake://a/one"

        controller.submitTitles(listOf(TitleTranslationJob(bookUrl, "One", first)))
        advanceUntilIdle()
        assertEquals(1, engine.requests.size)

        // A newer request for the same book replaces the pending key while the
        // first batch is still in flight.
        controller.submitTitles(listOf(TitleTranslationJob(bookUrl, "One", second)))
        advanceUntilIdle()

        engine.release()
        advanceUntilIdle()
        assertNotEquals("GEMINI:One", controller.state.value[bookUrl]?.title)

        engine.release()
        advanceUntilIdle()
        assertEquals("OPENAI:One", controller.state.value[bookUrl]?.title)
        assertEquals(MetadataTranslationStatus.Ready, controller.state.value[bookUrl]?.status)
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
            statusText = "Old status",
            statusSource = "Old source",
            chapterTitlesJson = "{\"1\":\"One\"}",
            genresJson = "[\"Old genre\"]",
            genresSourceJson = "[\"Old source genre\"]",
            status = "COMPLETE",
            updatedAt = 1,
        )

        val merged = mergeMetadataTranslation(
            existing = existing,
            bookId = 4,
            key = key,
            patch = MetadataTranslationPatch(
                title = "New title",
                translatedStatus = "New status",
                translatedStatusSource = "New source",
                chapterTitles = mapOf(2 to "Two"),
                translatedGenres = listOf("Новый жанр"),
                translatedGenresSource = listOf("New genre"),
            ),
            now = 2,
        )

        assertEquals(9, merged.translationId)
        assertEquals("New title", merged.title)
        assertEquals("Old description", merged.description)
        assertEquals("New status", merged.statusText)
        assertEquals("New source", merged.statusSource)
        assertEquals(mapOf(1 to "One", 2 to "Two"), decodeChapterTitles(merged.chapterTitlesJson))
        assertEquals("[\"Новый жанр\"]", merged.genresJson)
        assertEquals("[\"New genre\"]", merged.genresSourceJson)
    }

    @Test
    fun `partial metadata patch keeps genres it does not touch`() {
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
            genresJson = "[\"Фэнтези\"]",
            genresSourceJson = "[\"Fantasy\"]",
            status = "COMPLETE",
            updatedAt = 1,
        )

        val merged = mergeMetadataTranslation(
            existing = existing,
            bookId = 4,
            key = MetadataTranslationKey(
                source = "en",
                target = "ru",
                provider = "GEMINI",
                promptHash = "p",
                pipelineVersion = MetadataPipelineVersion,
            ),
            patch = MetadataTranslationPatch(title = "Only a title"),
            now = 2,
        )

        assertEquals("[\"Фэнтези\"]", merged.genresJson)
        assertEquals("[\"Fantasy\"]", merged.genresSourceJson)
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
                translatedStatus = patch.translatedStatus ?: previous.translatedStatus,
                translatedStatusSource = patch.translatedStatusSource
                    ?: previous.translatedStatusSource,
                chapterTitles = previous.chapterTitles + patch.chapterTitles,
                translatedGenres = patch.translatedGenres ?: previous.translatedGenres,
                translatedGenresSource = patch.translatedGenresSource
                    ?: previous.translatedGenresSource,
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

    /** Holds every batch until the test releases it; answers carry the provider. */
    private class GatedEngine : TranslationEngine {
        val requests = mutableListOf<MetadataRequest>()
        private val releases = Channel<Unit>(Channel.UNLIMITED)

        fun release() {
            releases.trySend(Unit)
        }

        override suspend fun translateChapter(
            request: TranslationRequest,
        ): Outcome<ChapterTranslationResult> =
            error("metadata test engine has no chapter path")

        override suspend fun translateMetadata(
            request: MetadataRequest,
        ): Outcome<MetadataTranslationResult> {
            requests += request
            releases.receive()
            return Outcome.Ok(
                MetadataTranslationResult(
                    translations = request.units.mapNotNull { unit ->
                        unit.id.toIntOrNull()?.let { id ->
                            id to "${request.provider.name}:${unit.text}"
                        }
                    }.toMap(),
                    provider = request.provider,
                    model = null,
                ),
            )
        }
    }
}
