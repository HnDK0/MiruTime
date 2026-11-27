package dev.mirutime.uikit

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTitleHyphenatorTest {

    private fun monoWidth(text: String): Int = text.length

    @Test
    fun `russian regression fills first line instead of leaving lone word`() {
        val formatted = formatCardTitleLines(
            title = "Герой, жаждущий мести, уничтожит врагов силой тьмы",
            maxLines = 2,
            maxWidthPx = 15,
            measureWidthPx = ::monoWidth,
        )
        val lines = formatted.split('\n')
        assertEquals(2, lines.size)
        // With a 2px guard fitWidth = 13: "Герой, жажду-" is 13 chars.
        assertEquals("Герой, жажду-", lines[0])
        assertTrue(lines[1].startsWith("щий мести"))
    }

    @Test
    fun `three-line on-cover layout packs every wrap without orphan first lines`() {
        val formatted = formatCardTitleLines(
            title = "Герой, жаждущий мести, уничтожит врагов силой тьмы",
            maxLines = 3,
            maxWidthPx = 15,
            measureWidthPx = ::monoWidth,
        )
        val lines = formatted.split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("-"))
        assertTrue(lines[0].length >= 11)
        assertTrue(lines[1].length >= 10)
    }

    @Test
    fun `cyrillic rules respect й ь ъ ukrainian apostrophe and minimum lengths`() {
        val warBreaks = wordBreakCandidates("война")
        assertTrue(warBreaks.any { it.head == "вой-" && it.tail == "на" })
        assertFalse(warBreaks.any { it.tail.startsWith("й") })

        val strongBreaks = wordBreakCandidates("сильный")
        assertTrue(strongBreaks.any { it.head == "силь-" && it.tail == "ный" })
        assertFalse(strongBreaks.any { it.tail.startsWith("ь") })

        val entranceBreaks = wordBreakCandidates("подъезд")
        assertTrue(entranceBreaks.any { it.head == "подъ-" && it.tail == "езд" })
        assertFalse(entranceBreaks.any { it.tail.startsWith("ъ") })

        val destroyBreaks = wordBreakCandidates("уничтожит")
        assertFalse(destroyBreaks.any { it.head == "у-" })
        assertTrue(destroyBreaks.any { it.head == "унич-" && it.tail == "тожит" })
        assertTrue(destroyBreaks.any { it.head == "уничто-" && it.tail == "жит" })

        val armyBreaks = wordBreakCandidates("армия")
        assertFalse(armyBreaks.any { it.tail == "я" })
        assertTrue(armyBreaks.any { it.head == "ар-" && it.tail == "мия" })

        val uaApostrophe = wordBreakCandidates("комп'ютер")
        assertFalse(uaApostrophe.any { it.head == "комп'-" })
        assertTrue(uaApostrophe.any { it.head == "комп'ю-" && it.tail == "тер" })

        // Single-vowel words must never be broken.
        assertTrue(wordBreakCandidates("тьмы").isEmpty())
        assertTrue(wordBreakCandidates("взгляд").isEmpty())
    }

    @Test
    fun `latin scripts handle english german french turkish vietnamese romaji and uppercase`() {
        // Silent terminal -e / -ed stays unbroken, whereas pronounced -ted/-ded and -ing break cleanly.
        assertTrue(wordBreakCandidates("Blade").isEmpty())
        assertTrue(wordBreakCandidates("BLADE").isEmpty())
        assertTrue(wordBreakCandidates("Cursed").isEmpty())

        val discarded = wordBreakCandidates("Discarded")
        assertTrue(discarded.any { it.head == "Dis-" && it.tail == "carded" })
        assertTrue(discarded.any { it.head == "Discar-" && it.tail == "ded" })

        val discardedUpper = wordBreakCandidates("DISCARDED")
        assertTrue(discardedUpper.any { it.head == "DIS-" && it.tail == "CARDED" })

        val mysteries = wordBreakCandidates("Mysteries")
        assertTrue(mysteries.any { it.head == "Mys-" && it.tail == "teries" })

        val beginning = wordBreakCandidates("Beginning")
        assertTrue(beginning.any { it.head == "Begin-" && it.tail == "ning" })

        val possessive = wordBreakCandidates("Journey's")
        assertFalse(possessive.any { it.tail == "'s" || it.tail == "s" })

        val germanLong = wordBreakCandidates("Donaudampfschifffahrtsgesellschaft")
        assertTrue(germanLong.size >= 4)
        assertTrue(germanLong.any { it.head.endsWith("Donaudampf-") && it.tail.startsWith("schiff") })

        val frenchElision = wordBreakCandidates("L'Impératrice")
        assertFalse(frenchElision.any { it.head == "L'-" })
        assertTrue(frenchElision.any { it.head == "L'Im-" && it.tail == "pératrice" })

        val turkish = wordBreakCandidates("Hükümdarın")
        assertTrue(turkish.any { it.head == "Hüküm-" && it.tail == "darın" })

        val romaji = wordBreakCandidates("Machigatteiru")
        assertTrue(romaji.any { it.head == "Machi-" && it.tail == "gatteiru" })
        assertTrue(romaji.any { it.head == "Machigat-" && it.tail == "teiru" })

        // Decomposed NFD Vietnamese combining marks must normalize and never leave orphan marks.
        val nfdViet = Normalizer.normalize("Hoàng", Normalizer.Form.NFD)
        val multiViet = Normalizer.normalize("Thương", Normalizer.Form.NFD)
        wordBreakCandidates(nfdViet).forEach { br ->
            val firstTailType = Character.getType(br.tail.codePointAt(0))
            assertFalse(firstTailType == Character.NON_SPACING_MARK.toInt())
        }
        assertTrue(wordBreakCandidates(multiViet).isEmpty())
    }

    @Test
    fun `greek words split cleanly without 1-letter fragments`() {
        val breaks = wordBreakCandidates("Αθάνατος")
        assertTrue(breaks.isNotEmpty())
        assertTrue(breaks.all { it.head.length >= 3 && it.tail.length >= 2 })
    }

    @Test
    fun `balanced two-line titles without overflow keep whole words unbroken`() {
        val formatted = formatCardTitleLines(
            title = "Blade of the Discarded",
            maxLines = 2,
            maxWidthPx = 18,
            measureWidthPx = ::monoWidth,
        )
        assertEquals("Blade of the\nDiscarded", formatted)

        val lomn = formatCardTitleLines(
            title = "Lord of the Mysteries",
            maxLines = 2,
            maxWidthPx = 16,
            measureWidthPx = ::monoWidth,
        )
        assertEquals("Lord of the\nMysteries", lomn)
    }

    @Test
    fun `very narrow line with orphan single short word hyphenates second word`() {
        val formatted = formatCardTitleLines(
            title = "Solo Leveling",
            maxLines = 2,
            maxWidthPx = 12,
            measureWidthPx = ::monoWidth,
        )
        val lines = formatted.split('\n')
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("Solo "))
        assertTrue(lines[0].endsWith("-"))
    }

    @Test
    fun `compound punctuation splits cleanly without inserting extra hyphens`() {
        val slash = wordBreakCandidates("Fate/Grand")
        assertTrue(slash.any { it.head == "Fate/" && it.tail == "Grand" && it.compound })

        val colon = wordBreakCandidates("Re:Zero")
        assertTrue(colon.any { it.head == "Re:" && it.tail == "Zero" && it.compound })

        val hyphenated = wordBreakCandidates("S-Rank")
        assertTrue(hyphenated.any { it.head == "S-" && it.tail == "Rank" && it.compound })

        // Non-breaking hyphen U+2011 must never split.
        assertTrue(wordBreakCandidates("S\u2011Rank").isEmpty())
    }

    @Test
    fun `cursive indic cjk arabic hebrew and numeric tokens never receive hyphens`() {
        assertTrue(wordBreakCandidates("الأسطوري").isEmpty())
        assertTrue(wordBreakCandidates("הגיבור").isEmpty())
        assertTrue(wordBreakCandidates("斗破苍穹斗破").isEmpty())
        assertTrue(wordBreakCandidates("無職転生異世界").isEmpty())
        assertTrue(wordBreakCandidates("레벨업혼자만").isEmpty())
        assertTrue(wordBreakCandidates("महाभारत").isEmpty())
        assertTrue(wordBreakCandidates("Ch.1234").isEmpty())

        val cjk = formatCardTitleLines(
            title = "斗破苍穹异世界转生",
            maxLines = 2,
            maxWidthPx = 6,
            measureWidthPx = ::monoWidth,
        )
        assertFalse(cjk.contains("-"))

        val arabic = formatCardTitleLines(
            title = "بطل الرواية الأسطوري العظيم",
            maxLines = 2,
            maxWidthPx = 12,
            measureWidthPx = ::monoWidth,
        )
        assertFalse(arabic.contains("-"))
    }

    @Test
    fun `locale detection picks script language regardless of UI locale`() {
        assertEquals("ru", cardTitleLocaleTag("Герой, жаждущий мести", "en-US"))
        assertEquals("uk", cardTitleLocaleTag("Історія героя", "en-US"))
        assertEquals("el", cardTitleLocaleTag("Βασιλιάς", "ru-RU"))
        assertEquals("de", cardTitleLocaleTag("Der Großmeister", "ru-RU"))
        assertEquals("tr", cardTitleLocaleTag("Hükümdarın Kılıcı", "en-US"))
        assertEquals("vi", cardTitleLocaleTag("Thế Giới Hoàn Mỹ", "en-US"))
        assertEquals("en", cardTitleLocaleTag("Lord of the Mysteries", "ru-RU"))
        assertEquals("fr-FR", cardTitleLocaleTag("Le Prince", "fr-FR"))
        assertEquals("de-DE", cardTitleLocaleTag("Der Prinz", "de-DE"))
        assertEquals("zh-CN", cardTitleLocaleTag("斗破苍穹", "zh-CN"))
    }
}
