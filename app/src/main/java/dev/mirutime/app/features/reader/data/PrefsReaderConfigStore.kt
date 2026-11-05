package dev.mirutime.app.features.reader.data

import android.content.Context
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.MangaReadingMode
import dev.mirutime.feature.reader.ReaderConfig
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.parseMangaColorFilter
import dev.mirutime.feature.reader.parseMangaPagedNavigation
import dev.mirutime.feature.reader.parseMangaReadingDirection
import dev.mirutime.feature.reader.parseMangaReadingMode
import dev.mirutime.feature.reader.parseMangaWebtoonPadding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class PrefsReaderConfigStore(
    context: Context,
    private val initialStyle: ReaderStyle = ReaderStyle.Default,
) : ReaderConfigStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    override val config: StateFlow<ReaderConfig> = _config.asStateFlow()

    private fun loadConfig(): ReaderConfig = ReaderConfig(
        mode = runCatching {
            ReaderMode.valueOf(prefs.getString(KEY_MODE, ReaderMode.Feed.name).orEmpty())
        }.getOrDefault(ReaderMode.Feed),
        style = prefs.getString(KEY_STYLE, null)
            ?.let { raw -> runCatching { styleFromJson(raw.toJsonObject()) }.getOrNull() }
            ?: initialStyle.sanitized(),
        mangaStyle = loadMangaStyle(),
    )

    /**
     * Webtoon is the standard mode. Installs that still carry the historical
     * Paged default are switched once; an explicit mode choice (a Save from the
     * settings sheet) is never overridden.
     */
    private fun loadMangaStyle(): MangaReaderStyle {
        val stored = prefs.getString(KEY_MANGA_STYLE, null)
            ?.let { raw -> runCatching { mangaStyleFromJson(raw.toJsonObject()) }.getOrNull() }
        val style = resolveMangaMode(
            stored = stored,
            explicitModeChoice = prefs.getBoolean(KEY_MANGA_MODE_EXPLICIT, false),
        )
        if (style != stored) {
            prefs.edit()
                .putString(KEY_MANGA_STYLE, mangaStyleToJson(style).toString())
                .apply()
        }
        return style
    }

    /** Re-reads SharedPreferences after a restore wrote them. */
    fun reload() {
        // A restored backup carries the user's own choice; never run the
        // one-time default switch on top of it.
        if (prefs.contains(KEY_MANGA_STYLE)) {
            prefs.edit().putBoolean(KEY_MANGA_MODE_EXPLICIT, true).apply()
        }
        _config.value = loadConfig()
    }

    override fun setMode(mode: ReaderMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _config.update { it.copy(mode = mode) }
    }

    override fun setStyle(style: ReaderStyle) {
        val sanitized = style.sanitized()
        prefs.edit()
            .putString(KEY_STYLE, styleToJson(sanitized).toString())
            .apply()
        _config.update { it.copy(style = sanitized) }
    }

    override fun setMangaStyle(style: MangaReaderStyle) {
        val sanitized = style.sanitized()
        prefs.edit()
            .putString(KEY_MANGA_STYLE, mangaStyleToJson(sanitized).toString())
            // The user chose this style; the one-time default switch must not
            // touch it again.
            .putBoolean(KEY_MANGA_MODE_EXPLICIT, true)
            .apply()
        _config.update { it.copy(mangaStyle = sanitized) }
    }

    internal companion object {
        const val PREFS_NAME = "reader_config"
        const val KEY_MODE = "mode"
        const val KEY_STYLE = "style"
        const val KEY_MANGA_STYLE = "manga_style"

        // Local only (never restored from a backup): records that the user
        // picked a manga mode themselves.
        const val KEY_MANGA_MODE_EXPLICIT = "manga_mode_explicit"
        val BackupKeys = setOf(KEY_MODE, KEY_STYLE, KEY_MANGA_STYLE)
    }
}

/**
 * Webtoon is the one standard mode. A stored style without an explicit choice
 * is migrated once (only the mode changes, every other preference survives).
 */
