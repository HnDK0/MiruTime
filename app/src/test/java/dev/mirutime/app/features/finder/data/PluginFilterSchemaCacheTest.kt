package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterType
import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogPage as CoreCatalogPage
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.getOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginFilterSchemaCacheTest {

    private val schema = listOf(PluginFilter("kind", PluginFilterType.Text, "Kind"))

    @Test
    fun `concurrent requests load the schema once`() = runBlocking {
        val cache = PluginFilterSchemaCache()
        var calls = 0

        val results = coroutineScope {
            (1..6).map {
                async {
                    cache.get("alpha") {
                        calls++
                        delay(20)
                        Outcome.Ok(schema)
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, calls)
        assertTrue(results.all { it == Outcome.Ok(schema) })
    }

    @Test
    fun `failed load is not cached and can be retried`() = runBlocking {
        val cache = PluginFilterSchemaCache()
        var calls = 0
        var available = false

        val first = cache.get("alpha") {
            calls++
            if (available) Outcome.Ok(schema) else Outcome.Err(PluginError.ScriptError("boom"))
        }
        available = true
        val second = cache.get("alpha") {
            calls++
            Outcome.Ok(schema)
        }

        assertTrue(first is Outcome.Err)
        assertEquals(Outcome.Ok(schema), second)
        assertEquals(2, calls)
    }

    @Test
    fun `provider path distinguishes empty schema from a load error`() = runBlocking {
        val cache = PluginFilterSchemaCache()
        var calls = 0
        var response: Outcome<List<Map<String, String>>> = Outcome.Ok(emptyList())
        val source = fakeSource {
            calls++
            response
        }

        // A real empty getFilterList is a valid schema and stays cached.
        assertEquals(Outcome.Ok(emptyList<PluginFilter>()), loadFilterSchema("alpha", source, cache))
        assertEquals(Outcome.Ok(emptyList<PluginFilter>()), loadFilterSchema("alpha", source, cache))
        assertEquals(1, calls)

        // Inject failure through the same cache: it must surface as Err and
        // must not freeze the earlier empty schema as a final answer.
        cache.invalidate("alpha")
        response = Outcome.Err(PluginError.ScriptError("boom"))
        val failed = loadFilterSchema("alpha", source, cache)
        assertTrue(failed is Outcome.Err)

        response = Outcome.Ok(
            listOf(
                mapOf(
                    "key" to "kind",
                    "type" to "text",
                    "label" to "Kind",
                ),
            ),
        )
        val recovered = loadFilterSchema("alpha", source, cache)
        assertEquals("Kind", recovered.getOrNull()?.single()?.label)
        assertEquals(3, calls)
    }

    @Test
    fun `fake provider exposes a real fixture shape and no local schema`() = runBlocking {
        val provider = FakeFinderDataProvider()

        val plugin = provider.filterSchema("wtr-lab").getOrNull().orEmpty()
        val local = provider.filterSchema("local-books").getOrNull().orEmpty()

        assertEquals(
            setOf(
                PluginFilterType.Sort,
                PluginFilterType.Select,
                PluginFilterType.Checkbox,
                PluginFilterType.Tristate,
                PluginFilterType.Switch,
                PluginFilterType.Text,
                PluginFilterType.TagInput,
            ),
            plugin.map { it.type }.toSet(),
        )
        assertTrue(plugin.all { it.label.isNotBlank() })
        assertEquals(emptyList<PluginFilter>(), local)
    }

    private fun fakeSource(
        response: suspend () -> Outcome<List<Map<String, String>>>,
    ): PluginSource = object : PluginSource {
        override val metadata = PluginMetadata(
            id = "alpha",
            name = "Alpha",
            baseUrl = "https://example.com",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "1.0.0",
            sha256 = "",
        )

        override suspend fun catalog(index: Int): Outcome<CoreCatalogPage> =
            Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false))

        override suspend fun search(index: Int, query: String): Outcome<CoreCatalogPage> =
            Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false))

        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CoreCatalogPage> = Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false))

        override suspend fun book(url: String): Outcome<BookMeta> =
            Outcome.Err(PluginError.ScriptError("unused"))

        override suspend fun chapters(url: String): Outcome<List<Chapter>> =
            Outcome.Err(PluginError.ScriptError("unused"))

        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            Outcome.Err(PluginError.ScriptError("unused"))

        override suspend fun filters() = response()

        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> =
            Outcome.Ok(emptyList())
    }
}
