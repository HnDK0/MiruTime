package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookActionKind
import org.junit.Assert.assertEquals
import org.junit.Test

// A known pdf-only entry must not bounce back to the catalogue: that CTA is
// reserved for entries we know nothing about.
class OpdsBookActionTest {

    @Test
    fun `owned entry opens the library book`() {
        assertEquals(
            BookActionKind.OpenLibrary,
            opdsBookAction(hasMembership = true, hasDownloadable = false, entryKnown = true),
        )
    }

    @Test
    fun `downloadable entry offers add`() {
        assertEquals(
            BookActionKind.AddToLibrary,
            opdsBookAction(hasMembership = false, hasDownloadable = true, entryKnown = true),
        )
    }

    @Test
    fun `known entry without a readable file is not a catalogue bounce`() {
        assertEquals(
            BookActionKind.NotDownloadable,
            opdsBookAction(hasMembership = false, hasDownloadable = false, entryKnown = true),
        )
    }

    @Test
    fun `missing metadata still points at the catalogue`() {
        assertEquals(
            BookActionKind.OpenCatalog,
            opdsBookAction(hasMembership = false, hasDownloadable = false, entryKnown = false),
        )
    }
}
