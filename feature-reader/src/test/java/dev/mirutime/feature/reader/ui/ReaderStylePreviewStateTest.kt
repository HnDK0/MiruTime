package dev.mirutime.feature.reader.ui

import dev.mirutime.feature.reader.ReaderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderStylePreviewStateTest {

    private val committed = ReaderStyle.Default
    private val preview = committed.copy(fontSizeSp = 24, marginStartDp = 40)

    @Test
    fun `preview changes effective style but not committed`() {
        val state = ReaderStylePreviewState(committed).preview(preview)

        assertEquals(committed, state.committed)
        assertEquals(24, state.effective.fontSizeSp)
        assertEquals(40, state.effective.marginStartDp)
    }

    @Test
    fun `commit keeps the previewed style as committed`() {
        val state = ReaderStylePreviewState(committed).preview(preview).commit()

        assertEquals(24, state.committed.fontSizeSp)
        assertNull(state.preview)
        assertEquals(24, state.effective.fontSizeSp)
    }

    @Test
    fun `cancel returns the last committed style`() {
        val state = ReaderStylePreviewState(committed).preview(preview).cancel()

        assertEquals(committed, state.committed)
        assertNull(state.preview)
        assertEquals(committed.fontSizeSp, state.effective.fontSizeSp)
    }

    @Test
    fun `effective style is sanitized`() {
        val state = ReaderStylePreviewState(committed)
            .preview(preview.copy(fontSizeSp = 999, marginStartDp = 999))

        assertEquals(32, state.effective.fontSizeSp)
        assertEquals(100, state.effective.marginStartDp)
    }
}
