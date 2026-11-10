package dev.mirutime.feature.reader.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetranslateAvailabilityTest {

    @Test
    fun `enabled only with a pair, a loaded chapter and no running translation`() {
        assertTrue(
            retranslateAvailability(
                hasPair = true,
                chapterReady = true,
                translationLoading = false,
            ),
        )
        assertFalse(
            retranslateAvailability(
                hasPair = false,
                chapterReady = true,
                translationLoading = false,
            ),
        )
        assertFalse(
            retranslateAvailability(
                hasPair = true,
                chapterReady = false,
                translationLoading = false,
            ),
        )
        assertFalse(
            retranslateAvailability(
                hasPair = true,
                chapterReady = true,
                translationLoading = true,
            ),
        )
    }
}