internal fun resolveMangaMode(
    stored: MangaReaderStyle?,
    explicitModeChoice: Boolean,
): MangaReaderStyle = when {
    stored == null -> MangaReaderStyle.Default
    !explicitModeChoice && stored.mode != MangaReadingMode.Webtoon ->
        stored.copy(mode = MangaReadingMode.Webtoon)
    else -> stored
}

// kotlinx JSON (already used by backups) keeps the codec runnable in JVM unit
// tests; org.json only exists as Android stubs there. The stored field names
// are unchanged, so old preferences and backups keep loading.
private fun String.toJsonObject(): JsonObject =
    Json.parseToJsonElement(this).jsonObject

internal fun mangaStyleToJson(style: MangaReaderStyle): JsonObject = buildJsonObject {
    put("mode", style.mode.name)
    put("direction", style.direction.name)
    put("tapZoneInvert", style.tapZoneInvert)
    put("showPageCounter", style.showPageCounter)
    put("webtoonPadding", style.webtoonPadding.name)
    put("doubleTapMenu", style.doubleTapMenu)
    put("brightness", style.brightness)
    put("colorFilter", style.colorFilter.name)
    put("pagedNavigation", style.pagedNavigation.name)
    put("pageTransition", style.pageTransition)
}

internal fun mangaStyleFromJson(json: JsonObject): MangaReaderStyle {
    val defaults = MangaReaderStyle.Default
    return MangaReaderStyle(
        // A style saved before modes existed used the old Paged default; the
        // one-time mode switch in loadMangaStyle moves it to Webtoon.
        mode = parseMangaReadingMode(json.str("mode", defaults.mode.name)),
        direction = parseMangaReadingDirection(
            json.str("direction", defaults.direction.name),
        ),
        tapZoneInvert = json.bool("tapZoneInvert", defaults.tapZoneInvert),
        showPageCounter = json.bool("showPageCounter", defaults.showPageCounter),
        webtoonPadding = parseMangaWebtoonPadding(
            json.str("webtoonPadding", defaults.webtoonPadding.name),
        ),
        doubleTapMenu = json.bool("doubleTapMenu", defaults.doubleTapMenu),
        brightness = json.float("brightness", defaults.brightness),
        colorFilter = parseMangaColorFilter(
            json.str("colorFilter", defaults.colorFilter.name),
        ),
        pagedNavigation = parseMangaPagedNavigation(
            json.str("pagedNavigation", defaults.pagedNavigation.name),
        ),
        pageTransition = json.bool("pageTransition", defaults.pageTransition),
    ).sanitized()
}

internal fun styleToJson(style: ReaderStyle): JsonObject = buildJsonObject {
    put("fontFamily", style.fontFamily)
    put("customFontPath", style.customFontPath)
    put("customFontName", style.customFontName)
    put("fontSizeSp", style.fontSizeSp)
    put("lineHeight", style.lineHeight)
    put("paragraphSpacingDp", style.paragraphSpacingDp)
    put("marginStartDp", style.marginStartDp)
    put("marginEndDp", style.marginEndDp)
    put("marginTopDp", style.marginTopDp)
    put("marginBottomDp", style.marginBottomDp)
    put("letterSpacingEm", style.letterSpacingEm)
    put("justify", style.justify)
    put("hyphenation", style.hyphenation)
    put("paragraphIndent", style.paragraphIndent)
    put("firstLetterBold", style.firstLetterBold)
    put("splitSentences", style.splitSentences)
    put("bold", style.bold)
    put("italic", style.italic)
    put("underline", style.underline)
    put("shadow", style.shadow)
    put("textColor", style.textColor)
    put("backgroundId", style.backgroundId)
    put("backgroundImagePath", style.backgroundImagePath)
    put("backgroundImageName", style.backgroundImageName)
    put("keepScreenOn", style.keepScreenOn)
    put("fullscreen", style.fullscreen)
    put("selectableText", style.selectableText)
    put("doubleTapMenu", style.doubleTapMenu)
    put("autoScrollSpeed", style.autoScrollSpeed)
    put("manualHighlight", style.manualHighlight)
    put("ttsHighlight", style.ttsHighlight)
    put("highlightColor", style.highlightColor)
}

