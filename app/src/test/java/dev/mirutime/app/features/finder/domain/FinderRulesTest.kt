package dev.mirutime.app.features.finder.domain

import dev.mirutime.app.features.library.domain.ContentType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinderRulesTest {
    @Test
    fun `translation is offered only for novel plugins`() {
        assertTrue(supportsPluginTranslation(ContentType.Novel))
        assertFalse(supportsPluginTranslation(ContentType.Manga))
        assertFalse(supportsPluginTranslation(ContentType.Video))
    }
}
