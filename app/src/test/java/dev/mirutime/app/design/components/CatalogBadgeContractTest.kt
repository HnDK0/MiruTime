package dev.mirutime.app.design.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBadgeContractTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `catalog uses the bookmark, not an in-library text badge`() {
        val text = source("src/main/java/dev/mirutime/app/design/components/CatalogTile.kt")

        assertFalse(text.contains("finder_caption_in_library_fmt"))
        assertFalse(text.contains("badge = if (inLibrary)"))
        assertFalse(text.contains("badge = stringResource"))
        assertTrue(text.contains("membership = badge"))
        assertTrue(text.contains("MiruBookCard"))
    }
}
