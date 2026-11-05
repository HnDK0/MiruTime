package dev.mirutime.app.features.reader.document

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven splitter corpus: novel formats (litRPG, chats, system windows,
 * poetry, dialogue) and scripts (Latin, Cyrillic, CJK, RTL, Indic, SEA).
 *
 * Every case also checks the span invariants: ordered, non-overlapping,
 * whitespace-only gaps and no lost text.
 */
class SentenceSplitterCorpusTest {

    private data class Case(
        val name: String,
        val text: String,
        val expected: List<String>? = null,
        val expectedCount: Int? = null,
        val skip: SplitSkip? = null,
    )

    private fun check(case: Case) {
        val plan = SentenceSplitter.splitPlan(case.text)
        val actual = plan.spans.map { case.text.substring(it.start, it.end) }
        case.expected?.let { assertEquals("case=${case.name}", it, actual) }
        case.expectedCount?.let { assertEquals("case=${case.name} count", it, actual.size) }
        case.skip?.let { assertEquals("case=${case.name} skip", it, plan.skipped) }
        if (case.expected == null && case.expectedCount == null) {
            assertEquals("case=${case.name} whole", listOf(case.text), actual)
        }
        assertSpanInvariants(case.name, case.text, plan.spans)
    }

    @Test
    fun englishAndLatinRules() {
        val cases = listOf(
            Case(
                name = "EN: short two sentences",
                text = "He walked home. The sun was setting.",
                expected = listOf("He walked home.", "The sun was setting."),
            ),
            Case(
                name = "EN: three long sentences",
                text = "He walked through the quiet streets of the old town, watching the lights flicker on in the windows of the houses he passed one by one. The sun was setting slowly behind the distant hills, painting the whole sky in warm shades of orange and pink. Birds sang their evening songs from the treetops, filling the quiet air with gentle and soothing melodies.",
                expectedCount = 3,
            ),
            Case(
                name = "EN: Dr abbreviation",
                text = "Dr. Smith went home after a long day at the clinic where he had treated many patients, written detailed reports and filled out all the paperwork for the week. He was tired but satisfied with the work he had done that day, and he was already looking forward to a quiet evening at home with a good book.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: Mr Mrs Prof abbreviations",
                text = "Mr. and Mrs. Smith arrived at Prof. Brown's house early in the morning, before the guests and before the rain, and they waited in the car. The professor greeted them at the door and invited them inside for tea and a long conversation about the university.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: No Vol pp abbreviations",
                text = "See No. 5 and Vol. 2 on pp. 10-20 for the full citation list, which covers every source used in the article and every note added by the editor. The appendix repeats the same numbers with a short explanation for each entry.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: decimals",
                text = "Pi equals 3.14 and e equals 2.71 in most textbooks, and every student learns these constants long before they study calculus or analysis. The numbers appear again in the final chapter, where the author explains why they matter.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: version numbers",
                text = "The release notes mention v1.2.3 and the older v1.0.0 build, which is still supported on devices that cannot update. The team promised a migration guide next week.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: dates",
                text = "The meeting on May 12, 2024 was moved to 12.05.2024 because the room was double-booked, and everyone received a new invitation. The organizer apologized for the confusion and promised to check the calendar twice next time.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: p.m. time",
                text = "The train leaves at 3 p.m. as usual, so we should be at the station by two thirty and wait near the main entrance. The conductor always checks the tickets before departure.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: URLs",
                text = "Read the docs at example.com/path and check the version tag v1.2.3 before you install anything on the server. The setup guide explains every step in detail.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: e.g. lowercase continuation",
                text = "Use e.g. this example when you explain the rule to beginners, because it is short and clear. The next section repeats the same idea with more detail.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: etc at sentence end (pinned no split)",
                text = "The list includes apples, pears, plums, etc. The rest of the paragraph explains how to store the fruit and how long it keeps in a cool room.",
            ),
            Case(
                name = "EN: ellipsis",
                text = "Most likely, it was a pack of monsters that were going to do the caravan in… and by the look of it, the attack was going to take place in a matter of hours, not days.",
            ),
            Case(
                name = "EN: punctuation run",
                text = "She shouted: how could you?! He promised to come on time and explain everything, but he never showed up that evening. She waited for him until late at night by the window, watching the empty street and the phone, but he never called.",
                expectedCount = 3,
            ),
            Case(
                name = "EN: quoted sentence",
                text = "He said \"Just wait right here and do not move. Things will be fine.\" Then he walked away down the long corridor without looking back even once, disappearing around the corner where the light was dim and the shadows were long and deep, and he never looked back even once.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: dash attribution after quote",
                text = "He said, \"Run!\" — and then he ran toward the exit, pushing past the crowd and looking back at every passer-by who stood in his way. She did not move and did not say a word until he disappeared around the corner.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: semicolon continuation",
                text = "The recipe needs flour, sugar, and eggs; the rest of the ingredients are optional and can be changed to taste. Mix everything slowly and bake the batter for thirty minutes at a moderate temperature.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: all caps",
                text = "THE STORM HIT THE COAST AT DAWN. EVERYONE RAN FOR SHELTER. THE OLD HARBOR WAS EMPTY BY NOON.",
                expectedCount = 3,
            ),
            Case(
                name = "EN: markdown asterisks",
                text = "He *really* meant it. She _did not_ believe him at first, but the evidence was hard to ignore. They talked for hours and finally agreed to try again.",
                expectedCount = 3,
            ),
            Case(
                name = "EN: newline inside paragraph",
                text = "He opened the door.\nThe room was dark and cold, and the window had been broken for weeks. She waited outside.",
                expectedCount = 3,
            ),
            Case(
                name = "EN: apostrophes",
                text = "John's car didn’t start this morning, so he called a mechanic and waited in the rain. The mechanic arrived an hour later and fixed the battery in ten minutes.",
                expectedCount = 2,
            ),
            Case(
                name = "EN: initials chain",
                text = "J. R. R. Tolkien wrote the books that later became a famous film trilogy, and readers still argue about the ending. The author's notes were published many years after his death.",
                expectedCount = 2,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun cyrillicAndEuropeanRules() {
        val cases = listOf(
            Case(
                name = "RU: dash dialogue whole",
                text = "— Привет, как дела? — спросил он, подходя ближе и протягивая руку для рукопожатия. — Хорошо, спасибо, — ответила она и улыбнулась, глядя ему в глаза. — Отлично, тогда пойдём прогуляемся по парку, — предложил он и показал рукой в сторону аллеи.",
                skip = SplitSkip.DASH_DIALOGUE,
            ),
            Case(
                name = "RU: dash dialogue with attributions",
                text = "— Ты пришёл? — спросила она, открывая дверь.\n— Пришёл, — ответил он и вошёл в комнату. — Извини за опоздание.\n— Ничего, — сказала она. — Проходи.",
                skip = SplitSkip.DASH_DIALOGUE,
            ),
            Case(
                name = "RU: split after closing quote",
                text = "«Я ухожу!» Она хлопнула дверью и больше не вернулась. Он остался один в пустой квартире и долго смотрел на закрытую дверь.",
                expectedCount = 3,
            ),
            Case(
                name = "RU: nested quotes",
                text = "Он прочитал записку: „Не жди меня, я вернусь поздно“, и положил её на стол, ничего не сказав. Потом он вышел на улицу и долго стоял под фонарём, глядя на дорогу.",
                expectedCount = 2,
            ),
            Case(
                name = "RU: g abbreviation",
                text = "В 2024 г. он переехал в Москву и начал работать в издательстве, которое выпускало книги о путешествиях и приключениях. Позже он написал несколько статей для журнала и выступил на конференции.",
                expectedCount = 2,
            ),
            Case(
                name = "RU: dotted chain final dot ends sentence",
                text = "Он знал математику, физику, химию и т.д. Но больше всего ему нравились языки и литература, которым он посвящал всё свободное время.",
                expectedCount = 2,
            ),
            Case(
                name = "RU: initials",
                text = "И.В. Иванов пришёл на встречу раньше всех и занял место у окна в дальнем конце кабинета, рядом с большим шкафом с документами, и стал ждать начала обсуждения. Он сел и принялся перечитывать свои заметки перед началом обсуждения, чтобы освежить в памяти все ключевые цифры и факты.",
                expectedCount = 2,
            ),
            Case(
                name = "RU: decimal comma",
                text = "Значение π примерно равно 3,14, и это число используют в школах и университетах по всему миру. Многие задачи нельзя решить без него, поэтому его изучают с самого начала курса.",
                expectedCount = 2,
            ),
            Case(
                name = "RU: date",
                text = "Дата 12.05.2024 была солнечной и тёплой, и многие люди вышли на улицы города, чтобы насладиться хорошей погодой. Он вышел из дома рано утром и отправился на долгую прогулку по набережной.",
                expectedCount = 2,
            ),
            Case(
                name = "UK: dash dialogue whole",
                text = "— Ти вже прийшов? — запитала вона, відчиняючи двері. — Я чекала на тебе цілий вечір.\n— Вибач, — відповів він. — Були справи.",
                skip = SplitSkip.DASH_DIALOGUE,
            ),
            Case(
                name = "DE: z.B. bzw. Dr. Nr.",
                text = "Dr. Müller kam am Montag, z.B. um neun Uhr, und brachte die Unterlagen für das Projekt mit. Die Ergebnisse waren besser als erwartet, bzw. genau so gut wie im letzten Jahr.",
                expectedCount = 2,
            ),
            Case(
                name = "FR: M. Dupont and guillemets",
                text = "M. Dupont est arrivé tôt ce matin et il a ouvert les fenêtres du bureau avant de commencer à travailler. « Nous avons beaucoup de choses à faire », a-t-il dit en regardant les dossiers sur la table.",
                expectedCount = 2,
            ),
            Case(
                name = "PL: np. and diacritics",
                text = "Pan Kowalski powiedział, że np. w przyszłym tygodniu podpisze umowę i zacznie pracę w nowym biurze. Potem wrócił do domu i długo rozmawiał z żoną o planach na przyszłość.",
                expectedCount = 2,
            ),
            Case(
                name = "ES: inverted question and exclamation",
                text = "¿Qué pasó? Él se fue sin decir una palabra y ella se quedó sola en la habitación. ¡Corre! Viene el tren y no hay tiempo que perder.",
                expectedCount = 4,
            ),
            Case(
                name = "PT: Sr. abbreviation",
                text = "O Sr. Silva chegou cedo e abriu a loja antes das oito, como fazia todos os dias. Depois ele tomou um café e esperou os primeiros clientes.",
                expectedCount = 2,
            ),
            Case(
                name = "TR: Dr. and vb.",
                text = "Dr. Ahmet sabah erkenden geldi ve ofisi açtı, vb. işlerle uğraştı. Daha sonra hastaneye gitti ve hastalarını muayene etti.",
                expectedCount = 2,
            ),
            Case(
                name = "RU: three long sentences",
                text = "Он вышел из дома рано утром, когда улицы ещё были пусты, и направился к реке, чтобы встретить рассвет. Солнце медленно поднималось над водой, окрашивая небо в тёплые оттенки, и в воздухе пахло сыростью и травой. Он стоял на берегу и смотрел на воду, пока не почувствовал, что наконец может вернуться домой.",
                expectedCount = 3,
            ),
            Case(
                name = "RU: italics asterisks",
                text = "Он *очень* устал, но всё равно пошёл на встречу, потому что обещал друзьям прийти. Потом он вернулся домой и сразу лёг спать, не ужиная и ни с кем не разговаривая.",
                expectedCount = 2,
            ),
            Case(
                name = "RU: unclosed quote whole",
                text = "«Он начал говорить о планах на будущее и о том, как важно не сдаваться, но внезапно замолчал и посмотрел в окно. За окном шёл дождь, и улица была совершенно пустой.",
                skip = SplitSkip.NO_BOUNDARY,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun cjkKoreanAndSeaRules() {
        val cases = listOf(
            Case(
                name = "JA: quote then prose",
                text = "「彼は言った。大丈夫だ。」 そして彼は静かに部屋を出て、暗い廊下を歩きながら、明日のことを考えていた。",
                expectedCount = 2,
            ),
            Case(
                name = "JA: whole in quotes",
                text = "「彼は遅い時間に仕事から家に帰って、とても疲れていたので、まずは冷たいお風呂に入って体を温めて、それから簡単な夕食を食べて、ベッドに入って、長い一日の出来事を一つずつ思い出しながら、明日の朝のことをもう考えないようにして、ただ静かに眠りについた。」",
                skip = SplitSkip.NO_BOUNDARY,
            ),
            Case(
                name = "ZH: quote then prose",
                text = "他说：“今天很冷，我们不要出门了。明天再去公园吧。”然后他坐下来，打开了电视。",
                expectedCount = 2,
            ),
            Case(
                name = "ZH: long no-space paragraph",
                text = "他走进房间，看见桌上放着一张纸条。窗外下着雨，街上没有一个人。他坐下来，慢慢地读完了那张纸条。",
                expectedCount = 3,
            ),
            Case(
                name = "KO: basic sentences",
                text = "그는 집으로 걸어갔다. 그녀는 창가에 서서 그를 바라보았다. 밖에는 비가 내리고 있었다.",
                expectedCount = 3,
            ),
            Case(
                name = "KO: full width terminators",
                text = "정말 큰일이야！어떻게 이럴 수가 있어？나는 믿을 수가 없어。그래도 우리는 해야 해！",
                expectedCount = 4,
            ),
            Case(
                name = "JA: mixed Latin product name",
                text = "彼はiPhone 15. Proを買った。とても高かったが、満足している。",
                expectedCount = 3,
            ),
            Case(
                name = "TH: no terminators whole",
                text = "วันนี้อากาศดีมากเราออกไปเดินเล่นที่สวนสาธารณะและนั่งคุยกันจนเย็น",
                skip = SplitSkip.NO_BOUNDARY,
            ),
            Case(
                name = "VI: diacritics",
                text = "Anh ấy thức dậy sớm và đi làm trước khi trời sáng. Trên đường, anh ấy mua một ly cà phê và vài chiếc bánh mì. Buổi chiều, anh ấy ghé thăm mẹ và ở lại ăn tối.",
                expectedCount = 3,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun rtlRules() {
        val cases = listOf(
            Case(
                name = "AR: arabic question mark",
                text = "ذهب الرجل إلى المنزل بعد يوم طويل من العمل في المدينة المزدحمة. هل أنت بخير اليوم بعد هذا اليوم الطويل والمتعب في العمل؟ نعم أنا بخير، شكراً لسؤالك اللطيف.",
                expectedCount = 3,
            ),
            Case(
                name = "AR: basic periods",
                text = "ذهب الرجل إلى المنزل بعد يوم طويل. كانت الشوارع مليئة بالناس والسيارات. جلس على الكرسي وشرب الشاي.",
                expectedCount = 3,
            ),
            Case(
                name = "FA: persian question mark",
                text = "او به خانه رفت و بعد از یک روز طولانی استراحت کرد. آیا حال او بهتر شده است؟ بله، او حالا خوب است و به کار خود ادامه می‌دهد.",
                expectedCount = 3,
            ),
            Case(
                name = "UR: urdu full stop",
                text = "وہ گھر چلا گیا اور سارا دن کام کرتا رہا۔ کیا تم ٹھیک ہو؟ ہاں، میں ٹھیک ہوں اور اب آرام کروں گا۔",
                expectedCount = 3,
            ),
            Case(
                name = "AR: arabic-indic decimal digits",
                text = "قيمة باي تساوي ٣.١٤ تقريباً في معظم الكتب، ويتعلمها الطلاب في المدرسة. ثم يستخدمونها في المسائل والاختبارات.",
                expectedCount = 2,
            ),
            Case(
                name = "HE: hebrew periods",
                text = "הוא קם מוקדם בבוקר ויצא לעבודה לפני שהשמש זרחה. בדרך הוא קנה קפה ולחם, והגיע למשרד בזמן. בערב הוא חזר הביתה ועבר לקרוא ספר.",
                expectedCount = 3,
            ),
            Case(
                name = "AR+EN: mixed scripts",
                text = "بدأ الفريق مشروعه الجديد في 2024. The first release was delayed, but the team kept working. وبعد شهرين، اكتمل الإصدار الأول.",
                expectedCount = 3,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun indicAndOtherScripts() {
        val cases = listOf(
            Case(
                name = "HI: danda and double danda",
                text = "वह सुबह जल्दी उठा और काम पर चला गया। रास्ते में उसने चाय पी। शाम को वह घर लौटा और आराम किया॥",
                expectedCount = 3,
            ),
            Case(
                name = "BN: bengali danda",
                text = "সে সকালে তাড়াতাড়ি উঠে কাজে চলে গেল। পথে সে চা খেল। সন্ধ্যায় সে বাড়ি ফিরে বিশ্রাম নিল।",
                expectedCount = 3,
            ),
            Case(
                name = "HI+EN: devanagari mixed with latin",
                text = "उसने Python में एक नया प्रोग्राम लिखा। Program बहुत तेज़ चला। उसने इसे दोस्तों को दिखाया।",
                expectedCount = 3,
            ),
            Case(
                name = "HY: armenian full stop",
                text = "Նա վաղ առավոտյան վեր կացավ և գնաց աշխատանքի։ Ճանապարհին նա սուրճ խմեց։ Երեկոյան նա տուն վերադարձավ և հանգստացավ։",
                expectedCount = 3,
            ),
            Case(
                name = "MY: burmese section mark",
                text = "သူ မနက်စောစော ထပြီး အလုပ်သွားတယ်။ လမ်းမှာ ကော်ဖီသောက်တယ်။ ညနေ အိမ်ပြန်ပြီး အနားယူတယ်။",
                expectedCount = 3,
            ),
            Case(
                name = "AM: ethiopic full stop",
                text = "እሱ ጠዋት በማለዳ ተነስቶ ወደ ሥራ ሄደ። በመንገድ ላይ ቡና ጠጣ። ምሽት ላይ ወደ ቤት ተመልሶ አረፈ።",
                expectedCount = 3,
            ),
            Case(
                name = "EL: greek periods",
                text = "Ο Γιάννης ξύπνησε νωρίς και πήγε στη δουλειά. Στο δρόμο ήπιε καφέ. Το βράδυ γύρισε σπίτι και ξεκουράστηκε.",
                expectedCount = 3,
            ),
            Case(
                name = "TA: no terminators whole",
                text = "அவர் காலையில் எழுந்து வேலைக்குச் சென்றார் பின்னர் மாலையில் வீட்டிற்குத் திரும்பினார்",
                skip = SplitSkip.NO_BOUNDARY,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun litRpgSystemAndStatusBlocks() {
        val cases = listOf(
            Case(
                name = "litRPG: key value block",
                text = "Имя: Джон\nУровень: 5\nHP: 120/120\nMP: 100/100\nСила: 15\nЛовкость: 12",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "litRPG: divider",
                text = "Глава первая: Встреча\n***\nОн шёл домой и думал о том, что произошло сегодня на работе, и не мог найти ответа на вопрос, который мучил его весь вечер.",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "litRPG: repeated char divider",
                text = "........\nОн шёл домой и думал о том, что произошло сегодня на работе, и не мог найти ответа на вопрос, который мучил его весь вечер.",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "litRPG: stat block with emoji",
                text = "🔥 HP: 120/120\nMP: 100/100\nСила: 15\nЛовкость: 12",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "litRPG: double frame",
                text = "╔══════════════════════════════════════════════════════╗\n║ A very long framed message that should stay untouched ║\n╚══════════════════════════════════════════════════════╝",
                skip = SplitSkip.FRAME_BLOCK,
            ),
            Case(
                name = "litRPG: single frame",
                text = "┌──────────────────────────────────────────────────────┐\n│ QUEST COMPLETE: the ancient relic has been returned │\n└──────────────────────────────────────────────────────┘",
                skip = SplitSkip.FRAME_BLOCK,
            ),
            Case(
                name = "litRPG: system message",
                text = "[System] You have leveled up! Reward: 100 gold. New skill: Fireball.",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "litRPG: quest log",
                text = "[Quest] The Lost Relic\n[Objective] Find the relic in the northern ruins.\n[Reward] 500 gold and 1 skill point.",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "litRPG: skill description",
                text = "[Skill: Fireball] \"Deals 50 damage over 3 seconds. Costs 20 MP.\"",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "litRPG: single stat line",
                text = "Level: 5",
                skip = SplitSkip.NO_BOUNDARY,
            ),
            Case(
                name = "litRPG: two sentences without prefix",
                text = "You gained 100 XP. You gained 50 gold.",
                expectedCount = 2,
            ),
            Case(
                name = "litRPG: status line followed by prose",
                text = "HP: 100\nHe stood up and looked around the room, then he walked to the door. He opened it slowly.",
                expectedCount = 2,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun chatAndLogFormats() {
        val cases = listOf(
            Case(
                name = "chat: timed multi line",
                text = "[12:34] Alice: Are you there? Hello?\n[12:35] Bob: Yes. What happened?\n[12:36] Alice: Nothing. I just wanted to talk.",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "chat: angle names multi line",
                text = "<Alice> Are you there? Hello?\n<Bob> Yes. What happened?",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "chat: bold names multi line",
                text = "**Alice** Are you there? Hello?\n**Bob** Yes. What happened?",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "chat: named multi line",
                text = "Alice: Are you there? Hello?\nBob: Yes. What happened?",
            ),
            Case(
                name = "chat: single timed line",
                text = "[12:34] Alice: Hi! You there? It's me, the one who called yesterday.",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "chat: single angle line",
                text = "<Alice> Hi! You there? It's me, the one who called yesterday.",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "chat: single named line is prose",
                text = "Alice: Hi! You there? It's me, the one who called yesterday.",
                expectedCount = 3,
            ),
            Case(
                name = "chat: prose with colon is not a log",
                text = "Он сказал: Привет. Как дела? Я давно тебя не видел, и мне есть что рассказать тебе сегодня вечером.",
                expectedCount = 3,
            ),
            Case(
                name = "chat: danmaku line",
                text = "[00:12] LOL! Nice one! [00:13] Indeed. [00:15] Absolutely brilliant.",
                skip = SplitSkip.LOG_BLOCK,
            ),
            Case(
                name = "chat: line followed by narration",
                text = "[12:34] Alice: Are you there? Hello?\nHe closed the phone and looked at the window. The street was empty.",
                expectedCount = 4,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun dialogueQuotesAndBrackets() {
        val cases = listOf(
            Case(
                name = "dialogue: dash lines whole",
                text = "— Я не вернусь, — сказала она и закрыла дверь.\n— Подожди, — крикнул он и побежал за ней. — Я всё объясню.",
                skip = SplitSkip.DASH_DIALOGUE,
            ),
            Case(
                name = "dialogue: quote with dash attribution",
                text = "«Беги!» — крикнул он и побежал к выходу, лавируя между машинами и оглядываясь назад на каждого прохожего, который попадался ему на пути, пока бежал через парковку. «Стой!» — закричала она ему вслед. Он не остановился и даже не замедлил шаг, и вскоре скрылся из виду.",
                expectedCount = 3,
            ),
            Case(
                name = "dialogue: nested quotes",
                text = "He said, \"She told me 'wait here' and left.\" Then he waited for an hour in the cold room.",
                expectedCount = 2,
            ),
            Case(
                name = "dialogue: brackets with periods",
                text = "Let me explain the situation in full detail (see the attached note on the previous page. It matters a great deal for what follows in this chapter). The next sentence starts right here.",
                expectedCount = 2,
            ),
            Case(
                name = "dialogue: unclosed bracket",
                text = "He opened the door (and then stopped. The room was empty and cold, and the window had been broken for weeks.",
                skip = SplitSkip.NO_BOUNDARY,
            ),
            Case(
                name = "dialogue: unbalanced quote",
                text = "He said \"I will be back soon. The train is late and the station is crowded with people who are also waiting.",
                skip = SplitSkip.NO_BOUNDARY,
            ),
            Case(
                name = "dialogue: ellipsis in quote",
                text = "«Я не знаю…» — тихо сказала она и посмотрела в окно. «Может быть, он вернётся завтра».",
                expectedCount = 2,
            ),
            Case(
                name = "dialogue: question exclamation in quote",
                text = "«Ты серьёзно?!» — спросил он, не веря своим ушам. «Да, вполне».",
                expectedCount = 2,
            ),
            Case(
                name = "dialogue: quoted lines via newline",
                text = "«Привет!»\n«Привет, как дела?»\n«Хорошо, спасибо.»",
                expectedCount = 3,
            ),
            Case(
                name = "dialogue: quote then narration",
                text = "«Он ушёл», — сказала она и посмотрела на пустую комнату. Больше она его не видела.",
                expectedCount = 2,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun poetryAndLayout() {
        val cases = listOf(
            Case(
                name = "poetry: haiku without terminators",
                text = "an old silent pond\na frog jumps into the pond\nsplash and silence again",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "poetry: verse with commas only",
                text = "Я помню чудное мгновенье,\nПередо мной явилась ты,\nКак мимолётное виденье,\nКак гений чистой красоты.",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "poetry: verse with periods",
                text = "Первая строка стиха.\nВторая строка стиха.\nТретья строка стиха.",
                expectedCount = 3,
            ),
            Case(
                name = "layout: title and subtitle",
                text = "Глава первая\nВстреча в старом городе",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "layout: scene divider",
                text = "Он вошёл в комнату и остановился.\n***\nЗа окном шёл дождь.",
                skip = SplitSkip.STATUS_BLOCK,
            ),
            Case(
                name = "layout: crlf and nbsp",
                text = "Он вошёл в комнату и остановился.\r\nОна подняла голову\u00A0и посмотрела на него.",
                expectedCount = 2,
            ),
            Case(
                name = "layout: ten thousand chars",
                text = (1..1500).joinToString(" ") { "Слово$it." },
                expectedCount = 1500,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun malformedAndEdgeInputs() {
        val cases = listOf(
            Case(name = "edge: empty", text = "", expected = emptyList()),
            Case(name = "edge: whitespace only", text = "   \n\t ", expected = emptyList()),
            Case(name = "edge: single char", text = "A", skip = SplitSkip.NO_BOUNDARY),
            Case(name = "edge: only punctuation", text = "?!...", skip = SplitSkip.TRIVIAL_BOUNDARY),
            Case(name = "edge: only emoji", text = "🌅🌅", skip = SplitSkip.FRAME_BLOCK),
            Case(
                name = "edge: zero width and soft hyphen",
                text = "Он\u200Bвошёл\u00ADв комнату и остановился. Она посмотрела на него.",
                expectedCount = 2,
            ),
            Case(
                name = "edge: surrogate emoji",
                text = "Он улыбнулся и помахал рукой 👋. Она засмеялась и помахала в ответ.",
                expectedCount = 2,
            ),
            Case(
                name = "edge: leading terminator",
                text = ". Hello. World.",
                expected = listOf(".", "Hello.", "World."),
            ),
            Case(
                name = "edge: ends with terminator",
                text = "He walked home.",
                expected = listOf("He walked home."),
            ),
            Case(
                name = "edge: many boundaries",
                text = (1..150).joinToString(" ") { "S$it." },
                expectedCount = 150,
            ),
        )
        cases.forEach(::check)
    }

    @Test
    fun realisticChapters() {
        val chapters = mapOf(
            "EN litRPG" to listOf(
                Case(
                    name = "EN chapter: stat block",
                    text = "Name: Sunny\nLevel: 12\nHP: 320/320\nMP: 180/180\nStrength: 24",
                    skip = SplitSkip.STATUS_BLOCK,
                ),
                Case(
                    name = "EN chapter: system message",
                    text = "[System] You gained 250 XP. A new skill is available: Shadow Step.",
                    skip = SplitSkip.LOG_BLOCK,
                ),
                Case(
                    name = "EN chapter: prose",
                    text = "Sunny stepped into the dark corridor and listened to the silence. Somewhere far below, water dripped onto stone, and the sound echoed through the empty hall.",
                    expectedCount = 2,
                ),
                Case(
                    name = "EN chapter: quoted dialogue",
                    text = "\"We should not be here,\" whispered Cassie, looking back at the sealed door. \"The guards will notice the missing key.\"",
                    expectedCount = 2,
                ),
            ),
            "RU ranobe" to listOf(
                Case(
                    name = "RU chapter: description",
                    text = "Он вышел на балкон и посмотрел на спящий город. Фонари горели ровно, а где-то далеко шумела ночная дорога.",
                    expectedCount = 2,
                ),
                Case(
                    name = "RU chapter: dash dialogue",
                    text = "— Ты правда уходишь? — спросила она, не поднимая глаз.\n— Да, — ответил он. — Так будет лучше для нас обоих.",
                    skip = SplitSkip.DASH_DIALOGUE,
                ),
                Case(
                    name = "RU chapter: thought",
                    text = "«Я вернусь, когда всё уляжется», — подумал он и закрыл дверь. На улице было тихо, и только дождь стучал по крышам.",
                    expectedCount = 2,
                ),
                Case(
                    name = "RU chapter: status",
                    text = "Уровень: 12\nОпыт: 3400/5000",
                    skip = SplitSkip.STATUS_BLOCK,
                ),
            ),
            "ZH and JA" to listOf(
                Case(
                    name = "ZH chapter: quote and narration",
                    text = "他走进房间，看见桌上放着一张纸条：“请把门关上。我在楼下等你。”他没有回头，直接下了楼。",
                    expectedCount = 2,
                ),
                Case(
                    name = "ZH chapter: long paragraph",
                    text = "他走进房间，看见桌上放着一张纸条。窗外下着雨，街上没有一个人。他坐下来，慢慢地读完了那张纸条。",
                    expectedCount = 3,
                ),
                Case(
                    name = "JA chapter: quoted block",
                    text = "「彼は遅い時間に仕事から家に帰って、とても疲れていたので、まずは冷たいお風呂に入って体を温めて、それから簡単な夕食を食べて、ベッドに入って、長い一日の出来事を一つずつ思い出しながら、明日の朝のことをもう考えないようにして、ただ静かに眠りについた。」",
                    skip = SplitSkip.NO_BOUNDARY,
                ),
                Case(
                    name = "JA chapter: prose",
                    text = "彼は静かに部屋を出て、暗い廊下を歩きながら、明日のことを考えていた。電車はもうホームに来ていて、たくさんの人々が慌ただしく乗り降りしていた。",
                    expectedCount = 2,
                ),
            ),
            "chat heavy" to listOf(
                Case(
                    name = "chat chapter: timed block",
                    text = "[12:34] Alice: Are you there? Hello?\n[12:35] Bob: Yes. What happened?\n[12:36] Alice: Nothing. I just wanted to talk.",
                    skip = SplitSkip.LOG_BLOCK,
                ),
                Case(
                    name = "chat chapter: angle block",
                    text = "<Bob> Did you see the news? It is everywhere.\n<Alice> Yes. I cannot believe it.",
                    skip = SplitSkip.LOG_BLOCK,
                ),
                Case(
                    name = "chat chapter: narration",
                    text = "He closed the phone and looked at the window. The street was empty, and the rain had stopped.",
                    expectedCount = 2,
                ),
                Case(
                    name = "chat chapter: prose with colon",
                    text = "Он сказал: Привет. Как дела? Я давно тебя не видел, и мне есть что рассказать тебе сегодня вечером.",
                    expectedCount = 3,
                ),
            ),
        )
        chapters.forEach { (chapter, cases) ->
            cases.forEach { case -> check(case.copy(name = "$chapter / ${case.name}")) }
        }
    }
}
