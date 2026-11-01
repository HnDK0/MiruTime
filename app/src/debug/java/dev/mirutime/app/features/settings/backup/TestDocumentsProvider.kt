package dev.mirutime.app.features.settings.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal in-memory... well, on-disk SAF provider for instrumentation tests.
 * It supports exactly what SafBackupTree needs: tree/document queries,
 * create, rename and delete.
 */
class TestDocumentsProvider : DocumentsProvider() {
    private lateinit var root: File

    override fun onCreate(): Boolean {
        root = File(context!!.cacheDir, DirectoryName).apply {
            deleteRecursively()
            mkdirs()
        }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DefaultRootProjection)
        val row = cursor.newRow()
        (projection ?: DefaultRootProjection).forEach { column ->
            row.add(
                column,
                when (column) {
                    Root.COLUMN_ROOT_ID -> RootId
                    Root.COLUMN_DOCUMENT_ID -> RootId
                    Root.COLUMN_TITLE -> "Test documents"
                    Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
                    Root.COLUMN_MIME_TYPES -> "application/zip"
                    else -> null
                },
            )
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DefaultDocumentProjection)
        addDocumentRow(cursor, projection, documentId)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DefaultDocumentProjection)
        val parent = fileFor(parentDocumentId)
        parent.listFiles()?.sortedBy { it.name }?.forEach { child ->
            addDocumentRow(cursor, projection, child.name)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(
        fileFor(documentId),
        ParcelFileDescriptor.parseMode(mode),
    )

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = fileFor(parentDocumentId)
        val file = File(parent, displayName)
        if (!file.createNewFile()) throw FileNotFoundException("File already exists: $displayName")
        return file.name
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = fileFor(documentId)
        val target = File(source.parentFile, displayName)
        if (!source.renameTo(target)) throw FileNotFoundException("Cannot rename $documentId")
        return target.name
    }

    override fun deleteDocument(documentId: String) {
        if (!fileFor(documentId).delete()) {
            throw FileNotFoundException("Cannot delete $documentId")
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId != RootId && parentDocumentId == RootId

    private fun addDocumentRow(
        cursor: MatrixCursor,
        projection: Array<out String>?,
        documentId: String,
    ) {
        val file = fileFor(documentId)
        val isRoot = documentId == RootId
        val row = cursor.newRow()
        (projection ?: DefaultDocumentProjection).forEach { column ->
            row.add(
                column,
                when (column) {
                    Document.COLUMN_DOCUMENT_ID -> documentId
                    Document.COLUMN_DISPLAY_NAME -> if (isRoot) "Test documents" else file.name
                    Document.COLUMN_MIME_TYPE -> if (file.isDirectory) {
                        Document.MIME_TYPE_DIR
                    } else {
                        "application/zip"
                    }
                    Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                    Document.COLUMN_SIZE -> file.length()
                    Document.COLUMN_FLAGS -> if (file.isDirectory) {
                        Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_RENAME
                    } else {
                        Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
                    }
                    else -> null
                },
            )
        }
    }

    private fun fileFor(documentId: String): File {
        if (documentId == RootId) return root
        require(!documentId.contains('/')) { "Unsupported test document id: $documentId" }
        return File(root, documentId)
    }

    companion object {
        // Must match the debug manifest authority (${applicationId}); a debug
        // build installs next to release, so a hardcoded package would collide.
        val Authority = dev.mirutime.app.BuildConfig.APPLICATION_ID + ".test.documents"
        const val RootId = "root"
        const val DirectoryName = "saf-test-documents"

        private val DefaultRootProjection = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )
        private val DefaultDocumentProjection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )

        fun treeUri(): Uri = DocumentsContract.buildTreeDocumentUri(Authority, RootId)
    }
}
