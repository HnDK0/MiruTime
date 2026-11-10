package dev.mirutime.app.features.reader.document

/** Why [SentenceSplitter.spans] kept a paragraph whole. Diagnostic surface for tests. */
internal enum class SplitSkip {
    STATUS_BLOCK,
    LOG_BLOCK,
    FRAME_BLOCK,
    DASH_DIALOGUE,
    NO_BOUNDARY,
    TRIVIAL_BOUNDARY,
}

/** Spans for one paragraph plus the reason it stayed whole, if it did. */
internal data class SplitPlan(val spans: List<SentenceSpan>, val skipped: SplitSkip?)

/**
 * Rule-based paragraph-to-sentences splitter, ported from NoveLA.
 *
 * Pure Kotlin stdlib, no dependencies. A paragraph is split whenever it has at
 * least one valid sentence boundary; structural blocks stay whole on purpose:
 * litRPG status blocks, chat/system log lines, framed decoration lines,
 * dash-dialogue paragraphs, and paragraphs without a valid boundary.
 *
 * Boundary validation follows NoveLA's `splitParagraph`, but the policy is
 * local: there is no minimum-length gate, decoration is judged per line, and
 * chat/system log blocks stay whole. [spans] returns offsets into [text]
 * (leading/trailing whitespace of each sentence excluded).
 */
object SentenceSplitter {
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

    // Converted angle brackets (⟨⟩) are deliberately not brackets: they replace
    // literal ASCII < >, which were never brackets either, so sentence
    // boundaries must stay exactly where they were before the conversion.
    private val CLOSING_TO_OPENING_BRACKETS = mapOf(
        ')' to '(', ']' to '[', '}' to '{',
        '\u3015' to '\u3014', // 〕...〔
        '\u3011' to '\u3010'  // 】...【
    )

    private val OPENING_BRACKETS = setOf('(', '[', '{', '\u3014', '\u3010')

