package dev.mirutime.app.design.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruSpace

// ONE error surface for catalogue, book, updates and plugin health. It renders
// the shared detail (source/op, HTTP status or class, host+path) and offers
// Retry / Copy / Share, plus Open login and Solve only when the caller can
// really perform them. Copy and Share both send reportText verbatim; Share
// always previews first. Stateless apart from the preview sheet.
@Composable
fun PluginErrorState(
    title: String,
    message: String,
    detailText: String? = null,
    reportText: String? = null,
    authUrl: String? = null,
    authLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    onOpenAuth: ((String) -> Unit)? = null,
    onSolveCf: (() -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var previewing by remember { mutableStateOf(false) }
    val payload = reportText?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(title.takeIf { it.isNotBlank() }, message.takeIf { it.isNotBlank() })
            .joinToString("\n")
    val copy = {
        if (onCopy != null) {
            onCopy(payload)
        } else {
            copyToClipboard(context, payload)
            Toast.makeText(context, R.string.finder_error_copied, Toast.LENGTH_SHORT).show()
        }
    }

    MiruCard(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = MiruSpace.Xs),
            )
        }
        detailText?.let { technicalDetail(it, title, message) }?.takeIf { it.isNotBlank() }?.let { detail ->
            SelectionContainer {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.S),
                )
            }
        }
        authLabel?.takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(top = MiruSpace.Xs),
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.M))
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
            if (onRetry != null) {
                MiruButton(
                    onClick = onRetry,
                    style = MiruButtonStyle.Primary,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.finder_error_reload), maxLines = 1)
                }
            }
            if (payload.isNotBlank()) {
                MiruButton(onClick = copy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.finder_error_copy), maxLines = 1)
                }
            }
        }
        if (payload.isNotBlank() || onOpenAuth != null || onSolveCf != null) {
            Spacer(modifier = Modifier.height(MiruSpace.S))
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                if (payload.isNotBlank()) {
                    MiruButton(
                        onClick = { previewing = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.plugin_error_share), maxLines = 1)
                    }
                }
                if (authUrl != null && onOpenAuth != null) {
                    MiruButton(
                        onClick = { onOpenAuth(authUrl) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.finder_error_open_login), maxLines = 1)
                    }
                } else if (onSolveCf != null) {
                    MiruButton(
                        onClick = onSolveCf,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.plugin_error_solve), maxLines = 1)
                    }
                }
            }
        }
    }

    if (previewing) {
        PluginSharePreviewSheet(
            reportText = payload,
            onClose = { previewing = false },
            onShare = onShare,
        )
    }
}

// Share is a two-step action: the exact payload is shown in the one
// AppBottomSheet first, and ACTION_SEND fires only from its done button. The
// same sheet is reused by PluginHealthSheet (which swaps its own content
// instead of stacking a second sheet).
@Composable
fun PluginSharePreviewSheet(
    reportText: String,
    onClose: () -> Unit,
    onShare: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    AppBottomSheet(
        title = stringResource(R.string.plugin_error_share_title),
        description = stringResource(R.string.plugin_error_share_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.plugin_error_share_send),
        onDone = {
            if (onShare != null) onShare(reportText) else shareText(context, reportText)
            onClose()
        },
    ) {
        SelectionContainer {
            Column {
                Text(
                    text = reportText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    manager.setPrimaryClip(ClipData.newPlainText("plugin-error", text))
}

internal fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

// The detail already carries the human title/message (they are part of the
// copyable report). The card renders those separately, so drop exact duplicate
// lines and keep the technical ones (source/op, HTTP/class, request, CF).
internal fun technicalDetail(detailText: String, vararg humanLines: String): String {
    val human = humanLines.filter { it.isNotBlank() }.toSet()
    return detailText.lineSequence()
        .filterNot { it in human }
        .joinToString("\n")
        .trim()
}
