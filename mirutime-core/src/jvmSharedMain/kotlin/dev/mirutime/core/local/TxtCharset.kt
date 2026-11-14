package dev.mirutime.core.local

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal data class DetectedCharset(
    val charset: Charset,
    val confident: Boolean,
    val label: String,
)

// Legacy detection is a statistics over the 64 KB sample: BOM and strict UTF-8
// are exact, the CJK candidates are ranked by their own script statistics, the
// single-byte stage scores letter frequencies. cp1258, ISO-8859-6 and TIS-620
// are not covered — such files are expected to be UTF-8.
internal fun detectTxtCharset(sample: ByteArray): DetectedCharset {
    if (sample.isEmpty()) return fallback()
    bomCharset(sample)?.let { return confident(it) }
    val utf8 = strictDecode(Charsets.UTF_8, sample)
    // NUL is legal UTF-8 but in a text file it means BOM-less UTF-16.
    if (utf8 != null && '\u0000' !in utf8) return confident(Charsets.UTF_8)

    var bestCjk: MultibyteCandidate? = null
    var bestMatch: MultibyteMatch? = null
    for (candidate in MultibyteCandidates) {
        val text = strictDecode(candidate.charset, sample) ?: continue
        val match = multibyteScore(candidate.kind, text) ?: continue
        // Strict ">" keeps the more specific candidate on a tie, list order.
        if (bestMatch == null || match.score > bestMatch.score) {
            bestCjk = candidate
            bestMatch = match
        }
    }

    utf16WithoutBom(sample)?.let { return confident(it) }

    val bestSingle = SingleByteCandidates.mapNotNull { candidate ->
        singleByteConfidence(candidate, String(sample, candidate.charset))?.let { candidate to it }
    }.maxByOrNull { it.second }
    val singleConfidence = bestSingle?.second ?: 0.0
    // A short single-byte text (an ALL-CAPS name) can gate as GB18030 with only
    // 2-6 Han characters, while a real CJK sample has hundreds. When the script
    // evidence is that thin the byte confidence decides; otherwise CJK wins.
    val cjk = bestCjk
    if (cjk != null && bestMatch != null && bestMatch.score >= CJK_SCORE_MIN &&
        bestMatch.scriptLetters >= CJK_MIN_SCRIPT_LETTERS
    ) {
        return confident(cjk.charset)
    }
    if (bestSingle != null && singleConfidence >= SINGLE_BYTE_CONFIDENCE_MIN) {
        return confident(bestSingle.first.charset)
    }
    // A CJK text that decoded cleanly must not end up as a confident cp1251.
    if (cjk != null) return confident(cjk.charset)
    return fallback()
}

private const val SCRIPT_SHARE_MIN = 0.5
private const val CJK_SCORE_MIN = 0.02
private const val CJK_MIN_SCRIPT_LETTERS = 8
private const val SINGLE_BYTE_CONFIDENCE_MIN = 0.35
private const val CONTROL_PENALTY = 3.0
private const val UTF16_ZERO_MIN_SHARE = 0.2
private const val UTF16_ZERO_SIDE_SHARE = 0.8

private fun confident(charset: Charset) = DetectedCharset(charset, true, charset.name())

private fun fallback(): DetectedCharset {
    val charset = charsetOrNull("windows-1251") ?: Charsets.UTF_8
    return DetectedCharset(charset, false, charset.name())
}

private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

