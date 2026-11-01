package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoveLA SentenceSplitter parity cases. Expected segments are the exact
 * strings NoveLA produces; spans must reconstruct those segments.
 */
class SentenceSplitterTest {

    private fun segments(text: String): List<String> =
        SentenceSplitter.spans(text).map { text.substring(it.start, it.end) }

    private fun assertValidSpans(text: String) {
        var previousEnd = 0
        SentenceSplitter.spans(text).forEach { span ->
            assertTrue("span start ${span.start} >= previous $previousEnd", span.start >= previousEnd)
            assertTrue("span end ${span.end} <= ${text.length}", span.end <= text.length)
            assertTrue("span is not empty", span.start < span.end)
            previousEnd = span.end
        }
    }

    // --- Size gate -----------------------------------------------------------

    @Test
    fun `short paragraph with two sentences is kept whole`() {
        val input = "He walked home. The sun was setting."
        assertEquals(listOf(input), segments(input))
    }

    @Test
    fun `99 chars stay whole and 100 chars can split`() {
        val under = "A".repeat(96) + ". B"
        val over = "A".repeat(97) + ". B"
        assertEquals(99, under.length)
        assertEquals(100, over.length)

        assertEquals(listOf(under), segments(under))
        assertEquals(listOf("A".repeat(97) + ".", "B"), segments(over))
    }

    @Test
    fun `long english paragraph splits into three sentences`() {
        val input = "He walked through the quiet streets of the old town, watching the lights flicker on in the windows of the houses he passed one by one. The sun was setting slowly behind the distant hills, painting the whole sky in warm shades of orange and pink. Birds sang their evening songs from the treetops, filling the quiet air with gentle and soothing melodies."
        assertEquals(
            listOf(
                "He walked through the quiet streets of the old town, watching the lights flicker on in the windows of the houses he passed one by one.",
                "The sun was setting slowly behind the distant hills, painting the whole sky in warm shades of orange and pink.",
                "Birds sang their evening songs from the treetops, filling the quiet air with gentle and soothing melodies.",
            ),
            segments(input),
        )
    }

    // --- Abbreviations / numbers / initials ---------------------------------

    @Test
    fun `abbreviations do not split`() {
        val dr = "Dr. Smith went home after a long day at the clinic where he had treated many patients, written detailed reports and filled out all the paperwork for the week. He was tired but satisfied with the work he had done that day, and he was already looking forward to a quiet evening at home with a good book."
        assertEquals(2, segments(dr).size)

        val number = "No. 1 is the best option for anyone who wants to learn quickly and efficiently, so we always recommend it to beginners who start from scratch. Try it yourself and you will see the difference immediately, without spending too much time on complicated practice."
        assertEquals(2, segments(number).size)

        val time = "The meeting is at 3 p.m. as usual, so we need to prepare the reports and the presentation slides well in advance of tomorrow morning, so we can connect without any delays. We left the office a bit earlier to have enough time for all the preparations and for a quick dinner before the call."
        assertEquals(2, segments(time).size)

        val ru = "Он пришёл в 2024 г. и сразу принялся за работу над новым проектом, который требовал его постоянного внимания и полной сосредоточенности с самого утра до позднего вечера. И ушёл он только поздно вечером, когда все дела были наконец закончены и можно было спокойно выдохнуть и отдохнуть."
        assertEquals(2, segments(ru).size)
    }

    @Test
    fun `initials do not split`() {
        val chain = "И.В. Иванов пришёл на встречу раньше всех и занял место у окна в дальнем конце кабинета, рядом с большим шкафом с документами, и стал ждать начала обсуждения. Он сел и принялся перечитывать свои заметки перед началом обсуждения, чтобы освежить в памяти все ключевые цифры и факты, о которых собирался рассказать."
        assertEquals(2, segments(chain).size)

        val single = "Я сегодня случайно встретил И. Иванова на вокзале, и мы немного поговорили о погоде, о работе и о наших общих знакомых, которых не видели уже много лет. Он молчал почти всю дорогу, но потом разговорился и рассказал много интересного о своей поездке и о том, что происходило в городе за последнее время."
        assertEquals(2, segments(single).size)
    }

    @Test
    fun `decimals and dates do not split`() {
        val decimal = "В математике и физике значение числа π равно 3.14, и это число используется повсюду, от простых школьных задач до самых сложных инженерных и космических расчётов. Это важно для всех, кто изучает точные науки и применяет их на практике, потому что без этого числа невозможно представить современную физику и технику."
        assertEquals(2, segments(decimal).size)

        val date = "Дата 12.05.2024 была солнечной и тёплой, и многие люди вышли на улицы города, чтобы насладиться по-настоящему хорошей весенней погодой после долгой холодной зимы. Он вышел из дома рано утром, взял с собой термос с чаем и отправился на долгую прогулку по набережной реки в парке."
        assertEquals(2, segments(date).size)
    }

