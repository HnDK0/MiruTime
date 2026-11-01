package dev.mirutime.app.features.reader.data

import dev.mirutime.feature.reader.MangaReadingMode
import dev.mirutime.feature.reader.ReaderStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderConfigCodecTest {
    @Test
    fun `style survives the shared preferences json round trip`() {
        val style = ReaderStyle.Default.copy(
            fullscreen = false,
            keepScreenOn = true,
            selectableText = false,
            autoScrollSpeed = 150,
            textColor = 0xFF00FF00,
        ).sanitized()

        val decoded = styleFromJson(
            Json.parseToJsonElement(styleToJson(style).toString()).jsonObject,
        )

        assertEquals(style, decoded)
        assertFalse(decoded.fullscreen)
        assertEquals(150, decoded.autoScrollSpeed)
    }

    @Test
    fun `legacy and hostile values fall back safely`() {
        val decoded = styleFromJson(
            buildJsonObject {
                put("autoScrollSpeed", 999)
                put("fullscreen", false)
                put("fontSizeSp", 1)
                put("backgroundId", "nope")
            },
        )

        assertEquals(200, decoded.autoScrollSpeed)
        assertFalse(decoded.fullscreen)
        assertEquals(8, decoded.fontSizeSp)
        assertEquals("nope", decoded.backgroundId)
    }

    @Test
    fun `fresh install gets webtoon while an upgraded install keeps paged`() {
        assertEquals(
            MangaReadingMode.Webtoon,
            resolveInitialMangaStyle(stored = null, marker = null, freshInstall = true).mode,
        )
        assertEquals(
            MangaReadingMode.Paged,
            resolveInitialMangaStyle(stored = null, marker = null, freshInstall = false).mode,
        )
    }

    @Test
    fun `explicit saved manga mode survives the default migration`() {
        val paged = buildJsonObject { put("mode", "Paged") }.toString()
        val webtoon = buildJsonObject { put("mode", "Webtoon") }.toString()

        assertEquals(
            MangaReadingMode.Paged,
            resolveInitialMangaStyle(paged, marker = null, freshInstall = true).mode,
        )
        assertEquals(
            MangaReadingMode.Webtoon,
            resolveInitialMangaStyle(webtoon, marker = null, freshInstall = false).mode,
        )
    }

    @Test
    fun `resolved marker keeps the first default on an existing install`() {
        assertEquals(
            MangaReadingMode.Paged,
            resolveInitialMangaStyle(stored = null, marker = "Paged", freshInstall = true).mode,
        )
        assertEquals(
            MangaReadingMode.Webtoon,
            resolveInitialMangaStyle(stored = null, marker = "Webtoon", freshInstall = false).mode,
        )
    }

    @Test
    fun `stored style without a mode field is a legacy paged save`() {
        val legacy = mangaStyleFromJson(buildJsonObject { put("brightness", 0.8f) })
        assertEquals(MangaReadingMode.Paged, legacy.mode)
        assertEquals(0.8f, legacy.brightness, 0.001f)
    }
}
