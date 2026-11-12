package dev.mirutime.app.features.settings.data

import dev.mirutime.app.features.reader.data.mangaStyleFromJson
import dev.mirutime.app.features.reader.data.mangaStyleToJson
import dev.mirutime.app.features.reader.data.styleFromJson
import dev.mirutime.app.features.reader.data.styleToJson
import dev.mirutime.feature.reader.MangaColorFilter
import dev.mirutime.feature.reader.MangaPagedNavigation
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.MangaReadingDirection
import dev.mirutime.feature.reader.MangaReadingMode
import dev.mirutime.feature.reader.MangaWebtoonPadding
import dev.mirutime.feature.reader.ReaderStyle
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSettingsCoverageTest {

    private fun dataFields(type: Class<*>): Set<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.name == "Companion" }
            .map { it.name }
            .toSet()

    @Test
    fun `reader style JSON covers every data class field`() {
        assertEquals(dataFields(ReaderStyle::class.java), styleToJson(ReaderStyle.Default).keys)
    }

    @Test
    fun `manga style JSON covers every data class field`() {
        assertEquals(
            dataFields(MangaReaderStyle::class.java),
            mangaStyleToJson(MangaReaderStyle.Default).keys,
        )
    }

    @Test
    fun `fully populated reader style round-trips`() {
        val style = ReaderStyle(
            fontFamily = "sys:Test",
            customFontPath = "/fonts/test.ttf",
            customFontName = "Test",
            fontSizeSp = 21,
            lineHeight = 1.6f,
            paragraphSpacingDp = 12,
            marginStartDp = 11,
            marginEndDp = 12,
            marginTopDp = 3,
            marginBottomDp = 4,
            letterSpacingEm = 0.1f,
            justify = true,
            hyphenation = true,
            paragraphIndent = true,
            firstLetterBold = true,
            splitSentences = true,
            bold = true,
            italic = true,
            underline = true,
            shadow = true,
            textColor = 0xFF112233,
            backgroundId = "dark",
            backgroundImagePath = "/backgrounds/test.jpg",
            backgroundImageName = "test",
            keepScreenOn = true,
            fullscreen = false,
            selectableText = false,
            doubleTapMenu = true,
            autoScrollSpeed = 90,
            manualHighlight = true,
            ttsHighlight = true,
            highlightColor = 0xFF00FF00,
        )

        assertEquals(style, styleFromJson(styleToJson(style)))
    }

    @Test
    fun `fully populated manga style round-trips`() {
        val style = MangaReaderStyle(
            mode = MangaReadingMode.Paged,
            direction = MangaReadingDirection.Rtl,
            tapZoneInvert = true,
            showPageCounter = false,
            webtoonPadding = MangaWebtoonPadding.Large,
            doubleTapMenu = true,
            brightness = 0.6f,
            colorFilter = MangaColorFilter.Sepia,
            pagedNavigation = MangaPagedNavigation.TapEdges,
            pageTransition = false,
            autoScrollSpeed = 135,
        )

        assertEquals(style, mangaStyleFromJson(mangaStyleToJson(style)))
    }
}