    // --- Dialogue, quotes and brackets --------------------------------------

    @Test
    fun `dash dialogue paragraph is kept whole`() {
        val input = "— Привет, как дела? — спросил он, подходя ближе и протягивая руку для рукопожатия. — Хорошо, спасибо, — ответила она и улыбнулась, глядя ему в глаза. — Отлично, тогда пойдём прогуляемся по парку, — предложил он и показал рукой в сторону аллеи. — С удовольствием, — согласилась она, поправляя шарф."
        assertEquals(listOf(input), segments(input))
    }

    @Test
    fun `closing quote followed by dash attribution does not end a sentence`() {
        val input = "«Беги!» — крикнул он и побежал к выходу, лавируя между машинами и оглядываясь назад на каждого прохожего, который попадался ему на пути, пока бежал через парковку. «Стой!» — закричала она ему вслед. Он не остановился и даже не замедлил шаг, и вскоре скрылся из виду за поворотом, и она осталась стоять одна на пустой улице, тяжело дыша и сжимая в руке ключи."
        assertEquals(
            listOf(
                "«Беги!» — крикнул он и побежал к выходу, лавируя между машинами и оглядываясь назад на каждого прохожего, который попадался ему на пути, пока бежал через парковку.",
                "«Стой!» — закричала она ему вслед.",
                "Он не остановился и даже не замедлил шаг, и вскоре скрылся из виду за поворотом, и она осталась стоять одна на пустой улице, тяжело дыша и сжимая в руке ключи.",
            ),
            segments(input),
        )
    }

    @Test
    fun `parentheses keep inner periods and split outside`() {
        val input = "Let me explain the situation in full detail (see the attached note on the previous page. It matters a great deal for what follows in this chapter). The next sentence starts right here and continues with a lot of additional words that describe the circumstances of the evening in great detail."
        assertEquals(2, segments(input).size)
        assertTrue(segments(input)[0].endsWith("chapter)."))
    }

    @Test
    fun `inline quote keeps its interior sentences`() {
        val input = "He said \"Just wait right here and do not move. Things will be fine.\" Then he walked away down the long corridor without looking back even once, disappearing around the corner where the light was dim and the shadows were long and deep, and he never looked back even once."
        assertEquals(
            listOf(
                "He said \"Just wait right here and do not move. Things will be fine.\"",
                "Then he walked away down the long corridor without looking back even once, disappearing around the corner where the light was dim and the shadows were long and deep, and he never looked back even once.",
            ),
            segments(input),
        )
    }

    @Test
    fun `paragraph wrapped in quotes is a frame and stays whole`() {
        val input = "\"Junior Brothers and Sisters, you must cross the Daluo Immortal Bridge every morning to attend the lectures given by the Elders, and you must remember this rule well and never forget it. Otherwise, unless there is a truly valid reason, missing the morning class will result in a serious punishment for the whole group of newly inducted disciples.\""
        assertEquals(listOf(input), segments(input))
    }

    @Test
    fun `japanese bracket quote keeps inner content and splits after it`() {
        val input = "「彼は言った。大丈夫だ。」 そして彼は静かに部屋を出て、暗い廊下を歩きながら、明日のことを考えていた。電車はもうホームに来ていて、たくさんの人々が慌ただしく乗り降りしていた。彼は一番後ろの席に座って窓の外を見た。"
        assertEquals(
            listOf(
                "「彼は言った。大丈夫だ。」",
                "そして彼は静かに部屋を出て、暗い廊下を歩きながら、明日のことを考えていた。",
                "電車はもうホームに来ていて、たくさんの人々が慌ただしく乗り降りしていた。",
                "彼は一番後ろの席に座って窓の外を見た。",
            ),
            segments(input),
        )
    }

    // --- Script terminators --------------------------------------------------

    @Test
    fun `japanese script terminators split sentences`() {
        val input = "彼は遅い時間に仕事から家に帰って、とても疲れていたので、まずは冷たいお風呂に入って体を温めて、それから簡単な夕食を食べて、ベッドに入って、長い一日の出来事を一つずつ思い出しながら、明日の朝のことをもう考えないようにして、ただ静かに眠りについた。太陽がゆっくりと西の地平線の向こうに沈んで、空が美しいオレンジ色と紫色に染まって、街の灯りが一つずつ点いていき、そして夜の気配が静かに近づいてきた、まさにそのときに。鳥たちが高い木の上で楽しそうに鳴いていて、その澄んだ声がだんだんと静まりかけた夜の空気の中に優しく響いていた、そして遠くの森からは夜の音が聞こえてきた。"
        assertEquals(3, segments(input).size)
    }

