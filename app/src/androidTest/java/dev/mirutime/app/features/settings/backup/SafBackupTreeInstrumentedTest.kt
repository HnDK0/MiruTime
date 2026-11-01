package dev.mirutime.app.features.settings.backup

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real DocumentsContract path against a small SAF provider:
 * createDocument must receive the parent document URI (the tree URI is
 * rejected by standard providers), and `.part` documents must be renamed only
 * after a complete archive exists.
 */
@RunWith(AndroidJUnit4::class)
class SafBackupTreeInstrumentedTest {
    private lateinit var context: Context
    private val tree: Uri get() = TestDocumentsProvider.treeUri()

    @Before
    fun setUp() {
        // DocumentsProvider is intentionally MANAGE_DOCUMENTS-protected; the
        // instrumentation process borrows the shell identity to exercise it.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .adoptShellPermissionIdentity(Manifest.permission.MANAGE_DOCUMENTS)
        context = ApplicationProvider.getApplicationContext()
        SafBackupTree.listBackups(context, tree).forEach { file ->
            SafBackupTree.delete(context, tree, file)
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .dropShellPermissionIdentity()
    }

    @Test
    fun partArchiveIsCreatedRenamedAndReadable() = runBlocking {
        val finalName = "mirutime-auto-20261024-120000.zip"
        val part = SafBackupTree.createBackupPart(context, tree, finalName)

        assertEquals(BackupRotation.partName(finalName), displayName(part))
        context.contentResolver.openOutputStream(part, "w")!!.use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"formatVersion":1}""".toByteArray())
                zip.closeEntry()
            }
        }

        val beforeRename = context.contentResolver.openInputStream(part)!!.use { it.readBytes() }
        assertTrue(beforeRename.isNotEmpty())
        assertEquals(
            listOf(BackupRotation.partName(finalName)),
            SafBackupTree.listBackups(context, tree).map { it.displayName },
        )

        val completed = SafBackupTree.finalizeBackup(context, part, finalName)

        assertEquals(finalName, displayName(completed))
        assertTrue(BackupRotation.isAppBackup(finalName))
        val names = SafBackupTree.listBackups(context, tree).map { it.displayName }
        assertEquals(listOf(finalName), names)
    }

    @Test
    fun stalePartsAreCleanedUpButFreshOnesAreNot() = runBlocking {
        val finalName = "mirutime-auto-20261024-120001.zip"
        val part = SafBackupTree.createBackupPart(context, tree, finalName)
        context.contentResolver.openOutputStream(part, "w")!!.use { it.write(byteArrayOf(1, 2, 3)) }
        val partName = BackupRotation.partName(finalName)

        assertEquals(0, SafBackupTree.cleanupParts(context, tree))
        assertTrue(SafBackupTree.listBackups(context, tree).any { it.displayName == partName })

        assertEquals(
            1,
            SafBackupTree.cleanupParts(
                context,
                tree,
                now = System.currentTimeMillis() + BackupRotation.StalePartMillis,
            ),
        )
        assertFalse(SafBackupTree.listBackups(context, tree).any { it.displayName == partName })
    }

    @Test
    fun rotationLeavesRecentAndInProgressDocumentsAlone() {
        SafBackupTree.createBackupDocument(
            context,
            tree,
            "mirutime-auto-20261024-120002.zip",
        )
        SafBackupTree.createBackupDocument(
            context,
            tree,
            "mirutime-backup-20261024-120003.zip",
        )
        SafBackupTree.createBackupPart(
            context,
            tree,
            "mirutime-auto-20261024-120004.zip",
        )

        assertEquals(0, SafBackupTree.rotate(context, tree, maxCount = 0))
        assertEquals(3, SafBackupTree.listBackups(context, tree).size)
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
