package dev.mirutime.app.features.finder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.features.finder.data.FinderDataProvider

// Read-only plugin script viewer. Deliberately not an editor: the install
// path (file or repository) is the only way a script changes.
@Composable
fun PluginSourceSheet(
    pluginId: String,
    name: String,
    provider: FinderDataProvider,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var source by remember(pluginId) { mutableStateOf<String?>(null) }
    var loaded by remember(pluginId) { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(pluginId) {
        source = provider.pluginScript(pluginId)
        loaded = true
    }

    AppBottomSheet(
        title = name,
        description = stringResource(R.string.finder_source_sheet_desc),
        onClose = onClose,
        doneLabel = stringResource(
            if (copied) R.string.finder_source_copied else R.string.finder_source_copy,
        ),
        doneEnabled = source != null,
        onDone = {
            val text = source ?: return@AppBottomSheet
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText("plugin", text))
            copied = true
        },
    ) {
        Column {
            when {
                !loaded -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalMiruExtraColors.current.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.finder_source_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
                source == null -> Text(
                    text = stringResource(R.string.finder_source_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> SelectionContainer {
                    Text(
                        text = source.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (source != null) {
                Text(
                    text = stringResource(R.string.finder_source_readonly),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.S),
                )
            }
        }
    }
}
