package dev.mirutime.app.features.book.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF-only writer for a CBZ export. Documents are always addressed by tree or
 * document URIs; no raw filesystem path is ever derived from the picked folder.
 * A directory is created under the tree root, then one document per archive.
 */
internal class SafCbzOutput(
    context: Context,
    private val treeUri: Uri,
) : CbzExportTarget {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun beginFolder(suggestedName: String): String = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val name = uniqueChildName(parent, suggestedName)
        val created = DocumentsContract.createDocument(
            resolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("Cannot create the manga folder")
        created.toString()
    }

    override suspend fun writeMetadata(folderHandle: String, xml: ByteArray) =
        withContext(Dispatchers.IO) {
            val folder = Uri.parse(folderHandle)
            val name = uniqueChildName(folder, "ComicInfo.xml")
            val document = DocumentsContract.createDocument(resolver, folder, "text/xml", name)
                ?: error("Cannot create ComicInfo.xml")
            resolver.openOutputStream(document)?.use { it.write(xml) }
                ?: error("Cannot write ComicInfo.xml")
        }

    override suspend fun writeCover(
        folderHandle: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Unit = withContext(Dispatchers.IO) {
        val folder = Uri.parse(folderHandle)
        val name = uniqueChildName(folder, fileName)
        val document = DocumentsContract.createDocument(resolver, folder, mimeType, name)
            ?: error("Cannot create $name")
        resolver.openOutputStream(document)?.use { it.write(bytes) }
            ?: error("Cannot write $name")
    }

    override suspend fun writeChapter(folderHandle: String, fileName: String, file: File): Unit =
        withContext(Dispatchers.IO) {
            val folder = Uri.parse(folderHandle)
            val name = uniqueChildName(folder, fileName)
            // AOSP appends ".zip" to the display name for any zip MIME type, but
            // the default type keeps the ".cbz" extension the reader expects.
            val document = DocumentsContract.createDocument(
                resolver,
                folder,
                OCTET_STREAM,
                name,
            ) ?: error("Cannot create $name")
            resolver.openOutputStream(document)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("Cannot write $name")
        }

    // A second export must not overwrite or silently merge into the first.
    private fun uniqueChildName(parent: Uri, requested: String): String {
        val existing = childNames(parent)
        if (requested !in existing) return requested
        val extension = requested.substringAfterLast('.', "")
        val base = if (extension.isEmpty()) requested else requested.dropLast(extension.length + 1)
        var index = 2
        while (true) {
            val candidate = if (extension.isEmpty()) {
                "$base ($index)"
            } else {
                "$base ($index).$extension"
            }
            if (candidate !in existing) return candidate
            index++
        }
    }

    private fun childNames(parent: Uri): Set<String> {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent),
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let(::add)
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}

// AOSP's FileUtils.splitFileName special-cases this type and keeps the name as
// is; "application/zip" would get ".zip" appended.
private const val OCTET_STREAM = "application/octet-stream"
