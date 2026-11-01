package dev.mirutime.feature.tts.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Same spoken-word background, mapped onto an arbitrary displayed string. */
fun highlightedRange(
    text: String,
    start: Int,
    end: Int,
    colorArgb: Long,
): AnnotatedString {
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    if (from >= to) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(background = Color(colorArgb).copy(alpha = 0.35f)), from, to)
    }
}
