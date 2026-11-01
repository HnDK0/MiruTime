package dev.mirutime.app.features.settings.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF-only backup storage. Backup files are always addressed by tree/document
 * URIs; no raw filesystem path is ever derived from the persisted setting.
 */
object SafBackupTree {
    fun isTreeUri(uriString: String?): Boolean {
        val raw = uriString?.trim().orEmpty()
        if (!raw.startsWith("content://")) return false
        return runCatching { DocumentsContract.isTreeUri(Uri.parse(raw)) }.getOrDefault(false)
    }

    /**
     * Persists the read/write grant obtained from OpenDocumentTree. The caller
     * (settings UI) owns the activity result; this helper only records it.
     */
    fun persistPermission(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun hasPersistedPermission(context: Context, uriString: String?): Boolean {
        if (!isTreeUri(uriString)) return false
        val uri = Uri.parse(uriString)
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }

    /**
     * Human-readable folder name for the settings row. Raw tree URIs are never
     * shown to the user; a provider that cannot answer falls back to the UI's
     * generic "selected folder" label.
     */
    fun displayName(context: Context, uriString: String?): String? {
        if (!isTreeUri(uriString)) return null
        val treeUri = Uri.parse(uriString)
        return runCatching {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * createDocument expects the *parent document* URI, not the tree URI.
     * Passing the tree URI is rejected by standard providers.
     */
    fun createBackupDocument(context: Context, treeUri: Uri, displayName: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            MimeType,
            displayName,
        ) ?: error("Cannot create a backup document in the selected folder")
    }

    /**
     * Starts a backup under `name.zip.part`. Process death can only leave a
     * partial file that rotation ignores; it becomes the final name after the
     * archive has been written and validated.
     */
    fun createBackupPart(context: Context, treeUri: Uri, finalName: String): Uri =
        createBackupDocument(context, treeUri, BackupRotation.partName(finalName))

    /** Renames a validated `.part` document to its final app backup name. */
    fun finalizeBackup(context: Context, document: Uri, finalName: String): Uri =
        DocumentsContract.renameDocument(context.contentResolver, document, finalName)
            ?: error("Cannot finalize the backup document")

    /** Deletes stale app-named `.part` documents from a previous run. */
    fun cleanupParts(
        context: Context,
        treeUri: Uri,
        now: Long = System.currentTimeMillis(),
    ): Int = listBackups(context, treeUri)
        .filter { file ->
            BackupRotation.isAppPart(file.displayName) &&
                BackupRotation.isStalePart(file, now)
        }
        .count { delete(context, treeUri, it) }

    fun listBackups(context: Context, treeUri: Uri): List<BackupFileInfo> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<BackupFileInfo>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val modifiedColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                result += BackupFileInfo(
                    documentId = cursor.getString(idColumn) ?: continue,
                    displayName = name,
                    lastModified = if (modifiedColumn >= 0) {
                        cursor.getLong(modifiedColumn)
                    } else {
                        0L
                    },
                )
            }
        }
        return result
    }

    fun delete(context: Context, treeUri: Uri, file: BackupFileInfo): Boolean =
        runCatching {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId),
            )
        }.getOrDefault(false)

    /** Deletes old completed app-named backups; returns the number removed. */
    fun rotate(
        context: Context,
        treeUri: Uri,
        maxCount: Int,
        excludedNames: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): Int {
        val stale = BackupRotation.selectForDeletion(
            files = listBackups(context, treeUri),
            maxCount = maxCount,
            excludedNames = excludedNames,
            now = now,
        )
        return stale.count { delete(context, treeUri, it) }
    }

    private const val MimeType = "application/zip"
}
