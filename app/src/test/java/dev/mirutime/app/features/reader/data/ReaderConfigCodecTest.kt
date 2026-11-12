package dev.mirutime.app.features.reader.data

import dev.mirutime.feature.reader.MangaPagedNavigation
import dev.mirutime.feature.reader.MangaReaderStyle
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
    fun `webtoon is the standard mode for every install`() {
        assertEquals(
            MangaReadingMode.Webtoon,
            resolveMangaMode(stored = null, explicitModeChoice = false).mode,
        )
        assertEquals(
            MangaReadingMode.Webtoon,
            resolveMangaMode(MangaReaderStyle.Default, explicitModeChoice = true).mode,
        )
    }

    @Test
    fun `a saved paged mode switches to webtoon once unless the user chose it`() {
        val paged = MangaReaderStyle.Default.copy(
            mode = MangaReadingMode.Paged,
            brightness = 0.8f,
        )

        val migrated = resolveMangaMode(paged, explicitModeChoice = false)
        assertEquals(MangaReadingMode.Webtoon, migrated.mode)
        assertEquals(0.8f, migrated.brightness, 0.001f)

        assertEquals(
            MangaReadingMode.Paged,
            resolveMangaMode(paged, explicitModeChoice = true).mode,
        )
    }

    @Test
    fun `stored style without a mode field is webtoon`() {
        val legacy = mangaStyleFromJson(buildJsonObject { put("brightness", 0.8f) })
        assertEquals(MangaReadingMode.Webtoon, legacy.mode)
        assertEquals(0.8f, legacy.brightness, 0.001f)
    }

    @Test
    fun `manga navigation and viewer toggles survive a round trip`() {
        val style = MangaReaderStyle.Default.copy(
            pagedNavigation = MangaPagedNavigation.TapEdges,
            pageTransition = false,
            doubleTapMenu = true,
        )
        val decoded = mangaStyleFromJson(mangaStyleToJson(style))
        assertEquals(MangaPagedNavigation.TapEdges, decoded.pagedNavigation)
        assertEquals(false, decoded.pageTransition)
        assertEquals(true, decoded.doubleTapMenu)
    }

    @Test
    fun `legacy manga style defaults the new viewer toggles`() {
        val legacy = mangaStyleFromJson(buildJsonObject { put("mode", "Paged") })
        assertEquals(MangaPagedNavigation.Both, legacy.pagedNavigation)
        assertEquals(true, legacy.pageTransition)
    }

    @Test
    fun `manga autoscroll speed survives a round trip apart from the novel one`() {
        val manga = MangaReaderStyle.Default.copy(autoScrollSpeed = 125).sanitized()
        val novel = ReaderStyle.Default.copy(autoScrollSpeed = 40).sanitized()

        val decodedManga = mangaStyleFromJson(mangaStyleToJson(manga))
        val decodedNovel = styleFromJson(styleToJson(novel))

        assertEquals(125, decodedManga.autoScrollSpeed)
        assertEquals(40, decodedNovel.autoScrollSpeed)
    }

    @Test
    fun `hostile manga autoscroll speed is clamped`() {
        val decoded = mangaStyleFromJson(buildJsonObject { put("autoScrollSpeed", 1) })
        assertEquals(5, decoded.autoScrollSpeed)
    }
}