    /** Known abbreviations (lowercase, without the trailing dot). */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "no", "vs", "etc", "fig", "vol",
        "pp", "ed", "inc", "ltd", "co", "corp", "approx", "capt", "cf", "col", "gen", "mag",
        "med", "mt", "nat", "nr", "phil", "rer", "sci", "sgt", "univ", "zb", "dh", "ua", "usw",
        "bspw", "ca", "bzw", "т", "г", "гг", "гл", "стр", "см", "рис", "табл", "ул", "руб",
        "проф", "др", "до", "н", "э", "янв", "фев", "мар", "апр", "авг", "сен", "окт", "ноя", "дек",
        "д", "ч", "с", "п", "тыс", "млн", "им", "км", "z"
    )

    /** Punctuation that continues a sentence rather than ending it ("и т. п., потому..."). */
    private val SENTENCE_CONTINUATION = setOf(',', ';', ':')

    /**
     * Splits [text] into sentence spans. Blank text yields no spans; see
     * [SplitSkip] for the reasons a non-blank paragraph stays one span.
     */
    fun spans(text: String): List<SentenceSpan> = splitPlan(text).spans

    internal fun splitPlan(text: String): SplitPlan {
        if (text.isBlank()) return SplitPlan(emptyList(), null)
        if (isLogBlock(text)) return SplitPlan(listOf(SentenceSpan(0, text.length)), SplitSkip.LOG_BLOCK)
        if (isStatusBlock(text)) return SplitPlan(listOf(SentenceSpan(0, text.length)), SplitSkip.STATUS_BLOCK)
        if (isFrameBlock(text)) return SplitPlan(listOf(SentenceSpan(0, text.length)), SplitSkip.FRAME_BLOCK)
        if (isDashDialogue(text)) return SplitPlan(listOf(SentenceSpan(0, text.length)), SplitSkip.DASH_DIALOGUE)
        val boundaries = findValidBoundaries(text)
        if (boundaries.isEmpty()) {
            return SplitPlan(listOf(SentenceSpan(0, text.length)), SplitSkip.NO_BOUNDARY)
        }

        val spans = mutableListOf<SentenceSpan>()
        var start = 0
        for (boundary in boundaries) {
            addTrimmedSpan(text, start, boundary + 1, spans)
            start = boundary + 1
        }
        addTrimmedSpan(text, start, text.length, spans)
        if (spans.size <= 1) {
            return SplitPlan(
                spans.ifEmpty { listOf(SentenceSpan(0, text.length)) },
                SplitSkip.TRIVIAL_BOUNDARY,
            )
        }
        return SplitPlan(spans, null)
    }

    /** Mirrors NoveLA's per-segment `.trim()` without losing offsets. */
    private fun addTrimmedSpan(text: String, from: Int, to: Int, out: MutableList<SentenceSpan>) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) out += SentenceSpan(start, end)
    }

    /**
     * True when the whole paragraph is one quoted block and nothing but a
     * closing period/punctuation follows it (for example `«...» .`). Display
     * grouping keeps such a paragraph whole.
     */
    internal fun isQuoteWrappedParagraph(text: String): Boolean {
        val trimmedStart = text.indexOfFirst { !it.isWhitespace() }
        if (trimmedStart < 0) return false
        val closing = quotedBlockEnd(text, trimmedStart) ?: return false
        return text.substring(closing + 1).none { it.isLetterOrDigit() }
    }

    /**
     * A paragraph is a log/chat block when its only line starts with a log
     * prefix, or when at least two lines look like chat lines ("[12:34]",
     * "<Alice>", "**Alice**"). "Name: ..." lines are status-block territory.
     */
    private fun isLogBlock(text: String): Boolean {
        val lines = text.split('\n')
        if (lines.size == 1) return TextStructure.isLogPrefixLine(text)
        return lines.count { TextStructure.isLogPrefixLine(it) } >= 2
    }

    /**
     * A paragraph is framed when at least one line is made of decoration alone
     * or starts/ends with a box-drawing/block-element char.
     */
    private fun isFrameBlock(text: String): Boolean = text.lineSequence().any { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@any false
        val allDecoration = trimmed.codePoints().allMatch { TextStructure.isDecorationCodePoint(it) }
        allDecoration || TextStructure.isFrameCodePoint(trimmed.codePointAt(0)) ||
            TextStructure.isFrameCodePoint(trimmed.codePointBefore(trimmed.length))
    }

    /**
     * Scans [text] left to right with a quote/bracket stack. A terminator is a
     * split candidate only at stack depth 0; script terminators always yield a
     * boundary, basic ones ( . ! ? ) are validated.
     */
    private fun findValidBoundaries(text: String): List<Int> {
        // A paragraph that is one quoted block may contain several sentences
        // (long dialogue). An inline quote or a quote followed by an
        // attribution is part of a sentence and stays whole.
        // `».` is a quoted paragraph with the sentence period outside the quote.
        val quoteWrappedParagraph = isQuoteWrappedParagraph(text)

        // Quotes and brackets are tracked separately: a quoted paragraph may
        // contain several sentences, so a quote must not suppress boundaries.
        // Brackets still do (an aside is kept whole).
        val quotes = ArrayDeque<Char>()
        val brackets = ArrayDeque<Char>()
        val boundaries = mutableListOf<Int>()

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                CLOSING_TO_OPENING_QUOTES.containsKey(ch) -> {
                    val opener = CLOSING_TO_OPENING_QUOTES.getValue(ch)
                    if (quotes.isNotEmpty() && quotes.last() == opener) {
                        quotes.removeLast()
                        // A terminator directly before a closing quote-pair is
                        // attached to it: the boundary is placed right after the
                        // closing quote ("大丈夫だ。」 そして去った。" → 2 segments).
                        val prev = text.getOrNull(i - 1)
                        if (quotes.isEmpty() && brackets.isEmpty() && prev != null &&
                            prev in ALL_TERMINATORS &&
                            i + 1 < text.length &&
                            text[i + 1] !in ALL_TERMINATORS &&
                            isBoundaryValid(text, prev, i - 1, i + 1)
                        ) {
                            boundaries.add(i)
                        }
                    } else if (ch in OPENING_QUOTES) {
                        quotes.addLast(ch)
                    }
                }
                ch in OPENING_QUOTES -> quotes.addLast(ch)
                CLOSING_TO_OPENING_BRACKETS.containsKey(ch) -> {
                    val opener = CLOSING_TO_OPENING_BRACKETS.getValue(ch)
                    if (brackets.isNotEmpty() && brackets.last() == opener) brackets.removeLast()
                }
                ch in OPENING_BRACKETS -> brackets.addLast(ch)
                ch in ALL_TERMINATORS -> {
                    // Adjacent punctuation runs ("?!", "!?", "....") belong together: the
                    // boundary is placed on the last terminator only, otherwise empty
                    // "!" / "." / "?" segments are produced.
                    val rightAfter = text.getOrNull(i + 1)
                    if (rightAfter == null || rightAfter !in ALL_TERMINATORS) {
                        // The closing quote owns a terminator right before it; adding
                        // a boundary here as well would produce a "»"-only span.
                        val nextSig = nextSignificantChar(text, i + 1)
                        val closingQuoteNext = nextSig != null &&
                            quotes.isNotEmpty() &&
                            CLOSING_TO_OPENING_QUOTES[nextSig]?.let { it == quotes.last() } == true
                        val insideSplittableQuote = quotes.isEmpty() || quoteWrappedParagraph
                        if (!closingQuoteNext && brackets.isEmpty() &&
                            insideSplittableQuote &&
                            isBoundaryValid(text, ch, i, i + 1)
                        ) {
                            boundaries.add(i)
                        }
                    }
                }
            }
            i++
        }
        return boundaries
    }

    /**
     * Index of the closing quote that ends the quote block opened at [start],
     * or null if that quote never closes. Used to detect a paragraph that is
     * exactly one quoted block (it may then be split into sentences).
     */
    private fun quotedBlockEnd(text: String, start: Int): Int? {
        val opener = text.getOrNull(start) ?: return null
        if (opener !in OPENING_QUOTES) return null
        val stack = ArrayDeque<Char>()
        stack.addLast(opener)
        var i = start + 1
        while (i < text.length) {
            val ch = text[i]
            val openerForClose = CLOSING_TO_OPENING_QUOTES[ch]
            if (openerForClose != null && stack.isNotEmpty() && stack.last() == openerForClose) {
                stack.removeLast()
                if (stack.isEmpty()) return i
            } else if (ch in OPENING_QUOTES) {
                stack.addLast(ch)
            }
            i++
        }
        return null
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
     * True when the paragraph is a structural block and must be kept whole:
     * >= 2 "key: value" lines (litRPG), any divider line, or >= 2 short lines
     * without any terminator (titles, verse, status lists).
     */
    private fun isStatusBlock(paragraph: String): Boolean {
        var keyValueLines = 0
        var shortNoTerminatorLines = 0
        for (line in paragraph.split('\n')) {
            if (TextStructure.isStatusKeyValueLine(line)) keyValueLines++
            if (TextStructure.isStatusDividerLine(line)) return true
            if (TextStructure.isRepeatedCharDivider(line)) return true
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
