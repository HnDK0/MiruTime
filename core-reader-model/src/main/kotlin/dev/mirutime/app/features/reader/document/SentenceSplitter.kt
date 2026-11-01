package dev.mirutime.app.features.reader.document

/**
 * Rule-based paragraph-to-sentences splitter, ported from NoveLA.
 *
 * Pure Kotlin stdlib, no dependencies. A paragraph is only split when it is
 * long enough and contains at least one valid sentence boundary; dialogue,
 * status blocks (litRPG), dash-dialogue paragraphs and paragraphs with
 * decorative symbols (frames, emoji, ornamentation) are always kept whole.
 *
 * [spans] returns the same segments as NoveLA's `splitParagraph`, but as
 * offsets into [text] (leading/trailing whitespace of each sentence excluded).
 */
object SentenceSplitter {
    // Threshold lowered from 250 to 100 (data-driven, NoveLA issue-209):
    // 165 of 1004 web-novel paragraphs in the 100-249 band split into >=2
    // real sentences; 250 left those whole.
    private const val MIN_PARAGRAPH_LENGTH = 100
    private const val SHORT_STATUS_LINE_LENGTH = 40
    private const val EM_DASH = '\u2014'
    private const val EN_DASH = '\u2013'

    /** Terminators that require boundary validation: . ! ? */
    private val BASIC_TERMINATORS = setOf('.', '!', '?')

    /**
     * Script terminators: bypass the a-g validation rules, but the
     * quote/bracket stack gate still applies (a script terminator inside
     * quotes/brackets is not a boundary).
     */
    private val SCRIPT_TERMINATORS = setOf(
        // CJK / Japanese / Chinese / Korean
        '\u3002', // 。
        '\uFF01', // ！
        '\uFF1F', // ？
        '\uFF0E', // ．
        // Arabic / Urdu
        '\u061F', // ؟
        '\u06D4', // ۔
        // Devanagari
        '\u0964', // ।
        '\u0965', // ॥
        // Armenian
        '\u0589', // ։
        // Burmese
        '\u104A', // ။
        '\u104B', // ၌
        // Ethiopic
        '\u1362', // ።
        '\u1367', // ፧
        '\u1368'  // ፨
    )

    private val ALL_TERMINATORS = BASIC_TERMINATORS + SCRIPT_TERMINATORS

    /** Quote pairs: closing char -> its opening char. */
    private val CLOSING_TO_OPENING_QUOTES = mapOf(
        '"' to '"',                             // "..."
        '»' to '«',                             // «...»
        '\u201C' to '\u201E',                   // "..." closes „...
        '\u201D' to '\u201C',                   // "... closes ...
        '\u2019' to '\u2018',                   // '... closes '...
        '\u300D' to '\u300C',                   // 」...「
        '\u300F' to '\u300E',                   // 』...『
        '\u300B' to '\u300A',                   // 》...《
        '\u203A' to '\u2039'                    // ›...‹
    )

    /** Opening quotes. A char may be both an opening and a closing quote (" and “). */
    private val OPENING_QUOTES = setOf(
        '"',
        '«',
        '\u201E', // „
        '\u201C', // “
        '\u2018', // ‘
        '\u300C', // 「
        '\u300E', // 『
        '\u300A', // 《
        '\u2039'  // ‹
    )

    private val CLOSING_TO_OPENING_BRACKETS = mapOf(
        ')' to '(', ']' to '[', '}' to '{',
        '\u27E9' to '\u27E8', // ⟩...⟨
        '\u3015' to '\u3014', // 〕...〔
        '\u3011' to '\u3010'  // 】...【
    )

    private val OPENING_BRACKETS = setOf('(', '[', '{', '\u27E8', '\u3014', '\u3010')