// 8 dp was the old default; migrate it so existing readers get a visible
// paragraph gap instead of keeping the almost-flush legacy spacing.
private const val LEGACY_PARAGRAPH_SPACING_DP = 8

internal fun styleFromJson(json: JsonObject): ReaderStyle {
    val defaults = ReaderStyle.Default
    val paragraphSpacing = json.int("paragraphSpacingDp", defaults.paragraphSpacingDp)
    return ReaderStyle(
        fontFamily = json.str("fontFamily", defaults.fontFamily),
        customFontPath = json.stringOrNull("customFontPath"),
        customFontName = json.stringOrNull("customFontName"),
        fontSizeSp = json.int("fontSizeSp", defaults.fontSizeSp),
        lineHeight = json.float("lineHeight", defaults.lineHeight),
        paragraphSpacingDp = if (paragraphSpacing == LEGACY_PARAGRAPH_SPACING_DP) {
            defaults.paragraphSpacingDp
        } else {
            paragraphSpacing
        },
        marginStartDp = json.int("marginStartDp", defaults.marginStartDp),
        marginEndDp = json.int("marginEndDp", defaults.marginEndDp),
        marginTopDp = json.int("marginTopDp", defaults.marginTopDp),
        marginBottomDp = json.int("marginBottomDp", defaults.marginBottomDp),
        letterSpacingEm = json.float("letterSpacingEm", defaults.letterSpacingEm),
        justify = json.bool("justify", defaults.justify),
        hyphenation = json.bool("hyphenation", defaults.hyphenation),
        paragraphIndent = json.bool("paragraphIndent", defaults.paragraphIndent),
        firstLetterBold = json.bool("firstLetterBold", defaults.firstLetterBold),
        splitSentences = json.bool("splitSentences", defaults.splitSentences),
        bold = json.bool("bold", defaults.bold),
        italic = json.bool("italic", defaults.italic),
        underline = json.bool("underline", defaults.underline),
        shadow = json.bool("shadow", defaults.shadow),
        textColor = json.longOrNull("textColor"),
        backgroundId = json.str("backgroundId", defaults.backgroundId),
        backgroundImagePath = json.stringOrNull("backgroundImagePath"),
        backgroundImageName = json.stringOrNull("backgroundImageName"),
        keepScreenOn = json.bool("keepScreenOn", defaults.keepScreenOn),
        fullscreen = json.bool("fullscreen", defaults.fullscreen),
        selectableText = json.bool("selectableText", defaults.selectableText),
        doubleTapMenu = json.bool("doubleTapMenu", defaults.doubleTapMenu),
        autoScrollSpeed = json.int("autoScrollSpeed", defaults.autoScrollSpeed),
        manualHighlight = json.bool("manualHighlight", defaults.manualHighlight),
        ttsHighlight = json.bool("ttsHighlight", defaults.ttsHighlight),
        highlightColor = json.long("highlightColor", defaults.highlightColor),
    ).sanitized()
}

private fun JsonObject.primitive(key: String): JsonPrimitive? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element as? JsonPrimitive
}

private fun JsonObject.stringOrNull(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.str(key: String, default: String): String =
    primitive(key)?.content ?: default

private fun JsonObject.int(key: String, default: Int): Int =
    primitive(key)?.content?.toIntOrNull() ?: default

private fun JsonObject.long(key: String, default: Long): Long =
    primitive(key)?.content?.toLongOrNull() ?: default

private fun JsonObject.longOrNull(key: String): Long? =
    primitive(key)?.content?.toLongOrNull()

private fun JsonObject.float(key: String, default: Float): Float =
    primitive(key)?.content?.toFloatOrNull() ?: default

private fun JsonObject.bool(key: String, default: Boolean): Boolean =
    primitive(key)?.content?.toBooleanStrictOrNull() ?: default