private fun bomCharset(sample: ByteArray): Charset? = when {
    sample.startsWithBytes(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8
    sample.startsWithBytes(0xFF, 0xFE, 0x00, 0x00) -> charsetOrNull("UTF-32LE")
    sample.startsWithBytes(0x00, 0x00, 0xFE, 0xFF) -> charsetOrNull("UTF-32BE")
    sample.startsWithBytes(0xFF, 0xFE) -> charsetOrNull("UTF-16LE")
    sample.startsWithBytes(0xFE, 0xFF) -> charsetOrNull("UTF-16BE")
    else -> null
}

private fun ByteArray.startsWithBytes(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

// A strict decode only proves the byte pairs are legal; the same bytes often
// decode cleanly as several CJK charsets, so every candidate is scored by its
// own script statistics instead of taking the first one. endOfInput = false
// keeps a multibyte character cut by the sample boundary from being an error.
private fun strictDecode(charset: Charset, sample: ByteArray): String? = runCatching {
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val out = CharBuffer.allocate(sample.size + 16)
    val result = decoder.decode(ByteBuffer.wrap(sample), out, false)
    if (result.isError) {
        null
    } else {
        out.flip()
        out.toString()
    }
}.getOrNull()

// BOM-less UTF-16 (ASCII-heavy text) leaves a NUL at every second byte. The
// side that holds the NULs tells the byte order: the high byte follows the ASCII.
private fun utf16WithoutBom(sample: ByteArray): Charset? {
    var zeros = 0
    var evenZeros = 0
    for (i in sample.indices) {
        if (sample[i] == 0.toByte()) {
            zeros++
            if (i % 2 == 0) evenZeros++
        }
    }
    if (zeros.toDouble() / sample.size <= UTF16_ZERO_MIN_SHARE) return null
    val evenShare = evenZeros.toDouble() / zeros
    return when {
        evenShare >= UTF16_ZERO_SIDE_SHARE -> charsetOrNull("UTF-16BE")
        evenShare <= 1.0 - UTF16_ZERO_SIDE_SHARE -> charsetOrNull("UTF-16LE")
        else -> null
    }
}

private enum class CjkKind { HANGUL, KANA, HAN }

private data class MultibyteCandidate(val charset: Charset, val kind: CjkKind)

// Order is the tie-break priority, most specific first; GB18030 is last because
// its byte ranges would swallow every other candidate.
private val MultibyteCandidates: List<MultibyteCandidate> = listOfNotNull(
    charsetOrNull("EUC-KR")?.let { MultibyteCandidate(it, CjkKind.HANGUL) },
    charsetOrNull("EUC-JP")?.let { MultibyteCandidate(it, CjkKind.KANA) },
    charsetOrNull("Shift_JIS")?.let { MultibyteCandidate(it, CjkKind.KANA) },
    charsetOrNull("Big5")?.let { MultibyteCandidate(it, CjkKind.HAN) },
    charsetOrNull("GB18030")?.let { MultibyteCandidate(it, CjkKind.HAN) },
)

// Score = share of letters that are frequent characters of the candidate's
// script; null when the decoded text is not dominated by that script at all.
// `scriptLetters` feeds the thin-evidence check in the decision.
private class MultibyteMatch(val score: Double, val scriptLetters: Int)

private fun multibyteScore(kind: CjkKind, text: String): MultibyteMatch? {
    var letters = 0
    var inScript = 0
    var kana = 0
    var common = 0
    for (c in text) {
        if (!c.isLetter()) continue
        letters++
        when (kind) {
            CjkKind.HANGUL -> {
                if (isHangulChar(c)) inScript++
                if (HANGUL_COMMON.indexOf(c) >= 0) common++
            }
            CjkKind.KANA -> {
                if (isHanOrKanaChar(c)) inScript++
                if (isKanaChar(c)) kana++
                if (HAN_COMMON.indexOf(c) >= 0) common++
            }
            CjkKind.HAN -> {
                if (isHanChar(c)) inScript++
                if (HAN_COMMON.indexOf(c) >= 0) common++
            }
        }
    }
    if (letters == 0 || inScript.toDouble() / letters < SCRIPT_SHARE_MIN) return null
    // Kanji-only Japanese carries no kana, so frequent Han characters stand in.
    val hits = if (kind == CjkKind.KANA && kana > 0) kana else common
    return MultibyteMatch(hits.toDouble() / letters, inScript)
}

private const val HANGUL_COMMON =
    "이다는의에가하을를지기리로나도수시어자해보면일대있게거서구여그저우니한"

// Simplified and traditional forms of the most frequent characters. Both are
// listed because Big5 and GB18030 decode the same bytes to different scripts.
private const val HAN_COMMON =
    "的一是不了在人有我他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些与高意进把法此实回二理美点月明其种声全工己话儿者向情部正名定女问力机给等几很业最间新什打便位因重被走电四第门相次东政海口使教西再平真听世气信北少关并内加化由却代军产入先山五太水万市眼体别处总才场师书比住员九笑性通目华报立马命张活难神数件安表原车白应朝叫死常提感金何更反合放做系计或司利受光王果亲界及今京务制解各任至清物台象记边共风战干接它许八特觉望直服毛林题建南度统色字请交爱让认算论百吃义科怎元社术结六功指思非流每青管夫连远资队跟带花快条院变联言权往展该领传近留红治决周保达办运武半候七必城父强步完革深区即求品士转量空甚众技轻程告江语英基派满式李息写呢识极令黄德收脸钱党倒未持取设始版双历越史商千片容研像找友孩站广改议形委早房音火际则首单据导影失拿网香似斯专石若兵弟谁校读志飞观争究包组造落视济喜离虽坐集编宝谈府拉黑且随格尽剑讲布杀微怕母调局根曾准团段终乐切级克精哪官示冷卡极呀苦假击绿称掉這國說來時會對個與後麼開點萬電為們將無東車馬見學覺聽聲華報張難數業應兩長間題軍進種實發現當動過還經頭體組應"

private data class SingleByteCandidate(
    val charset: Charset,
    val table: String,
    val script: (Char) -> Boolean,
)

private const val CYRILLIC_FREQUENCIES = "оеаинтсрвлкмдпуяыьгзбчй"
private const val ARABIC_FREQUENCIES = "الميونرتبعدهاسحةفكق"
private const val LATIN_FREQUENCIES = "etaoinshrdlucmfwypvbgkjqxz"

private val SingleByteCandidates: List<SingleByteCandidate> = listOfNotNull(
    charsetOrNull("windows-1251")?.let {
        SingleByteCandidate(it, CYRILLIC_FREQUENCIES, ::isCyrillicChar)
    },
    charsetOrNull("windows-1256")?.let {
        SingleByteCandidate(it, ARABIC_FREQUENCIES, ::isArabicChar)
    },
    charsetOrNull("windows-1252")?.let {
        SingleByteCandidate(it, LATIN_FREQUENCIES, ::isLatinLetter)
    },
    charsetOrNull("KOI8-R")?.let {
        SingleByteCandidate(it, CYRILLIC_FREQUENCIES, ::isCyrillicChar)
    },
)

// Normalized confidence = weighted letter score / letters, so a short sample
// does not win just because it is short.
private fun singleByteConfidence(candidate: SingleByteCandidate, text: String): Double? {
    var score = 0.0
    var letters = 0
    var inScript = 0
    for (raw in text) {
        val c = raw.lowercaseChar()
        if (raw.isLetter()) letters++
        if (candidate.script(c)) {
            inScript++
            val index = candidate.table.indexOf(c)
            if (index >= 0) score += (1.0 - 0.04 * index).coerceAtLeast(0.1)
        } else if (c == '\uFFFD' || (c < ' ' && c != '\n' && c != '\r' && c != '\t')) {
            score -= CONTROL_PENALTY
        }
    }
    if (letters == 0 || inScript.toDouble() / letters < SCRIPT_SHARE_MIN) return null
    return score / letters
}

internal fun isHanChar(c: Char): Boolean = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'

internal fun isKanaChar(c: Char): Boolean = c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'

internal fun isHangulChar(c: Char): Boolean = c in '\uAC00'..'\uD7AF'

internal fun isHanOrKanaChar(c: Char): Boolean = isHanChar(c) || isKanaChar(c)

private fun isCyrillicChar(c: Char): Boolean = c in '\u0400'..'\u04FF'

private fun isArabicChar(c: Char): Boolean = c in '\u0600'..'\u06FF'

private fun isLatinLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'
