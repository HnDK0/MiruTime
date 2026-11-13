package dev.mirutime.app.features.finder.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.mirutime.app.features.local.data.MAX_LOCAL_SCAN_DEPTH
import dev.mirutime.app.features.local.data.naturalCompare
import dev.mirutime.app.features.settings.data.mergeFolders

// `uri` is the concrete document URI (built with the tree URI), not only a
// display name: the importer reads bytes from it directly. Size/mtime are
// best-effort metadata for the live catalogue.
data class LocalFolderFile(
    val name: String,
    val format: String,
    val uri: Uri,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
)

private const val LOCAL_FOLDER_PREFS = "local_source_folders"
private const val KIND_BOOKS = "books"
private const val KIND_MANGA = "manga"

/** Durable folder list seam; tests use an in-memory implementation. */
interface LocalFolderStore {
    fun get(kind: String): Set<String>

    /** Must commit durably; false means the platform refused the write. */
    fun put(kind: String, values: Set<String>): Boolean
}

class SharedPrefsLocalFolderStore(context: Context) : LocalFolderStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(LOCAL_FOLDER_PREFS, Context.MODE_PRIVATE)

    override fun get(kind: String): Set<String> =
        prefs.getStringSet(kind, emptySet()).orEmpty()

    override fun put(kind: String, values: Set<String>): Boolean =
        prefs.edit().putStringSet(kind, values).commit()
}

/** Backup/restore of the folder list; merge keeps folders added after the archive. */
class LocalFolderBackup(private val store: LocalFolderStore) {
    fun snapshot(): Pair<List<String>, List<String>> =
        store.get(KIND_BOOKS).sorted() to store.get(KIND_MANGA).sorted()

    fun restore(books: List<String>, manga: List<String>) {
        restoreKind(KIND_BOOKS, books)
        restoreKind(KIND_MANGA, manga)
    }

    private fun restoreKind(kind: String, incoming: List<String>) {
        val merged = mergeFolders(store.get(kind), incoming)
        if (!store.put(kind, merged)) error("Could not restore local folders")
    }
}

