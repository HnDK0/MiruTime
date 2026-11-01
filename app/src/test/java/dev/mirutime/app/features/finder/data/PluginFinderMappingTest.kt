package dev.mirutime.app.features.finder.data

import dev.mirutime.app.di.InstallReport
import dev.mirutime.app.di.InstalledPlugin
import dev.mirutime.app.di.REPO_FAILURE_KEY
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterOption
import dev.mirutime.app.features.finder.domain.PluginFilterType
import dev.mirutime.app.features.finder.domain.pluginFilterMap
import dev.mirutime.core.api.CatalogItem
import dev.mirutime.core.api.CatalogPage as CoreCatalogPage
import dev.mirutime.core.api.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginFinderMappingTest {

    private fun item(
        title: String = "Title",
        url: String = "https://example.com/book/1",
        rating: String? = null,
    ) = CatalogItem(title = title, url = url, rating = rating)

    @Test
    fun `duplicate urls collapse to one item`() {
        val twkan = "https://twkan.com/book/82888.html"
        val page = CoreCatalogPage(
            items = listOf(
                item(title = "First", url = twkan),
                item(title = "Second", url = twkan),
            ),
            hasNext = false,
        )

        val books = mapCatalogPage(page)

        assertEquals(1, books.size)
        assertEquals(twkan, books.single().bookUrl)
        assertEquals("First", books.single().title)
    }

    @Test
    fun `blank url or title is dropped`() {
        val page = CoreCatalogPage(
            items = listOf(
                item(title = "   ", url = "https://example.com/a"),
                item(title = "No URL", url = ""),
                item(title = "Good", url = "https://example.com/b"),
            ),
            hasNext = false,
        )

        val books = mapCatalogPage(page)

        assertEquals(listOf("Good"), books.map { it.title })
    }

    @Test
    fun `non finite rating is coerced to zero and valid rating is kept`() {
        val page = CoreCatalogPage(
            items = listOf(
                item(title = "Nan", url = "https://example.com/nan", rating = "NaN"),
                item(title = "Inf", url = "https://example.com/inf", rating = "Infinity"),
                item(title = "Valid", url = "https://example.com/valid", rating = "4.5"),
            ),
            hasNext = false,
        )

        val books = mapCatalogPage(page)

        assertEquals(0f, books.first { it.title == "Nan" }.rating)
        assertEquals(0f, books.first { it.title == "Inf" }.rating)
        assertEquals(4.5f, books.first { it.title == "Valid" }.rating)
    }

    @Test
    fun `cap limits the returned list`() {
        val page = CoreCatalogPage(
            items = (1..5).map { item(title = "Book $it", url = "https://example.com/$it") },
            hasNext = true,
        )

        assertEquals(3, mapCatalogPage(page, cap = 3).size)
    }

    @Test
    fun `plugin filter map sends schema keys instead of synthetic filters`() {
        val schema = listOf(
            PluginFilter(
                key = "order",
                type = PluginFilterType.Sort,
                label = "Sort",
                options = listOf(PluginFilterOption("weekly", "Weekly")),
                defaultValue = "weekly",
            ),
            PluginFilter(
                key = "genre",
                type = PluginFilterType.Checkbox,
                label = "Genres",
                options = listOf(PluginFilterOption("fantasy", "Fantasy")),
            ),
        )
        val map = pluginFilterMap(
            CatalogFilters(
                sort = CatalogSort.Rating,
                descending = false,
                genres = setOf("Fantasy"),
                ongoingOnly = true,
                pluginValues = mapOf(
                    "order" to listOf("new"),
                    "order_ascending" to listOf("true"),
                    "genre_included" to listOf("fantasy"),
                ),
            ),
            schema,
        )

        assertEquals(listOf("new"), map["order"])
        assertEquals(listOf("true"), map["order_ascending"])
        assertEquals(listOf("fantasy"), map["genre_included"])
        assertTrue("status" !in map)
        assertTrue("descending" !in map)
        assertTrue("genres_included" !in map)
    }

    @Test
    fun `mapSources dedupes by id and skips errored or disabled plugins`() {
        val enabled = plugin("alpha", enabled = true, error = null)
        val duplicate = plugin("alpha", name = "Alpha Again", enabled = true, error = null)
        val disabled = plugin("beta", enabled = false, error = null)
        val broken = plugin("gamma", enabled = true, error = "boom")

        val sources = mapSources(listOf(enabled, duplicate, disabled, broken))

        assertEquals(listOf("alpha"), sources.map { it.id })
        assertEquals("Alpha", sources.single().name)
        assertTrue(sources.none { it.id == "beta" })
        assertTrue(sources.none { it.id == "gamma" })
    }

    @Test
    fun `mapSources applies persisted pins`() {
        val sources = mapSources(listOf(plugin("alpha", enabled = true, error = null)), setOf("alpha"))

        assertTrue(sources.single().pinned)
    }

    @Test
    fun `mapSources applies library counts and defaults missing ids to zero`() {
        val sources = mapSources(
            installed = listOf(
                plugin("alpha", enabled = true, error = null),
                plugin("beta", enabled = true, error = null),
            ),
            counts = mapOf("alpha" to 3),
        )

        assertEquals(3, sources.first { it.id == "alpha" }.libraryCount)
        assertEquals(0, sources.first { it.id == "beta" }.libraryCount)
    }

    @Test
    fun `install failure details name every failed source`() {
        val report = InstallReport(
            installed = listOf("alpha"),
            failed = listOf(
                "alpha" to "HTTP 500",
                REPO_FAILURE_KEY to "timeout",
                "ghost" to "not found",
            ),
        )

        assertEquals(
            "Alpha: HTTP 500; Repository: timeout; ghost: not found",
            installFailureDetails(report, mapOf("alpha" to "Alpha"), "Repository"),
        )
    }

    @Test
    fun `successful install pass has no failure details`() {
        assertNull(installFailureDetails(InstallReport(installed = listOf("alpha"), failed = emptyList())))
    }

    private fun plugin(
        id: String,
        name: String = id.replaceFirstChar { it.uppercase() },
        enabled: Boolean,
        error: String?,
    ) = InstalledPlugin(
        id = id,
        name = name,
        language = "en",
        version = "1.0.0",
        contentType = ContentType.NOVEL,
        enabled = enabled,
        error = error,
    )
}