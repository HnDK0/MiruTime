package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.finder.domain.dedupeCatalogBooks
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogGlobalDedupeTest {

    private fun source(id: String) = CatalogSource(
        id = id,
        name = id,
        language = "EN",
        type = ContentType.Novel,
        version = "1.0.0",
        pinned = false,
    )

    private fun book(url: String) = CatalogBook(
        bookUrl = url,
        title = url,
        rating = 0f,
        chapters = 0,
        genres = emptyList(),
        ongoing = false,
    )

    @Test
    fun `catalog books dedupe by url`() {
        assertEquals(
            listOf("https://site/one", "https://site/two"),
            dedupeCatalogBooks(
                listOf(
                    book("https://site/one"),
                    book("https://site/one"),
                    book("https://site/two"),
                ),
            ).map { it.bookUrl },
        )
    }

    @Test
    fun `global sections dedupe by source id`() {
        val first = SourceResult(source("one"), listOf(book("https://site/a")), failed = false)
        val duplicate = SourceResult(source("one"), emptyList(), failed = true)
        val second = SourceResult(source("two"), emptyList(), failed = false)

        assertEquals(
            listOf("one", "two"),
            dedupeSourceResults(listOf(first, duplicate, second)).map { it.source.id },
        )
    }
}