// SAF folders added by the user for the two built-in local sources. URIs live
// in SharedPreferences per kind ("books" / "manga"); the files themselves are
// never copied, only walked with DocumentsContract on demand.
// shortcut: plain SharedPreferences + StringSet, DataStore if we need flows.
class LocalSourceFolders(
    context: Context,
    private val registry: LocalSafRegistry,
    private val store: LocalFolderStore = SharedPrefsLocalFolderStore(context.applicationContext),
) {
    private val context = context.applicationContext
    private val folderBackup = LocalFolderBackup(store)

    fun uris(kind: String): List<Uri> =
        store.get(kind).sorted().map(Uri::parse)

    fun snapshotFolders(): Pair<List<String>, List<String>> = folderBackup.snapshot()

    fun restoreFolders(books: List<String>, manga: List<String>) =
        folderBackup.restore(books, manga)

    // The persisted grant is the only permission that survives a restart, so a
    // denied/non-persistable grant must not leave a folder in the list.
    fun add(kind: String, uri: Uri): Boolean {
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (!persisted) return false
        return update(kind) { it + uri.toString() }
    }

    fun remove(kind: String, uri: Uri) {
        if (!update(kind) { it - uri.toString() }) return
        // The same tree may be configured for both built-ins; only release the
        // persisted grant when no remaining kind still scans it.
        val otherKind = if (kind == "manga") "books" else "manga"
        if (uri !in uris(otherKind)) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    fun displayName(uri: Uri): String = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri),
        )
        context.contentResolver.query(
            docUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: FALLBACK_NAME

    // Live catalogue: every persisted folder is walked on every call, so files
    // added later appear and removed folders disappear without an import. SAF
    // errors become warnings on the result instead of a silently empty list.
    fun scan(kind: String): LocalCatalogScan {
        val result = buildLocalCatalogScan(
            kind = kind,
            folders = uris(kind).map { uri -> scanFolder(kind, uri) },
        )
        // Every card URL carries only the registry id; register before
        // returning so the provider/reader can resolve it without re-walking.
        return result.copy(
            entries = result.entries.map { entry ->
                entry.copy(catalogId = registry.register(entry.uri))
            },
        )
    }

    private fun scanFolder(kind: String, uri: Uri): LocalFolderScan {
        val name = displayName(uri)
        val warnings = mutableListOf<String>()
        val root = runCatching { walkTree(uri, name, warnings) }.getOrElse { error ->
            warnings += "Cannot scan $name: ${error.message ?: error::class.simpleName}"
            null
        } ?: return LocalFolderScan(
            uri = uri.toString(),
            name = name,
            files = emptyList(),
            warnings = warnings,
        )
        return if (kind == "manga") {
            val catalog = collectMangaCatalog(root)
            LocalFolderScan(
                uri = uri.toString(),
                name = name,
                files = catalog.files,
                folders = catalog.folders,
                warnings = warnings + catalog.warnings,
            )
        } else {
            val files = mutableListOf<ScannedLocalFile>()
            collectBookFiles(root, files)
            LocalFolderScan(uri = uri.toString(), name = name, files = files, warnings = warnings)
        }
    }

    // Kept for bulk import and folder counts: every supported document in the
    // tree, flattened (folders are a catalogue concept only).
    // PDF is deliberately absent: an imported PDF stores `pdfpage://` chapter
    // rows, which this scan (page files on disk) cannot produce yet.
    fun files(kind: String, uri: Uri): List<LocalFolderFile> {
        val warnings = mutableListOf<String>()
        val root = runCatching { walkTree(uri, displayName(uri), warnings) }.getOrNull()
            ?: return emptyList()
        val extensions = if (kind == "manga") setOf("cbz") else setOf("epub", "fb2")
        val result = mutableListOf<LocalFolderFile>()
        fun collect(node: ScannedNode) {
            for (child in node.children) {
                if (child.isDirectory) {
                    collect(child)
                    continue
                }
                val ext = child.name.substringAfterLast('.', "").lowercase()
                if (ext !in extensions) continue
                result += LocalFolderFile(
                    name = child.name,
                    format = ext.uppercase(),
                    uri = Uri.parse(child.uri),
                    sizeBytes = child.sizeBytes,
                    lastModified = child.lastModified,
                )
            }
        }
        collect(root)
        return result
    }

    private fun collectBookFiles(node: ScannedNode, out: MutableList<ScannedLocalFile>) {
        for (child in node.children) {
            if (child.isDirectory) {
                collectBookFiles(child, out)
                continue
            }
            val ext = child.name.substringAfterLast('.', "").lowercase()
            if (ext != "epub" && ext != "fb2") continue
            out += ScannedLocalFile(
                uri = child.uri,
                name = child.name,
                format = ext.uppercase(),
                sizeBytes = child.sizeBytes,
                lastModified = child.lastModified,
            )
        }
    }

    // SAF-only walk: every child comes from buildChildDocumentsUriUsingTree,
    // so nothing outside the picked tree can be reached. Depth is bounded and
    // children are naturally ordered for stable catalogue cards.
    private fun walkTree(uri: Uri, rootName: String, warnings: MutableList<String>): ScannedNode {
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val root = walkNode(uri, rootId, rootName, depth = 0, warnings = warnings)
        // The root has no parent cursor to supply size/mtime; fetch them so a
        // root-level book folder gets a real metadata-cache key.
        val info = runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
            context.contentResolver.query(
                docUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val size = if (cursor.isNull(0)) null else cursor.getLong(0)
                    val modified = if (cursor.isNull(1)) null else cursor.getLong(1)
                    size to modified
                } else {
                    null
                }
            }
        }.getOrNull()
        return if (info == null) {
            root
        } else {
            root.copy(sizeBytes = info.first, lastModified = info.second)
        }
    }

    private fun walkNode(
        treeUri: Uri,
        documentId: String,
        name: String,
        depth: Int,
        warnings: MutableList<String>,
    ): ScannedNode {
        val self = ScannedNode(
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
            documentId = documentId,
            name = name,
            isDirectory = true,
        )
        if (depth > MAX_LOCAL_SCAN_DEPTH) {
            warnings += "Folder tree too deep at $name"
            return self
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val nodes = mutableListOf<ScannedNode>()
        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            warnings += "Cannot read $name: ${e.message ?: e::class.simpleName}"
            null
        }
        if (cursor == null) {
            if (warnings.isEmpty() || !warnings.last().startsWith("Cannot read $name")) {
                warnings += "Cannot read $name"
            }
            return self
        }
        cursor.use {
            while (it.moveToNext()) {
                val childName = it.getString(0) ?: continue
                val childId = it.getString(1) ?: continue
                val mime = it.getString(2).orEmpty()
                val size = if (it.isNull(3)) null else it.getLong(3)
                val modified = if (it.isNull(4)) null else it.getLong(4)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                nodes += if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkNode(treeUri, childId, childName, depth + 1, warnings).copy(
                        sizeBytes = size,
                        lastModified = modified,
                    )
                } else {
                    ScannedNode(
                        uri = childUri.toString(),
                        documentId = childId,
                        name = childName,
                        isDirectory = false,
                        sizeBytes = size,
                        lastModified = modified,
                    )
                }
            }
        }
        nodes.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return self.copy(children = nodes)
    }

    private fun update(kind: String, transform: (Set<String>) -> Set<String>): Boolean =
        store.put(kind, transform(store.get(kind)))

    private companion object {
        const val FALLBACK_NAME = "Folder"
    }
}
