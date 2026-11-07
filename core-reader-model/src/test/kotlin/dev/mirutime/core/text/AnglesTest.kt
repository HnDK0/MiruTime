package dev.mirutime.core.text

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class AnglesTest {

    @Test
    fun `replaces both angles one to one`() {
        val text = "a <System> b > c"

        val normalized = normalizeAngles(text)

        assertEquals("a ⟨System⟩ b ⟩ c", normalized)
        assertEquals(text.length, normalized.length)
    }

    @Test
    fun `is idempotent and keeps the string when there is nothing to change`() {
        val text = "no angles here"
        assertSame(text, normalizeAngles(text))

        val once = normalizeAngles("<Alice> hi")
        assertSame(once, normalizeAngles(once))
    }

    @Test
    fun `fuzz keeps length and removes ascii angles`() {
        val random = Random(5)
        repeat(300) { seed ->
            val text = buildString {
                repeat(random.nextInt(0, 200)) {
                    append(if (random.nextBoolean()) '<' else '>')
                }
            }

            val normalized = normalizeAngles(text)

            assertEquals("seed=$seed length", text.length, normalized.length)
            assertFalse("seed=$seed ascii less-than", normalized.contains('<'))
            assertFalse("seed=$seed ascii greater-than", normalized.contains('>'))
        }
    }
}
