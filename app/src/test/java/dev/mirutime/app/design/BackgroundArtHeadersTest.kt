package dev.mirutime.app.design

import dev.mirutime.app.di.host.AndroidSystemInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundArtHeadersTest {

    @Test
    fun `custom background requests carry the shared browser user agent`() {
        val headers = backgroundArtHeaders()

        assertEquals(AndroidSystemInfo.defaultUserAgent, headers["User-Agent"])
        assertEquals("image/*", headers["Accept"])
    }

    @Test
    fun `preset download uses the shared user agent instead of the old literal`() {
        val text = source("src/main/java/dev/mirutime/app/design/BackgroundPresets.kt")

        assertTrue(text.contains("AndroidSystemInfo.defaultUserAgent"))
        assertFalse(text.contains("\"MiruTime-Android\""))
    }

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()
}
