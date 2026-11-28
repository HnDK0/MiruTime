package dev.mirutime.core.opds

import dev.mirutime.core.api.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private fun feedOf(publication: String): OpdsFeed =
    assertNotNull(
        Opds2Parser.parse("""{"metadata":{"title":"Catalog"},"publications":[$publication]}""".toByteArray(), "https://h/feed")
            .getOrNull(),
    )

class Opds2ShapesTest {
    @Test
    fun aLocalizedTitleIsReadAndEnglishIsPreferred() {
        val entry = feedOf("""{"metadata":{"title":{"fr":"Titre","en":"Title"}},"links":[]}""").entries.single()
        assertEquals("Title", entry.title)
    }

    @Test
    fun aLocalizedTitleWithoutEnglishFallsBackToTheFirstLanguage() {
        val entry = feedOf("""{"metadata":{"title":{"fr":"Titre","de":"Titel"}},"links":[]}""").entries.single()
        assertEquals("Titre", entry.title)
    }

    @Test
    fun anArrayRelStillFindsTheAcquisitionLink() {
        val entry = feedOf(
            """{"metadata":{"title":"T"},"links":[{"rel":["self","http://opds-spec.org/acquisition/open-access"],"href":"/b.epub","type":"application/epub+zip"}]}""",
        ).entries.single()
        assertEquals("https://h/b.epub", entry.acquisitions.single().href)
    }

    @Test
    fun aPlainStringRelAndTitleStillWork() {
        val entry = feedOf(
            """{"metadata":{"title":"Plain"},"links":[{"rel":"http://opds-spec.org/acquisition","href":"/p.epub","type":"application/epub+zip"}]}""",
        ).entries.single()
        assertEquals("Plain", entry.title)
        assertEquals("https://h/p.epub", entry.acquisitions.single().href)
    }
}
