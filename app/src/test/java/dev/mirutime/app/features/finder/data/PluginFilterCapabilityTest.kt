package dev.mirutime.app.features.finder.data

import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogRequest
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterOption
import dev.mirutime.app.features.finder.domain.PluginFilterType
import dev.mirutime.app.features.finder.domain.pluginCatalogRequest
import dev.mirutime.core.api.CatalogPage as CoreCatalogPage
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Query+filters is capability-driven: a plugin that does not declare
// `search_filters` must get the plain search call (filters are never silently
// promised); a capable plugin gets one catalogFiltered with the reserved
// `_query`; a failed catalogFiltered is an error, not an empty page.
class PluginFilterCapabilityTest {

    private val schema = listOf(
        PluginFilter(
            key = "genre",
            type = PluginFilterType.Checkbox,
            label = "Genre",
            options = listOf(PluginFilterOption("action", "Action")),
            defaultValue = "action",
        ),
    )

    @Test
    fun unsupportedQueryUsesSearchOnly() {
        val request = pluginCatalogRequest(
            filters = CatalogFilters(query = "dragon"),
            schema = schema,
            supportsSearchFilters = false,
        )

        assertTrue("expected one search call, got $request", request is CatalogRequest.Search)
        assertEquals("dragon", (request as CatalogRequest.Search).query)
    }

    @Test
    fun supportedQueryUsesOneFilteredRequestWithReservedQuery() {
        val request = pluginCatalogRequest(
            filters = CatalogFilters(
                query = "dragon",
                pluginValues = mapOf("genre_included" to listOf("action")),
            ),
            schema = schema,
            supportsSearchFilters = true,
        )

        assertTrue("expected one catalogFiltered call, got $request", request is CatalogRequest.Filtered)
        val filters = (request as CatalogRequest.Filtered).filters
        assertEquals(listOf("dragon"), filters["_query"])
        assertEquals(listOf("action"), filters["genre_included"])
    }

    @Test
    fun filtersWithoutQueryUseFiltered() {
        val request = pluginCatalogRequest(
            filters = CatalogFilters(pluginValues = mapOf("genre_included" to listOf("action"))),
            schema = schema,
            supportsSearchFilters = false,
        )

        assertTrue(request is CatalogRequest.Filtered)
    }

    @Test
    fun noQueryNoFiltersUsesCatalogList() {
        assertEquals(
            CatalogRequest.List,
            pluginCatalogRequest(CatalogFilters(), schema, supportsSearchFilters = true),
        )
    }

    @Test
    fun failedFilteredCallIsAnErrorNotEmptyPage() {
        val error = PluginError.Network("HTTP 503")
        try {
            requirePluginPage("catalogFiltered", Outcome.Err(error))
            fail("a failed catalogFiltered must throw")
        } catch (thrown: PluginRequestException) {
            assertEquals(error, thrown.error)
        }
    }

    @Test
    fun genuinelyEmptyFilteredPageStaysEmpty() {
        val page = requirePluginPage(
            "catalogFiltered",
            Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false)),
        )

        assertTrue(page.items.isEmpty())
    }
}
