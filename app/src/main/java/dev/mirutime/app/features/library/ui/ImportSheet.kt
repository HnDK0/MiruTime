package dev.mirutime.app.features.library.ui

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.library.data.PickedImportFile
import dev.mirutime.app.features.library.data.ImportFailureReason

// Picked-file handoff: the real SAF Uri plus the display name/format. The
// importer copies the bytes by sha; the Uri is only stored for diagnostics.
fun formatForFileName(fileName: String): String = when (
    fileName.substringAfterLast(".", "").lowercase()
) {
    "fb2" -> "FB2"
    "txt" -> "TXT"
    "cbz", "cbr", "zip" -> "CBZ"
    "pdf" -> "PDF"
    else -> "EPUB"
}

fun displayNameOf(context: Context, uri: Uri): String? {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getString(0)
        }
    }
    return null
}

private const val BYTES_PER_MB = 1024L * 1024L

// Ceiling so a file just over a limit never rounds down to the limit itself.
private fun megabytes(bytes: Long): Long = (bytes + BYTES_PER_MB - 1) / BYTES_PER_MB

/** One mapping for every surface that reports an import failure. */
internal fun importFailureText(resources: Resources, reason: ImportFailureReason): String =
    when (reason) {
        is ImportFailureReason.SizeLimit -> resources.getString(
            R.string.import_err_too_large_fmt,
            megabytes(reason.actualBytes),
            megabytes(reason.limitBytes),
        )
        is ImportFailureReason.InsufficientSpace -> resources.getString(
            R.string.import_err_no_space_fmt,
            megabytes(reason.requiredBytes),
        )
    }

// Import flow: one SAF picker, single or multi. One picked file goes to the
// single callback; several go to the batch callback. The importer itself
// decides the local source by extension.
@Composable
fun ImportSheet(
    onClose: () -> Unit,
    onImport: (PickedImportFile) -> Unit,
    onImportUris: (List<PickedImportFile>) -> Unit,
    // The caller owns the import coroutine; while it runs the sheet shows a
    // blocking progress state, cannot be dismissed and the picker is inert.
    busy: Boolean = false,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        val picked = uris.mapNotNull { uri ->
            val name = displayNameOf(context, uri) ?: uri.lastPathSegment ?: uri.toString()
            PickedImportFile(
                uri = uri.toString(),
                fileName = name,
                format = formatForFileName(name),
            )
        }
        when {
            picked.size == 1 -> onImport(picked.single())
            picked.isNotEmpty() -> onImportUris(picked)
        }
    }

    AppBottomSheet(
        title = stringResource(R.string.import_title),
        description = stringResource(R.string.import_desc),
        onClose = { if (!busy) onClose() },
        doneLabel = stringResource(R.string.lib_done),
        doneEnabled = !busy,
        onDone = { if (!busy) onClose() },
    ) {
        SheetSection(title = stringResource(R.string.import_from_storage)) {
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MiruSpace.Xl),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.loading_importing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MiruRadius.Control))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(MiruRadius.Control),
                        )
                        .clickable {
                            picker.launch(
                                arrayOf(
                                    "application/epub+zip",
                                    "text/plain",
                                    "text/xml",
                                    "application/x-fictionbook+xml",
                                    "application/x-cbz",
                                    "application/x-cbr",
                                    "application/pdf",
                                    "application/x-pdf",
                                    "application/zip",
                                ),
                            )
                        }
                        .padding(vertical = MiruSpace.Xl, horizontal = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.import_choose),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.import_multi),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
