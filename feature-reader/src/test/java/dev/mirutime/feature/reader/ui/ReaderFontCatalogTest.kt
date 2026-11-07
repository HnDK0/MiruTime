package dev.mirutime.feature.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontCatalogTest {
    @Test
    fun parsesFamilyAndAliasNames() {
        val xml = """
            <familyset>
              <family name="Roboto">
                <font weight="400">Roboto-Regular.ttf</font>
              </family>
              <alias name="sans-serif" to="Roboto" />
              <family>
                <font weight="400">Fallback.ttf</font>
              </family>
            </familyset>
        """.trimIndent()

        assertEquals(listOf("Roboto", "sans-serif"), ReaderFontCatalog.parseFamilyNames(xml))
    }

    @Test
    fun canonicalizesLegacyKeys() {
        assertEquals("sans-serif", ReaderFontCatalog.canonicalKey("sans"))
        assertEquals("monospace", ReaderFontCatalog.canonicalKey("mono"))
        assertEquals("serif", ReaderFontCatalog.canonicalKey("serif"))
        assertEquals("Noto Sans", ReaderFontCatalog.canonicalKey("Noto Sans"))
    }

    @Test
    fun systemKeysAreNamespaced() {
        val key = ReaderFontCatalog.systemKey("Roboto")

        assertEquals("sys:Roboto", key)
        assertEquals("Roboto", ReaderFontCatalog.systemName(key))
        assertNull(ReaderFontCatalog.systemName("Roboto"))
    }

    @Test
    fun `a stale system key falls back after the catalog is loaded`() {
        assertNull(
            ReaderFontCatalog.knownSystemName(
                "sys:Roboto",
                listOf("sys:Noto Sans"),
            ),
        )
        assertEquals(
            "Roboto",
            ReaderFontCatalog.knownSystemName(
                "sys:Roboto",
                listOf("sys:Roboto"),
            ),
        )
        assertNull(ReaderFontCatalog.knownSystemName("Roboto", listOf("sys:Roboto")))
    }

    @Test
    fun garbageXmlYieldsNoFamilies() {
        assertEquals(emptyList<String>(), ReaderFontCatalog.parseFamilyNames("not xml at all"))
        assertEquals(emptyList<String>(), ReaderFontCatalog.parseFamilyNames("<family name=\"  \"/>" ))
    }

    @Test
    fun mergesGenericsFirstAndDeduplicates() {
        val merged = ReaderFontCatalog.mergeNames(
            listOf("Roboto", "sans", "sans-serif", "Roboto", "roboto"),
        )

        assertEquals("serif", merged.first())
        assertEquals("sans-serif", merged[1])
        assertEquals("monospace", merged[2])
        assertEquals(1, merged.count { it == "Roboto" })
        assertEquals(1, merged.count { it == "sans-serif" })
    }

    @Test
    fun fallsBackWhenNothingWasParsed() {
        val merged = ReaderFontCatalog.mergeNames(emptyList())

        assertTrue(merged.contains("serif"))
        assertTrue(merged.contains("sans-serif"))
        assertTrue(merged.contains("monospace"))
        assertTrue(merged.contains("sans-serif-condensed"))
    }

    @Test
    fun createsHumanReadableNames() {
        assertEquals("Sans Serif Condensed Light", ReaderFontCatalog.displayName("sans-serif-condensed-light"))
        assertEquals("Noto Sans CJK SC", ReaderFontCatalog.displayName("Noto Sans CJK SC"))
    }
}
