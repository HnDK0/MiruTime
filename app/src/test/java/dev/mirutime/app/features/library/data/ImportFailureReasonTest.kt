package dev.mirutime.app.features.library.data

import dev.mirutime.core.local.DrmProtectedException
import java.io.EOFException
import java.util.zip.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Only a failure with a cause the user can act on gets a typed reason; anything else keeps its text.
class ImportFailureReasonTest {
    @Test
    fun `drm and broken archives map to their reasons`() {
        assertEquals(
            ImportFailureReason.Drm,
            failureReason(DrmProtectedException("encrypted")),
        )
        assertEquals(ImportFailureReason.BadArchive, failureReason(ZipException("zip END header not found")))
        assertEquals(
            ImportFailureReason.BadArchive,
            failureReason(EOFException("Unexpected end of ZLIB input stream")),
        )
        assertEquals(
            ImportFailureReason.BadArchive,
            failureReason(ZipException("invalid CEN header (bad entry name or comment)")),
        )
    }

    @Test
    fun `size and space keep their existing reasons`() {
        assertEquals(
            ImportFailureReason.SizeLimit(limitBytes = 10, actualBytes = 20),
            failureReason(ImportLimitException(limitBytes = 10, actualBytes = 20)),
        )
        assertEquals(
            ImportFailureReason.InsufficientSpace(requiredBytes = 30),
            failureReason(ImportNoSpaceException(requiredBytes = 30)),
        )
    }

    @Test
    fun `an unknown failure has no reason`() {
        assertNull(failureReason(IllegalStateException("META-INF/container.xml file missing")))
    }

    @Test
    fun `a mixed batch reports the reason only when nothing was added`() {
        val mixed = ImportSummary(imported = 1, failures = 1, firstFailureReason = ImportFailureReason.Drm)
        assertNull(mixed.failureToReport)

        val failedOnly = ImportSummary(failures = 1, firstFailureReason = ImportFailureReason.Drm)
        assertEquals(ImportFailureReason.Drm, failedOnly.failureToReport)

        assertNull(ImportSummary(imported = 2).failureToReport)
    }
}
