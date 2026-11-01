package dev.mirutime.app.features.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImportMembershipTest {

    @Test
    fun `catalogue open imports transiently without library membership`() {
        val membership = localImportMembership(addToLibrary = false, timestamp = 123)

        assertFalse(membership.inLibrary)
        assertNull(membership.addedToLibraryAt)
    }

    @Test
    fun `import all and explicit add keep library membership`() {
        val membership = localImportMembership(addToLibrary = true, timestamp = 123)

        assertTrue(membership.inLibrary)
        assertEquals(123L, membership.addedToLibraryAt)
    }
}
