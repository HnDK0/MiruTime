package dev.mirutime.uikit

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Card titles are measured in the layout phase, so every cold formatting pass
// shapes text on the UI thread. A grid recycles compositions while scrolling and
// the same title reappears when scrolling back, so results are cached across
// cards: formatted lines by (title, lines, width) and token advances by (token,
// style). Main thread only — measure always runs there.

internal data class TitleStyleKey(
    val fontFamily: FontFamily?,
    val fontSize: Float,
    val fontWeight: FontWeight?,
    val letterSpacing: Float,
    val localeTags: String,
) {
    companion object {
        fun of(style: TextStyle): TitleStyleKey = TitleStyleKey(
            fontFamily = style.fontFamily,
            fontSize = style.fontSize.value,
            fontWeight = style.fontWeight,
            letterSpacing = style.letterSpacing.value,
            localeTags = style.localeList?.localeList?.joinToString(",") { it.toLanguageTag() } ?: "",
        )
    }
}

private data class FormatKey(
    val title: String,
    val maxLines: Int,
    val widthPx: Int,
    val style: TitleStyleKey,
)

private data class WidthKey(
    val token: String,
    val style: TitleStyleKey,
)

internal object CardTitleFormatCache {
    private const val MaxFormattedEntries = 512
    private const val MaxWidthEntries = 4096

    private val formatted = LruMap<FormatKey, String>(MaxFormattedEntries)
    private val widths = LruMap<WidthKey, Int>(MaxWidthEntries)

    fun format(
        title: String,
        maxLines: Int,
        widthPx: Int,
        style: TitleStyleKey,
        measure: (String) -> Int,
    ): String {
        val key = FormatKey(title = title, maxLines = maxLines, widthPx = widthPx, style = style)
        formatted[key]?.let { return it }

        val memoized = { text: String ->
            val widthKey = WidthKey(token = text, style = style)
            widths[widthKey] ?: measure(text).also { widths[widthKey] = it }
        }
        val result = formatCardTitleLines(
            title = title,
            maxLines = maxLines,
            maxWidthPx = widthPx,
            measureWidthPx = memoized,
        )
        formatted[key] = result
        return result
    }
}

private class LruMap<K, V>(private val maxSize: Int) :
    LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
}
