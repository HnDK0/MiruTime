package dev.mirutime.app.features.reader.document

/** Why a block is not worth speaking. Diagnostic surface for tests and logs. */
enum class NoiseReason {
    EMPTY,
    DECORATION,
    DIVIDER,
    STATUS_PANEL,
    CHAT_LOG,
    STATUS_LINE,
}

/**
 * Text-only speech filter: decides which blocks are pure noise (dividers,
 * frames, litRPG status panels, chat logs) and masks decoration/whitespace so
 * the engine never speaks symbols.
 *
 * [mask] keeps the original length: removed chars become spaces. Chunk offsets
 * therefore still point at the real characters in [ReaderBlock.original] (or in
 * the translated string), so TTS highlighting and stored cursors stay valid.
 */
object SpeechText {

    /** Null when the block carries something worth speaking. */
    fun noiseReason(text: String): NoiseReason? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return NoiseReason.EMPTY
        if (lines.all { TextStructure.isDecorationOnly(it) }) return NoiseReason.DECORATION
        if (lines.all { TextStructure.isStatusDividerLine(it) || TextStructure.isRepeatedCharDivider(it) }) {
            return NoiseReason.DIVIDER
        }
        val contents = lines.map(::stripEdges).filter { it.isNotEmpty() }
        if (lines.size >= 2 && contents.isNotEmpty()) {
            val values = contents.map { TextStructure.strictStatusValue(it) }
            val hasNumber = values.any { value ->
                value != null && value.any { it.isDigit() || it == '/' || it == '%' }
            }
            if (values.all { it != null } && hasNumber) return NoiseReason.STATUS_PANEL
            if (contents.all { TextStructure.isLogPrefixLine(it) }) return NoiseReason.CHAT_LOG
        }
        if (lines.size == 1) {
            val value = contents.singleOrNull()
                ?.let { TextStructure.strictStatusValue(it) }
                ?: return null
            if (TextStructure.isStatValue(value)) return NoiseReason.STATUS_LINE
        }
        return null
    }

    fun isNoiseBlock(text: String): Boolean = noiseReason(text) != null

    /** Same-length copy with whitespace controls and edge decoration blanked. */
    fun mask(text: String): String {
        if (text.isEmpty()) return text
        val chars = text.toCharArray()
        var start = 0
        while (start <= chars.size) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) chars.size else newline
            stripEdgeRun(chars, start, end, leading = true)
            stripEdgeRun(chars, start, end, leading = false)
            start = end + 1
        }
        for (index in chars.indices) {
            if (isWhitespaceControl(chars[index])) chars[index] = ' '
        }
        return String(chars)
    }

    private fun stripEdges(line: String): String {
        val chars = line.toCharArray()
        stripEdgeRun(chars, 0, chars.size, leading = true)
        stripEdgeRun(chars, 0, chars.size, leading = false)
        return String(chars).trim()
    }

    private fun stripEdgeRun(chars: CharArray, from: Int, to: Int, leading: Boolean) {
        var start = from
        var end = to
        if (leading) {
            while (start < end && chars[start].isWhitespace()) start++
            var runEnd = start
            while (runEnd < end && TextStructure.isEdgeDecoration(chars[runEnd])) runEnd++
            if (isDecorationRun(chars, start, runEnd)) {
                for (index in start until runEnd) chars[index] = ' '
            }
        } else {
            while (end > start && chars[end - 1].isWhitespace()) end--
            var runStart = end
            while (runStart > start && TextStructure.isEdgeDecoration(chars[runStart - 1])) runStart--
            if (isDecorationRun(chars, runStart, end)) {
                for (index in runStart until end) chars[index] = ' '
            }
        }
    }

    /** Three or more decoration glyphs, or a single frame char wrapping a line. */
    private fun isDecorationRun(chars: CharArray, from: Int, to: Int): Boolean {
        val length = to - from
        if (length >= 3) return true
        return length == 1 && TextStructure.isFrameCodePoint(chars[from].code)
    }

    private fun isWhitespaceControl(ch: Char): Boolean = when (ch) {
        '\r', '\n', '\t', '\u00A0', '\u200B', '\u200C', '\u200D', '\uFEFF' -> true
        else -> false
    }
}
