package dev.mirutime.app.features.finder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.design.components.PluginSharePreviewSheet
import dev.mirutime.app.design.components.technicalDetail
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.domain.PluginExtension
import dev.mirutime.app.features.finder.domain.isWebUrl
import dev.mirutime.core.api.PluginError
import kotlinx.coroutines.launch

// Health sheet for a plugin that failed to load. Title/message come from the
// typed PluginError; actions are only shown when they can actually work:
// Disable for an enabled plugin, Reset only when the repository still has a
// copy, Open login/site only for a valid http(s) address.
@Composable
fun PluginHealthSheet(
    plugin: PluginExtension,
    provider: FinderDataProvider,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val error = plugin.error ?: PluginError.ScriptError("")
    // Same builder as every other surface: source/op + HTTP/class + host+path.
    val detailReport = buildPluginErrorReport(
        error = error,
        sourceId = plugin.id,
        sourceName = plugin.name,
        operation = "load",
        identity = plugin.id,
    )
    val title = detailReport.title ?: errorTitle(error)
    val message = detailReport.message ?: errorMessage(error)
    val authUrl = (error as? PluginError.UserMessage)?.authUrl
    val siteUrl = provider.baseUrl(plugin.id)
    val openUrl = authUrl?.takeIf(::isWebUrl) ?: siteUrl.takeIf(::isWebUrl)
    val openLabel = if (authUrl?.takeIf(::isWebUrl) != null) {
        stringResource(R.string.finder_error_open_login)
    } else {
        stringResource(R.string.finder_error_open_source)
    }
    var copied by remember { mutableStateOf(false) }
    var sharePreview by remember { mutableStateOf(false) }
    var sourceOpen by remember { mutableStateOf(false) }
    var resetBusy by remember { mutableStateOf(false) }
    var resetNote by remember { mutableStateOf<String?>(null) }

    // The sheet itself is the preview container: swap its content instead of
    // stacking a second AppBottomSheet over an already open one.
    if (sharePreview) {
        PluginSharePreviewSheet(
            reportText = detailReport.reportText,
            onClose = { sharePreview = false },
        )
        return
    }

    AppBottomSheet(
        title = plugin.name,
        description = stringResource(R.string.finder_error_sheet_desc),
        onClose = onClose,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(MiruSpace.Xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        technicalDetail(detailReport.detailText, title, message)
            .takeIf { it.isNotBlank() }
            ?.let { technical ->
                Text(
                    text = technical,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.S),
                )
            }
        detailReport.text.authHostPath?.let { label ->
            Spacer(modifier = Modifier.height(MiruSpace.Xs))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        resetNote?.let {
            Spacer(modifier = Modifier.height(MiruSpace.S))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.ContentCopy,
            title = stringResource(
                if (copied) R.string.finder_error_copied else R.string.finder_error_copy,
            ),
            subtitle = stringResource(R.string.finder_error_copy_sub),
            onClick = {
                copyToClipboard(context, detailReport.reportText)
                copied = true
            },
        )
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Share,
            title = stringResource(R.string.plugin_error_share),
            subtitle = stringResource(R.string.plugin_error_share_desc),
            onClick = { sharePreview = true },
        )
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Visibility,
            title = stringResource(R.string.finder_action_view_source),
            subtitle = stringResource(R.string.finder_action_view_source_sub),
            onClick = { sourceOpen = true },
        )
        if (plugin.enabledByUser) {
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Delete,
                title = stringResource(R.string.finder_error_disable),
                subtitle = stringResource(R.string.finder_error_disable_sub),
                onClick = {
                    scope.launch {
                        provider.setEnabled(plugin.id, false)
                        onChanged()
                        onClose()
                    }
                },
            )
        }
        if (plugin.repoAvailable) {
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Refresh,
                title = stringResource(R.string.finder_error_reset),
                subtitle = stringResource(R.string.finder_error_reset_sub),
                enabled = !resetBusy,
                onClick = {
                    resetBusy = true
                    scope.launch {
                        val result = provider.resetPlugin(plugin.id)
                        resetBusy = false
                        if (result.success) {
                            onChanged()
                            onClose()
                        } else {
                            resetNote = resources.getString(
                                R.string.finder_error_reset_failed_fmt,
                                result.detail,
                            )
                        }
                    }
                },
            )
        }
        if (openUrl != null) {
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Public,
                title = openLabel,
                subtitle = dev.mirutime.app.core.hostPathOnly(openUrl),
                onClick = { openWeb(context, openUrl) },
            )
        }
    }

    if (sourceOpen) {
        PluginSourceSheet(
            pluginId = plugin.id,
            name = plugin.name,
            provider = provider,
            onClose = { sourceOpen = false },
        )
    }
}

@Composable
private fun errorTitle(error: PluginError): String = when (error) {
    is PluginError.UserMessage -> error.title
    is PluginError.Network -> stringResource(R.string.finder_error_network_title)
    is PluginError.DnsFailure -> stringResource(R.string.finder_error_dns_title, error.host)
    is PluginError.CfChallengeRequired ->
        stringResource(R.string.finder_error_cf_title)
    is PluginError.ResponseTooLarge ->
        stringResource(R.string.finder_error_large_title)
    is PluginError.UnsupportedApi ->
        stringResource(R.string.finder_error_api_title)
    is PluginError.ScriptError -> stringResource(R.string.finder_error_script_title)
}

// Free text from below the plugin boundary (a script error detail, a network
// cause, a plugin message) is only renderable through the report builder;
// ScriptError.detail can quote page text and Network.cause can quote a URL.
internal fun pluginErrorBodyIsFreeText(error: PluginError): Boolean = when (error) {
    is PluginError.UserMessage,
    is PluginError.Network,
    is PluginError.ScriptError -> true
    is PluginError.DnsFailure,
    is PluginError.CfChallengeRequired,
    is PluginError.ResponseTooLarge,
    is PluginError.UnsupportedApi -> false
}

// The report already carries the redacted UserMessage text; only typed fields
// the app formats itself are safe to render as the fallback body.
@Composable
private fun errorMessage(error: PluginError): String = when {
    pluginErrorBodyIsFreeText(error) -> ""
    error is PluginError.DnsFailure -> stringResource(R.string.finder_error_dns_body, error.host)
    error is PluginError.CfChallengeRequired ->
        stringResource(R.string.finder_error_cf_body, error.host)
    error is PluginError.ResponseTooLarge ->
        stringResource(R.string.finder_error_large_body, error.limit)
    error is PluginError.UnsupportedApi -> stringResource(
        R.string.finder_error_api_body,
        error.required,
        error.actual,
    )
    else -> ""
}

private fun openWeb(context: Context, url: String) {
    if (!isWebUrl(url)) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    manager.setPrimaryClip(ClipData.newPlainText("plugin", text))
}
