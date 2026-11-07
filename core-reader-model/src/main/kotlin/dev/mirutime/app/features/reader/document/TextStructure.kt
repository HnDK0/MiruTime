package dev.mirutime.app.features.reader.document

/**
 * Structural line classifiers shared by [SentenceSplitter] and the speech
 * filter. One source of truth: the splitter decides whether to keep a paragraph
 * whole, the TTS decides whether a block is pure noise, but both must agree on
 * what a divider, a status line, a log line or decoration is.
 */
internal object TextStructure {
    /** litRPG status line "key: value". */
    private val STATUS_KEY_VALUE_LINE =
        Regex("^\\s*[«\"“\"\\[(【]*[\\p{L}\\p{N}_ \\-]{1,30}[:：]\\s*\\S.*")

    /** Same shape, bounded key/value and a printable value used to skip speech. */
    private val STRICT_STATUS_LINE =
        Regex("""^\s*[\[\(«"']?([\p{L}\p{N}_ /\-]{1,24})[:：]\s*(.*?)\s*$""")

    /** litRPG section divider line: ***, ===, ---, ──, ━━, ══, ╔, ╚, ║, ┌, └. */
    private val STATUS_DIVIDER_LINE =
        Regex("^\\s*(?:\\*{3,}|={3,}|-{3,}|─{2,}|━{2,}|═{2,}|╔|╚|║|┌|└)\\s*$")

    /** Line of one non-letter/digit char repeated >= 4 times — a decorative divider. */
    private val REPEATED_CHAR_DIVIDER = Regex("^(.)\\1{3,}$")

    /**
     * Chat/system log line prefix: a timestamp, a bracketed tag, an angle
     * handle or a bold handle. A bare "Name:" multi-line block is caught by
     * the status classifier instead. Both ASCII and converted angle handles
     * are accepted: the parser converts fresh text, but cached translations
     * and provider output may still use either form.
     */
    private val LOG_PREFIX = Regex(
        """^\s*(?:\[\d{1,2}:\d{2}(?::\d{2})?]|\[[^\]]{1,24}]|<[^<>]{1,32}>|⟨[^⟨⟩]{1,32}⟩|\*\*[^*]{1,32}\*\*)\s*\S"""
    )

    /** Box Drawing + Block Elements: chars that form a frame around a block. */
    val FRAME_RANGES = listOf(
        0x2500..0x257F,
        0x2580..0x259F,
    )

    /**
     * Unicode blocks that signal ornamentation: frames plus geometric shapes,
     * misc symbols, dingbats and emoji.
     */
    val DECORATIVE_RANGES = FRAME_RANGES + listOf(
        0x25A0..0x25FF, // Geometric Shapes
        0x2600..0x26FF, // Miscellaneous Symbols
        0x2700..0x27BF, // Dingbats
        0x1F300..0x1FAFF, // Emoji & Pictographs
    )

    /** Decoration glyphs NoveLA strips from the edges of a spoken line. */
    private const val EDGE_DECORATION = "-=*_~+#·•°"

    /** Max strict status value length; longer values are prose, not a panel. */
    private const val STRICT_VALUE_MAX = 40

    /** A single status value: digits with optional short labels per "/" part. */
    private val STAT_VALUE = Regex(
        """^\s*(?:\p{L}{1,8}\s*:\s*)?\d[\d ]*(?:\s*/\s*(?:\p{L}{1,8}\s*:\s*)?\d[\d ]*)*\s*$""",
    )

    private val STRICT_VALUE_FORBIDDEN = setOf(',', ';', ':', '!', '?', '…', '。', '！', '？')

    fun isStatusDividerLine(line: String): Boolean = STATUS_DIVIDER_LINE.matches(line)

    fun isRepeatedCharDivider(line: String): Boolean = REPEATED_CHAR_DIVIDER.matches(line.trim())

    fun isLogPrefixLine(line: String): Boolean = LOG_PREFIX.containsMatchIn(line)

    /** Splitter policy: any non-empty "key: value" line, current behaviour. */
    fun isStatusKeyValueLine(line: String): Boolean = STATUS_KEY_VALUE_LINE.matches(line)

    /** Digits with optional short labels, separated by "/": "120 / MP: 30". */
    fun isStatValue(value: String): Boolean =
        value.length <= STRICT_VALUE_MAX && STAT_VALUE.matches(value)

    /**
     * Value of a bounded strict status line, or null. Strict lines allow plain
     * letters/digits and the symbols status panels actually use, and reject
     * sentence punctuation so prose like "Он сказал: беги, глупец" never turns
     * into a skipped panel.
     */
    fun strictStatusValue(line: String): String? {
        val match = STRICT_STATUS_LINE.matchEntire(line) ?: return null
        val value = match.groupValues[2].trim()
        if (value.isEmpty() || value.length > STRICT_VALUE_MAX) return null
        // A second colon is allowed only in a stat pair like "HP: 120 / MP: 30".
        val allowColon = value.contains('/')
        if (value.any { it in STRICT_VALUE_FORBIDDEN && !(allowColon && it == ':') }) return null
        if (value.any {
                !(it.isLetterOrDigit() || it == ' ' || it in "/%+-." || (allowColon && it == ':'))
            }
        ) {
            return null
        }
        return value
    }

    fun isDecorationCodePoint(codePoint: Int): Boolean =
        DECORATIVE_RANGES.any { codePoint in it }

    fun isFrameCodePoint(codePoint: Int): Boolean =
        FRAME_RANGES.any { codePoint in it }

    fun isEdgeDecoration(ch: Char): Boolean =
        ch in EDGE_DECORATION || isFrameCodePoint(ch.code)

    /** True when every non-space char is decoration (dividers, frames, emoji runs). */
    fun isDecorationOnly(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return trimmed.codePoints().allMatch { codePoint ->
            codePoint == ' '.code || Character.isWhitespace(codePoint) ||
                isDecorationCodePoint(codePoint) || isEdgeDecoration(Character.toChars(codePoint)[0])
        }
    }
}
