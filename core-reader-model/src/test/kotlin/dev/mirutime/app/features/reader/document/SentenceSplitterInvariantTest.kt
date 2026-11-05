package dev.mirutime.app.features.reader.document

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deterministic fuzz: generated inputs from several scripts, checking only
 * invariants (no crash, ordered spans, whitespace-only gaps, no lost text).
 */
class SentenceSplitterInvariantTest {

    private val latin = "The quick brown fox jumps over the lazy dog. It was late! Was it? Yes; maybe: no,"
    private val cyrillic = "Он пришёл домой поздно. Она уже спала? «Неужели!» — спросил он; и замер: тихо,"
    private val cjk = "彼は家に帰った。彼女はまだ起きていた！本当ですか？はい、そうです。"
    private val rtl = "ذهب الرجل إلى البيت. هل أنت بخير؟ نعم، أنا بخير۔ ٣.١٤"
    private val emoji = "🌅🔥😀👋✨★─╔║🙂"

    private val alphabets = listOf(
        "latin" to latin,
        "cyrillic" to cyrillic,
        "cjk" to cjk,
        "rtl" to rtl,
        "emoji" to emoji,
    )

    @Test
    fun generatedInputsKeepSpanInvariants() {
        for ((label, alphabet) in alphabets) {
            for (seed in 0 until 100) {
                val text = generate(alphabet, seed)
                val plan = SentenceSplitter.splitPlan(text)
                assertSpanInvariants("$label seed=$seed", text, plan.spans)

                val again = SentenceSplitter.splitPlan(text)
                assertEquals("$label seed=$seed deterministic spans", plan.spans, again.spans)
                assertEquals("$label seed=$seed deterministic skip", plan.skipped, again.skipped)
            }
        }
    }

    private fun generate(alphabet: String, seed: Int): String {
        val random = Random(seed.toLong() * 31 + alphabet.length)
        val length = random.nextInt(201)
        val out = StringBuilder(length)
        repeat(length) {
            out.append(alphabet[random.nextInt(alphabet.length)])
        }
        return out.toString()
    }

}
