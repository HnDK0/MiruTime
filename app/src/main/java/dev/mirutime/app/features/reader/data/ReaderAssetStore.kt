package dev.mirutime.app.features.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Keeps picked reader art inside app storage so a revoked picker grant or a
// restart cannot break it. Both folders are flat and private to the app.
class ReaderAssetStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val backgroundDir = File(context.applicationContext.filesDir, "reader/backgrounds")
    private val fontDir = File(context.applicationContext.filesDir, "reader/fonts")

    data class StoredAsset(val path: String, val displayName: String)

    suspend fun importBackground(uri: Uri, previousPath: String? = null): StoredAsset? =
        withContext(Dispatchers.IO) {
            val extension = backgroundExtension(uri) ?: return@withContext null
            copyPicked(uri, backgroundDir, "background", extension)?.also {
                deleteOwned(backgroundDir, previousPath)
            }
        }

    suspend fun importFont(uri: Uri, previousPath: String? = null): StoredAsset? =
        withContext(Dispatchers.IO) {
            val extension = fontExtension(uri) ?: return@withContext null
            copyPicked(uri, fontDir, "font", extension)?.also {
                deleteOwned(fontDir, previousPath)
            }
        }

    fun deleteBackground(path: String?) {
        deleteOwned(backgroundDir, path)
    }

    fun deleteFont(path: String?) {
        deleteOwned(fontDir, path)
    }

    private fun copyPicked(
        uri: Uri,
        directory: File,
        prefix: String,
        extension: String,
    ): StoredAsset? {
        var temp: File? = null
        return try {
            if (!directory.exists() && !directory.mkdirs()) return null
            val displayName = queryDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "asset"
            val target = File(
                directory,
                "$prefix-${UUID.randomUUID().toString().take(8)}-${safeBaseName(displayName)}.$extension",
            )
            val tempFile = File(directory, "${target.name}.tmp")
            temp = tempFile
            val input = resolver.openInputStream(uri) ?: return null
            input.use { source ->
                tempFile.outputStream().use { targetStream -> source.copyTo(targetStream) }
            }
            if (tempFile.length() == 0L || !tempFile.renameTo(target)) {
                tempFile.delete()
                return null
            }
            StoredAsset(target.absolutePath, displayName)
        } catch (_: Exception) {
            null
        } finally {
            temp?.takeIf { it.exists() }?.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun backgroundExtension(uri: Uri): String? {
        val nameExtension = extensionOf(queryDisplayName(uri))
        if (nameExtension in ImageExtensions) return nameExtension
        return when (resolver.getType(uri).orEmpty().lowercase(Locale.US)) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/avif" -> "avif"
            else -> null
        }
    }

    private fun fontExtension(uri: Uri): String? {
        val nameExtension = extensionOf(queryDisplayName(uri))
        if (nameExtension == "ttf" || nameExtension == "otf") return nameExtension
        val mime = resolver.getType(uri).orEmpty().lowercase(Locale.US)
        return when {
            "otf" in mime || "opentype" in mime -> "otf"
            "ttf" in mime || "sfnt" in mime -> "ttf"
            else -> null
        }
    }

    private fun extensionOf(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }

    private fun safeBaseName(displayName: String): String =
        displayName
            .substringBeforeLast('.', displayName)
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "asset" }

    private fun deleteOwned(directory: File, path: String?) {
        val file = path?.let(::File) ?: return
        runCatching {
            if (file.canonicalFile.parentFile == directory.canonicalFile) file.delete()
        }
    }

    private companion object {
        val ImageExtensions = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
        )
    }
}
