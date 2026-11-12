package dev.mirutime.feature.reader

import androidx.compose.runtime.Immutable

@Immutable
data class ReaderStyle(
    val fontFamily: String = "serif",   // serif | sans-serif | monospace | sys:<family>
    val customFontPath: String? = null,
    val customFontName: String? = null,
    val fontSizeSp: Int = 14,
    val lineHeight: Float = 1.35f,
    val paragraphSpacingDp: Int = 16,
    val marginStartDp: Int = 16,
    val marginEndDp: Int = 16,
    val marginTopDp: Int = 0,
    val marginBottomDp: Int = 0,
    val letterSpacingEm: Float = 0f,
    val justify: Boolean = false,
    val hyphenation: Boolean = false,
    val paragraphIndent: Boolean = false,
    val firstLetterBold: Boolean = false,
    val splitSentences: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val shadow: Boolean = false,
    val textColor: Long? = null,        // null = auto
    val backgroundId: String = "auto",
    val backgroundImagePath: String? = null,
    val backgroundImageName: String? = null,
    val keepScreenOn: Boolean = false,
    val fullscreen: Boolean = true,
    val selectableText: Boolean = true,
    val doubleTapMenu: Boolean = false,
    val autoScrollSpeed: Int = 40,
    val manualHighlight: Boolean = false,
    val ttsHighlight: Boolean = false,
    val highlightColor: Long = 0xFFFF6D00,
) {
    fun sanitized(): ReaderStyle = copy(
        customFontPath = customFontPath?.takeIf { it.isNotBlank() },
        customFontName = customFontName?.takeIf { it.isNotBlank() },
        backgroundImagePath = backgroundImagePath?.takeIf { it.isNotBlank() },
        backgroundImageName = backgroundImageName?.takeIf { it.isNotBlank() },
        fontSizeSp = fontSizeSp.coerceIn(8, 32),
        lineHeight = lineHeight.coerceIn(1.0f, 2.5f),
        paragraphSpacingDp = paragraphSpacingDp.coerceIn(0, 40),
        marginStartDp = marginStartDp.coerceIn(0, 100),
        marginEndDp = marginEndDp.coerceIn(0, 100),
        marginTopDp = marginTopDp.coerceIn(0, 100),
        marginBottomDp = marginBottomDp.coerceIn(0, 100),
        letterSpacingEm = letterSpacingEm.coerceIn(0f, 0.3f),
        autoScrollSpeed = autoScrollSpeed.coerceIn(
            Autoscroll.MinSpeed,
            Autoscroll.MaxSpeed,
        ),
    )

    fun effectiveTextColor(): Long = textColor ?: ReaderPalette.background(backgroundId).textArgb

    fun letterSpacingSp(): Float = fontSizeSp * letterSpacingEm

    companion object {
        val Default = ReaderStyle(
            fontSizeSp = 17,
            lineHeight = 1.55f,
            marginStartDp = 20,
            marginEndDp = 20,
        )
    }
}

@Immutable
data class ReaderBackground(
    val id: String,
    val backgroundArgb: Long,
    val textArgb: Long,
)

object ReaderPalette {
    val backgrounds = listOf(
        ReaderBackground("auto", 0xFFFFFFFF, 0xFF333333),
        ReaderBackground("paper", 0xFFF7F3E8, 0xFF2B2A26),
        ReaderBackground("cream", 0xFFFFF8E7, 0xFF3A3325),
        ReaderBackground("sepia", 0xFFF3E5CF, 0xFF4A3B2A),
        ReaderBackground("sand", 0xFFE8DCC8, 0xFF3B3327),
        ReaderBackground("gray", 0xFFE6E6E6, 0xFF262626),
        ReaderBackground("slate", 0xFF37474F, 0xFFECEFF1),
        ReaderBackground("dark", 0xFF1E1E1E, 0xFFE8E8E8),
        ReaderBackground("black", 0xFF000000, 0xFFDDDDDD),
        ReaderBackground("night", 0xFF121826, 0xFFD7DEEA),
        ReaderBackground("forest", 0xFF1B2A20, 0xFFDCE7DA),
        ReaderBackground("ocean", 0xFF102A33, 0xFFD6E9EE),
        ReaderBackground("rose", 0xFF2C1B1F, 0xFFF0DADD),
        ReaderBackground("lavender", 0xFF241E33, 0xFFE5DCF5),
    )

    val textColors = listOf(
        0xFF000000, 0xFF333333, 0xFF555555, 0xFFFFFFFF, 0xFFFFF5D6, 0xFFE53935,
        0xFFFB8C00, 0xFFFFC107, 0xFF43A047, 0xFF00897B, 0xFF1E88E5, 0xFF8E24AA,
    )

    val highlightColors = listOf(
        0xFFFF6D00, 0xFFFFD600, 0xFF00C853, 0xFF00B0FF, 0xFFAA00FF, 0xFFFF1744,
        0xFFFF9100, 0xFF76FF03, 0xFF1DE9B6, 0xFF651FFF, 0xFFFF4081, 0xFF9E9E9E,
    )

    fun background(id: String): ReaderBackground =
        backgrounds.firstOrNull { it.id == id } ?: backgrounds.first()
}
