package dev.mirutime.app.features.finder.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.mirutime.app.features.finder.domain.PluginFileInstall
import dev.mirutime.app.features.finder.domain.PluginFileRejection
import dev.mirutime.core.installer.MAX_LOCAL_BYTES
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Result of reading one SAF stream before any validation happens. Typed so
// the UI never has to parse exception text.
internal sealed interface PluginFileRead {
    data class Ok(val bytes: ByteArray) : PluginFileRead
    data class Rejected(val reason: PluginFileRejection) : PluginFileRead
}

// Reads at most maxBytes; the extra byte is a deliberate overflow probe so a
// huge file is rejected without allocating it whole.
internal fun readPluginStream(
    input: InputStream,
    maxBytes: Int = MAX_LOCAL_BYTES,
): PluginFileRead {
    return try {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return PluginFileRead.Rejected(PluginFileRejection.TOO_LARGE)
            out.write(buffer, 0, read)
        }
        if (total == 0) {
            PluginFileRead.Rejected(PluginFileRejection.EMPTY)
        } else {
            PluginFileRead.Ok(out.toByteArray())
        }
    } catch (_: IOException) {
        PluginFileRead.Rejected(PluginFileRejection.UNREADABLE)
    }
}

internal fun isLuaFileName(name: String): Boolean = name.trim().endsWith(".lua", ignoreCase = true)

// SAF display name; falls back to the URI's last path segment. Only used for
// the id fallback and the result sheet, never as a filesystem path.
internal fun pluginDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()
    return fromProvider?.trim()?.takeIf { it.isNotEmpty() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty()
}

// Reads the picked file and hands the bytes to the provider. No arbitrary path
// access: everything goes through the SAF stream the user granted.
internal suspend fun importPluginFile(
    context: Context,
    uri: Uri,
    provider: FinderDataProvider,
): PluginFileInstall = withContext(Dispatchers.IO) {
    val name = pluginDisplayName(context, uri)
    if (!isLuaFileName(name)) {
        return@withContext PluginFileInstall.Rejected(PluginFileRejection.NOT_LUA, name)
    }
    val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: return@withContext PluginFileInstall.Rejected(PluginFileRejection.UNREADABLE, name)
    val read = input.use { readPluginStream(it) }
    when (read) {
        is PluginFileRead.Rejected -> PluginFileInstall.Rejected(read.reason, name)
        is PluginFileRead.Ok -> provider.installFromFile(name, read.bytes)
    }
}
