package dev.mirutime.core.local

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TxtCharsetTest {

    private val russian =
        "Он медленно шёл по узкой улице. Над ним висели тяжёлые облака, и воздух пах " +
            "дождём. Он вспоминал прекрасные дни своей юности, зелёные луга и маленький " +
            "дом у реки. ".repeat(4)

    private val german =
        "Der alte Mann ging langsam über die schmale Straße. Über ihm hingen schwere " +
            "Wolken, und die Luft roch nach Regen. Er dachte an die schönen Tage seiner " +
            "Jugend zurück, an die grünen Wiesen und das kleine Haus am Fluss. ".repeat(4)

    private val arabic =
        "السلام عليكم ورحمة الله وبركاته، هذا نص عربي للاختبار وقراءة الكتب. كان الرجل " +
            "يمشي في الشارع الضيق وينظر إلى السماء. ".repeat(4)

    private val korean =
        "그는 아침 일찍 일어나서 창문을 열었다. 바깥은 아직 어두웠지만 공기는 차갑고 " +
            "맑았다. 오늘은 오래전부터 준비해 온 여행을 떠나는 날이었다. 그는 가방을 " +
            "다시 한 번 확인하고 천천히 집을 나섰다."

    private val koreanProse =
        "그는 아침 일찍 일어나서 창문을 열었다. 바깥은 아직 어두웠지만 공기는 차갑고 맑았다. " +
            "오늘은 오래전부터 준비해 온 여행을 떠나는 날이었다. 그는 가방을 다시 한 번 확인하고 " +
            "천천히 집을 나섰다. 거리에는 사람들이 많지 않았고, 가게들은 아직 문을 열지 않았다. " +
            "그는 역으로 가는 길에 작은 카페에 들러 따뜻한 커피를 한 잔 주문했다. 창가에 앉아 " +
            "지나가는 사람들을 바라보면서 그는 지난 몇 달 동안 있었던 일들을 떠올렸다. ".repeat(5)

    private val japaneseContents = "第一章 序論 第二章 本論 第三章 結論 第四章 補論 参考文献 索引 ".repeat(40)

    private val japanese = "日本語のテキストです。これは文字コードのテストです。".repeat(10)

    private val japaneseNames = "山田太郎 鈴木一郎 佐藤花子 田中実 高橋誠 渡辺直子 伊藤健 中村優 小林誠 加藤明"

    private val japaneseAddress = "東京都新宿区西新宿二丁目大阪府大阪市北区梅田神奈川県横浜市中区山下町"

    private val chineseModern =
        "中华人民共和国成立了。这是一个伟大的国家，有着悠久的历史和灿烂的文化。" +
            "人民勤劳勇敢，正在建设美好的未来。".repeat(8)

    private val chineseNarrative =
        "他走进房间，看见桌子上放着一本厚厚的书。窗外的阳光照进来，把整个屋子照得暖洋洋的。" +
            "他坐下来，慢慢地翻开第一页。".repeat(8)

    private val chineseTraditional =
        "中華民國成立了。這是一個偉大的國家，有著悠久的歷史和燦爛的文化。" +
            "人民勤勞勇敢，正在建設美好的未來。".repeat(8)

    private fun detect(text: String, charset: Charset) = detectTxtCharset(text.toByteArray(charset))

    @Test
    fun `utf8 cyrillic is confident`() {
        val detected = detect(russian, Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `utf8 with bom is confident`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            russian.toByteArray(Charsets.UTF_8)
        val detected = detectTxtCharset(bytes)
        assertEquals(Charsets.UTF_8, detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `utf16le with bom is confident`() {
        val bytes = "\uFEFF$russian".toByteArray(Charsets.UTF_16LE)
        val detected = detectTxtCharset(bytes)
        assertEquals(Charset.forName("UTF-16LE"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `utf16be with bom is confident`() {
        val bytes = "\uFEFF$russian".toByteArray(Charsets.UTF_16BE)
        val detected = detectTxtCharset(bytes)
        assertEquals(Charset.forName("UTF-16BE"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `utf16le without bom is recognized by the zero side`() {
        val detected = detectTxtCharset(german.toByteArray(Charsets.UTF_16LE))
        assertEquals(Charset.forName("UTF-16LE"), detected.charset)
    }

    @Test
    fun `utf16be without bom is recognized by the zero side`() {
        val detected = detectTxtCharset(german.toByteArray(Charsets.UTF_16BE))
        assertEquals(Charset.forName("UTF-16BE"), detected.charset)
    }

    @Test
    fun `utf8 character cut by the sample boundary is not an error`() {
        val bytes = "привет".toByteArray(Charsets.UTF_8)
        val detected = detectTxtCharset(bytes.copyOf(bytes.size - 1))
        assertEquals(Charsets.UTF_8, detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `cp1251 russian text`() {
        val detected = detect(russian, Charset.forName("windows-1251"))
        assertEquals(Charset.forName("windows-1251"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `koi8 r russian text`() {
        val detected = detect(russian, Charset.forName("KOI8-R"))
        assertEquals(Charset.forName("KOI8-R"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `cp1256 arabic text`() {
        val detected = detect(arabic, Charset.forName("windows-1256"))
        assertEquals(Charset.forName("windows-1256"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `cp1252 german text with umlauts`() {
        val detected = detect(german, Charset.forName("windows-1252"))
        assertEquals(Charset.forName("windows-1252"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `shift jis japanese text`() {
        val detected = detect(japanese, Charset.forName("Shift_JIS"))
        assertEquals(Charset.forName("Shift_JIS"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `euc jp japanese text`() {
        val detected = detect(japanese, Charset.forName("EUC-JP"))
        assertEquals(Charset.forName("EUC-JP"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `euc kr korean text`() {
        val detected = detect(korean, Charset.forName("EUC-KR"))
        assertEquals(Charset.forName("EUC-KR"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `euc kr korean prose`() {
        val detected = detect(koreanProse, Charset.forName("EUC-KR"))
        assertEquals(Charset.forName("EUC-KR"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `euc jp kanji only table of contents`() {
        val detected = detect(japaneseContents, Charset.forName("EUC-JP"))
        assertEquals(Charset.forName("EUC-JP"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `gb18030 modern chinese text`() {
        val detected = detect(chineseModern, Charset.forName("GB18030"))
        assertEquals(Charset.forName("GB18030"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `gb18030 narrative chinese text that also decodes as big5`() {
        val detected = detect(chineseNarrative, Charset.forName("GB18030"))
        assertEquals(Charset.forName("GB18030"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `big5 traditional chinese text`() {
        val detected = detect(chineseTraditional, Charset.forName("Big5"))
        assertEquals(Charset.forName("Big5"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `shift jis kanji only text without kana`() {
        val detected = detect(japaneseNames, Charset.forName("Shift_JIS"))
        assertEquals(Charset.forName("Shift_JIS"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `euc jp kanji only text without kana`() {
        val detected = detect(japaneseAddress, Charset.forName("EUC-JP"))
        assertEquals(Charset.forName("EUC-JP"), detected.charset)
        assertTrue(detected.confident)
    }

    @Test
    fun `short all caps cyrillic stays cp1251`() {
        for (text in listOf("ИДИОТ", "АННА КАРЕНИНА", "ПРИВЕТ МИРОК")) {
            val detected = detect(text, Charset.forName("windows-1251"))
            assertEquals(Charset.forName("windows-1251"), detected.charset, text)
            assertTrue(detected.confident, text)
        }
    }

    @Test
    fun `empty sample falls back to cp1251 without confidence`() {
        val detected = detectTxtCharset(ByteArray(0))
        assertEquals(Charset.forName("windows-1251"), detected.charset)
        assertFalse(detected.confident)
    }

    @Test
    fun `cjk tie is won by the more specific candidate`() {
        // 0xA4 0xA3 is a hiragana in EUC-JP and a common han character in Big5:
        // both score 1.0, the earlier (more specific) candidate must win.
        val sample = ByteArray(600) { if (it % 2 == 0) 0xA4.toByte() else 0xA3.toByte() }
        val detected = detectTxtCharset(sample)
        assertEquals(Charset.forName("EUC-JP"), detected.charset)
        assertTrue(detected.confident)
    }
}