    @Test
    fun `arabic question mark splits sentences`() {
        val input = "ذهب الرجل إلى المنزل بعد يوم طويل من العمل في المدينة المزدحمة، حيث كانت الشوارع مليئة بالناس والسيارات والأصوات العالية في كل مكان. هل أنت بخير اليوم بعد هذا اليوم الطويل والمتعب في العمل؟ نعم أنا بخير، شكراً لسؤالك اللطيف، لقد كنت متعباً فقط لأنني مشيت كثيراً اليوم في شوارع المدينة الكبيرة."
        assertEquals(3, segments(input).size)
    }

    // --- Status blocks / decoration -----------------------------------------

    @Test
    fun `status block with key value lines stays whole`() {
        val input = "Имя: Джон\nУровень: 5\nHP: 120/120\nMP: 100/100\nСила: 15\nЛовкость: 12\nИнтеллект: 18\nМудрость: 14\nХаризма: 11\nВыносливость: 20\nСкорость: 16\nУдача: 8\nЗдоровье: 120\nМана: 90\nОпыт: 3500\nЗолото: 450\nАтака: 30\nЗащита: 25"
        assertEquals(listOf(input), segments(input))
    }

    @Test
    fun `status divider line stays whole`() {
        val input = "Глава первая: Встреча\n***\nОн шёл домой и думал о том, что произошло сегодня на работе, и не мог найти ответа на вопрос, который мучил его весь вечер и не давал покоя."
        assertEquals(listOf(input), segments(input))
    }

    @Test
    fun `emoji decorated paragraph stays whole`() {
        val input = "Капитан поднялся на палубу и улыбнулся, глядя на горизонт, где уже вставало солнце и разгоняло утренний туман, окутывающий корабль, и на мачтах хлопали паруса. 🌅"
        assertEquals(listOf(input), segments(input))
    }

    @Test
    fun `adjacent question and exclamation marks are one boundary`() {
        val input = "Она закричала: как ты мог?! Он же обещал, что придёт вовремя и всё объяснит и принесёт цветы, но так и не появился в тот вечер. Она ждала его до поздней ночи у окна, глядя на пустую улицу и на телефон, но он так и не позвонил."
        assertEquals(
            listOf(
                "Она закричала: как ты мог?!",
                "Он же обещал, что придёт вовремя и всё объяснит и принесёт цветы, но так и не появился в тот вечер.",
                "Она ждала его до поздней ночи у окна, глядя на пустую улицу и на телефон, но он так и не позвонил.",
            ),
            segments(input),
        )
    }

    // --- Shadow Slave ch.3 recon fixtures -----------------------------------

    @Test
    fun `shadow slave first long paragraph splits into five`() {
        val input = "For a few minutes after that, Sunny was in a dark mood. But then he pulled himself out of it and inhaled deeply, trying to enjoy the fresh air. Indeed, air like that was hard to come by in the real world: micro dust and other pollutants made it rough and unpleasant, not to mention the general stench of the outskirts. In the better parts of the city, sophisticated filtration systems worked diligently — however, filtrated air tasted sterile and stagnant. Only the very rich had access to truly pleasant breathing."
        assertEquals(
            listOf(
                "For a few minutes after that, Sunny was in a dark mood.",
                "But then he pulled himself out of it and inhaled deeply, trying to enjoy the fresh air.",
                "Indeed, air like that was hard to come by in the real world: micro dust and other pollutants made it rough and unpleasant, not to mention the general stench of the outskirts.",
                "In the better parts of the city, sophisticated filtration systems worked diligently — however, filtrated air tasted sterile and stagnant.",
                "Only the very rich had access to truly pleasant breathing.",
            ),
            segments(input),
        )
        assertValidSpans(input)
    }

    @Test
    fun `shadow slave quoted dialogue stays one sentence`() {
        val input = "\"These berries are called Bloodbane. They grow in the places where human blood was spilled. That's why there's always a lot of them along the slave trade routes.\""
        assertEquals(listOf(input), segments(input))
        assertValidSpans(input)
    }

    @Test
    fun `shadow slave fated attribute with ellipsis stays one sentence`() {
        val input = "[Fated] Attribute Description: \"The strings of fate wrap tightly around you. Unlikely events, both good and bad, are drawn by your presence. There are those who are blessed, and there are those who are cursed… but rarely both.\""
        assertEquals(listOf(input), segments(input))
        assertValidSpans(input)
    }

    @Test
    fun `ellipsis is not a terminator`() {
        val input = "Most likely, it was a pack of monsters that were going to do the caravan in… and by the look of it, the attack was going to take place in a matter of hours, not days."
        assertEquals(listOf(input), segments(input))
    }
}