    /** Known abbreviations (lowercase, without the trailing dot). */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "no", "vs", "etc", "fig", "vol",
        "pp", "ed", "inc", "ltd", "co", "corp", "approx", "capt", "cf", "col", "gen", "mag",
        "med", "mt", "nat", "nr", "phil", "rer", "sci", "sgt", "univ", "zb", "dh", "ua", "usw",
        "bspw", "ca", "bzw", "т", "г", "гг", "гл", "стр", "см", "рис", "табл", "ул", "руб",
        "проф", "др", "до", "н", "э", "янв", "фев", "мар", "апр", "авг", "сен", "окт", "ноя", "дек",
        "д", "ч", "с", "п", "тыс", "млн", "им", "км", "z"
    )

    /** litRPG status line "key: value". */
    private val STATUS_KEY_VALUE_LINE = Regex("^\\s*[«\"“\"\\[(【]*[\\p{L}\\p{N}_ \\-]{1,30}[:：]\\s*\\S.*")

    /** litRPG section divider line: ***, ===, ---, ──, ━━, ══, ╔, ╚, ║, ┌, └. */
    private val STATUS_DIVIDER_LINE = Regex("^\\s*(?:\\*{3,}|={3,}|-{3,}|─{2,}|━{2,}|═{2,}|╔|╚|║|┌|└)\\s*$")

    /** Punctuation that continues a sentence rather than ending it ("и т. п., потому..."). */
    private val SENTENCE_CONTINUATION = setOf(',', ';', ':')

    /** Line of one non-letter/digit char repeated >= 4 times — a decorative divider. */
    private val REPEATED_CHAR_DIVIDER = Regex("^(.)\\1{3,}$")

    /**
     * Unicode blocks that signal non-prose ornamentation: box-drawing frames,
     * block elements, geometric shapes, misc symbols, dingbats and emoji.
     * Any paragraph containing one of these is kept whole — rule-based
     * splitting would tear apart visual structure (frames, status boxes, dividers).
     */
    private val DECORATIVE_RANGES = listOf(
        0x2500..0x257F, // Box Drawing
        0x2580..0x259F, // Block Elements
        0x25A0..0x25FF, // Geometric Shapes
        0x2600..0x26FF, // Miscellaneous Symbols
        0x2700..0x27BF, // Dingbats
        0x1F300..0x1FAFF // Emoji & Pictographs
    )

    /**
     * Splits [text] into sentence spans using NoveLA's rules: paragraphs
     * shorter than 100 chars, status blocks, dash dialogue, decorative
     * paragraphs and paragraphs without a valid boundary stay one span.
     */
    fun spans(text: String): List<SentenceSpan> {
        if (text.isEmpty()) return emptyList()
        if (text.length < MIN_PARAGRAPH_LENGTH) return listOf(SentenceSpan(0, text.length))
        if (isStatusBlock(text) || isDashDialogue(text) || hasDecorationSymbols(text)) {
            return listOf(SentenceSpan(0, text.length))
        }
        val boundaries = findValidBoundaries(text)
        if (boundaries.isEmpty()) return listOf(SentenceSpan(0, text.length))

        val spans = mutableListOf<SentenceSpan>()
        var start = 0
        for (boundary in boundaries) {
            addTrimmedSpan(text, start, boundary + 1, spans)
            start = boundary + 1
        }
        addTrimmedSpan(text, start, text.length, spans)
        return spans.ifEmpty { listOf(SentenceSpan(0, text.length)) }
    }

    /** Mirrors NoveLA's per-segment `.trim()` without losing offsets. */
    private fun addTrimmedSpan(text: String, from: Int, to: Int, out: MutableList<SentenceSpan>) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) out += SentenceSpan(start, end)
    }

    /** True when [text] contains any framing/decoration symbol or emoji. */
    private fun hasDecorationSymbols(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val cp = if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                Character.toCodePoint(ch, text[i + 1])
            } else {
                ch.code
            }
            if (DECORATIVE_RANGES.any { cp in it }) return true
            i++
        }
        return false
    }

    /**
     * Scans [text] left to right with a quote/bracket stack. A terminator is a
     * split candidate only at stack depth 0; script terminators always yield a
     * boundary, basic ones ( . ! ? ) are validated.
     */
    private fun findValidBoundaries(text: String): List<Int> {
        val stack = ArrayDeque<Char>()
        val boundaries = mutableListOf<Int>()

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                CLOSING_TO_OPENING_QUOTES.containsKey(ch) -> {
                    val opener = CLOSING_TO_OPENING_QUOTES.getValue(ch)
                    if (stack.isNotEmpty() && stack.last() == opener) {
                        stack.removeLast()
                        // A terminator directly before a closing quote-pair is
                        // attached to it: the boundary is placed right after the
                        // closing quote ("大丈夫だ。」 そして去った。" → 2 segments).
                        val prev = text.getOrNull(i - 1)
                        if (stack.isEmpty() && prev != null && prev in ALL_TERMINATORS &&
                            i + 1 < text.length &&
                            isBoundaryValid(text, prev, i - 1, i + 1)
                        ) {
                            boundaries.add(i)
                        }
                    } else if (ch in OPENING_QUOTES) {
                        stack.addLast(ch)
                    }
                }
                ch in OPENING_QUOTES -> stack.addLast(ch)
                CLOSING_TO_OPENING_BRACKETS.containsKey(ch) -> {
                    val opener = CLOSING_TO_OPENING_BRACKETS.getValue(ch)
                    if (stack.isNotEmpty() && stack.last() == opener) stack.removeLast()
                }
                ch in OPENING_BRACKETS -> stack.addLast(ch)
                ch in ALL_TERMINATORS -> {
                    // Adjacent punctuation runs ("?!", "!?", "....") belong together: the
                    // boundary is placed on the last terminator only, otherwise empty
                    // "!" / "." / "?" segments are produced.
                    val rightAfter = text.getOrNull(i + 1)
                    if (rightAfter == null || rightAfter !in ALL_TERMINATORS) {
                        // Only at quote/bracket stack depth 0 can a terminator become
                        // a sentence boundary; text inside quotes is never cut.
                        if (stack.isEmpty() && isBoundaryValid(text, ch, i, i + 1)) boundaries.add(i)
                    }
                }
            }
            i++
        }
        return boundaries
    }

    /**
     * Validates a terminator at [terminatorPos] (basic . ! ? only; script
     * terminators are always valid). [nextFrom] is where the "next significant
     * char" scan starts — right after the terminator, or after a closing quote
     * the terminator was attached to.
     */
    private fun isBoundaryValid(text: String, terminator: Char, terminatorPos: Int, nextFrom: Int): Boolean {
        // Script-specific terminators (。！？．؟۔।॥։။၌።፧፨) always yield a boundary.
        if (terminator !in BASIC_TERMINATORS) return true

        val next = nextSignificantChar(text, nextFrom)
        val sigAfter = nextSignificantChar(text, terminatorPos + 1)
        val after = text.getOrNull(terminatorPos + 1)
        val before = text.getOrNull(terminatorPos - 1)

        // a) A letter immediately after (no space): "т.д.", "z.B.", "И.В.".
        if (after != null && isLatinCyrillicLetter(after)) return false

        // b) A digit as the next significant char (spaces allowed): "No. 1", "3.14", "ч. 2".
        if (sigAfter != null && sigAfter.isDigit()) return false
        if (before != null && before.isDigit() && after != null && after.isDigit()) return false

        // c) The last Latin/Cyrillic word before the terminator is an abbreviation: "Dr.", "St.", "г.".
        // A single-letter member of a dotted chain ("т.д." -> "д") does not count — the chain's
        // final dot can still end a sentence ("...и т.д. И ушёл...").
        val lastWord = lastLatinWordBefore(text, terminatorPos)
        if (lastWord != null && lastWord in ABBREVIATIONS &&
            text.getOrNull(terminatorPos - lastWord.length - 1) != '.'
        ) return false

        // d) A single uppercase initial followed by an uppercase word: "И. Иванов".
        if (before != null && isLatinCyrillicLetter(before) && before.isUpperCase() &&
            isSingleLetterWord(text, terminatorPos - 1) &&
            next != null && next.isLetter() && next.isUpperCase()
        ) return false

        // e) The next significant word starts with a lowercase letter: "Что? спросил он".
        if (next != null && next.isLetter() && next.isLowerCase()) return false

        // f) A comma, semicolon or colon right after continues the sentence: "и т. п., потому...".
        if (sigAfter != null && sigAfter in SENTENCE_CONTINUATION) return false

        // g) A dash right after is a dialogue attribution: "«Беги!» — крикнул он".
        if (next != null && (next == EM_DASH || next == EN_DASH)) return false

        return true
    }

    private fun nextSignificantChar(text: String, from: Int): Char? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length) text[i] else null
    }

    /** The last contiguous run of Latin/Cyrillic letters ending right before the terminator. */
    private fun lastLatinWordBefore(text: String, terminatorPos: Int): String? {
        val end = terminatorPos - 1
        if (end < 0 || !isLatinCyrillicLetter(text[end])) return null
        var start = end
        while (start >= 0 && isLatinCyrillicLetter(text[start])) start--
        return text.substring(start + 1, end + 1).lowercase()
    }

    /** True when the letter at [letterPos] forms a single-letter word. */
    private fun isSingleLetterWord(text: String, letterPos: Int): Boolean {
        val before = text.getOrNull(letterPos - 1)
        return before == null || !isLatinCyrillicLetter(before)
    }

    /** Latin (incl. extended) or Cyrillic letter: U+0041..U+024F or U+0400..U+052F. */
    private fun isLatinCyrillicLetter(c: Char): Boolean {
        if (!c.isLetter()) return false
        val code = c.code
        return code in 0x0041..0x024F || code in 0x0400..0x052F
    }

    /**
     * True when the paragraph looks like a litRPG status block and must be kept
     * whole: >= 2 "key: value" lines, or any divider line, or >= 2 short lines
     * without any terminator.
     */
    private fun isStatusBlock(paragraph: String): Boolean {
        var keyValueLines = 0
        var shortNoTerminatorLines = 0
        for (line in paragraph.split('\n')) {
            if (STATUS_KEY_VALUE_LINE.matches(line)) keyValueLines++
            if (STATUS_DIVIDER_LINE.matches(line)) return true
            if (REPEATED_CHAR_DIVIDER.matches(line.trim())) return true
            if (line.length < SHORT_STATUS_LINE_LENGTH && line.none { it in ALL_TERMINATORS }) {
                shortNoTerminatorLines++
            }
        }
        return keyValueLines >= 2 || shortNoTerminatorLines >= 2
    }

    private fun isDashDialogue(paragraph: String): Boolean {
        val trimmed = paragraph.trimStart()
        return trimmed.startsWith(EM_DASH) || trimmed.startsWith(EN_DASH)
    }
}
