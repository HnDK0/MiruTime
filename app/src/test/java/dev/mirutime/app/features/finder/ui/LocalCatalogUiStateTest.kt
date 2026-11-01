package dev.mirutime.app.features.finder.ui

import dev.mirutime.app.features.finder.data.localCatalogUrl
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.LocalSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pure UI-state rules for the local catalogue: which tap action a tile gets and
// which format chips the filter sheet may offer.
class LocalCatalogUiStateTest {

    private fun book(url: String, notImported: Boolean, format: String? = null) = CatalogBook(
        bookUrl = url,
        title = "one",
        rating = 0f,
        chapters = if (notImported) 0 else 4,
        genres = emptyList(),
        ongoing = false,
        localFormat = format,
        notImported = notImported,
    )

    @Test
    fun `scanned and imported local files open directly`() {
        val scanned = book(
            localCatalogUrl(
                "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/one.epub",
            ),
            notImported = true,
            format = "EPUB",
        )
        val imported = book("local://seven", notImported = false, format = "EPUB")

        assertEquals(LocalCatalogTap.Open, localCatalogTap(scanned))
        assertEquals(LocalCatalogTap.Open, localCatalogTap(imported))
    }

    @Test
    fun `plugin urls are not treated as local catalogue files`() {
        assertNull(localCatalogTap(book("https://example.com/book/one", notImported = false)))
        assertEquals(
            LocalCatalogTap.Open,
            localCatalogTap(book("local-file://broken", notImported = false)),
        )
    }

    @Test
    fun `local filter offers only the formats of its source kind`() {
        assertEquals(listOf("EPUB", "FB2"), localCatalogFormats(LocalSources.Books))
        assertEquals(listOf("CBZ", "IMAGES"), localCatalogFormats(LocalSources.Manga))
    }
}
