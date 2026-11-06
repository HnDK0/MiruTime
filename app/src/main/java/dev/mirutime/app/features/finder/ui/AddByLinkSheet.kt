package dev.mirutime.app.features.finder.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.domain.LinkBatch
import dev.mirutime.app.features.finder.domain.LinkResolution
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.mirutime.uikit.MiruTextField

// Add books by link. One link or a whole list (newline/comma separated): the
// sheet preflights the entire list, blocks every add when a link is malformed
// or a host no installed plugin serves, then adds the rest best-effort. Fixture
// address scheme: https://<plugin-id>.example/<book-slug>.
@Composable
fun AddByLinkSheet(
    provider: FinderDataProvider,
    onClose: () -> Unit,
    onAdded: (bookUrl: String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val extra = LocalMiruExtraColors.current

    var input by remember { mutableStateOf("") }
    var preflight by remember { mutableStateOf<LinkBatch.Preflight?>(null) }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var recheck by remember { mutableIntStateOf(0) }

    // Debounced preflight of the whole list: waits for a pause in typing/paste.
    LaunchedEffect(input, recheck) {
        val urls = LinkBatch.parse(input)
        if (urls.isEmpty()) {
            preflight = null
            checking = false
            return@LaunchedEffect
        }
        checking = true
        addError = null
        delay(400)
        preflight = LinkBatch.preflight(urls) { provider.resolveLink(it) }
        checking = false
    }

    val plan = preflight

    AppBottomSheet(
        title = stringResource(R.string.finder_link_title),
        description = stringResource(R.string.finder_link_desc),
        onClose = onClose,
        doneLabel = stringResource(
            if (adding) R.string.finder_link_adding else R.string.finder_link_add,
        ),
        doneEnabled = plan?.canAdd == true && !adding,
        doneBusy = adding,
        onDone = {
            val current = plan ?: return@AppBottomSheet
            if (adding || !current.canAdd) return@AppBottomSheet
            scope.launch {
                adding = true
                addError = null
                var added = 0
                var firstAdded: String? = null
                try {
                    LinkBatch.addAll(
                        urls = current.addableUrls,
                        add = { url -> provider.setInLibrary(url, true) },
                        onResult = { result ->
                            if (result.status == LinkBatch.Status.Added) {
                                added++
                                if (firstAdded == null) firstAdded = result.url
                            }
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Per-link failures are already recorded; nothing to add here.
                } finally {
                    adding = false
                }
                dev.mirutime.core.log.MiruLog.event(
                    "plugins",
                    "add-by-link-batch",
                    "added" to added,
                    "total" to current.addableUrls.size,
                )
                if (added > 0) {
                    Toast.makeText(
                        context,
                        resources.getString(
                            R.string.finder_link_added_count_fmt,
                            added,
                            current.addableUrls.size,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onAdded(firstAdded ?: current.addableUrls.first())
                } else {
                    // Keep the sheet open so the user can retry; never claim a
                    // book was added when every write failed.
                    addError = resources.getString(R.string.finder_link_add_failed)
                }
            }
        },
    ) {
        MiruTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.finder_link_hint),
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.finder_link_example),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.Xs, bottom = MiruSpace.M),
        )

        addError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = MiruSpace.S),
            )
        }

        when {
            checking -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(20.dp).height(20.dp),
                )
                Text(
                    text = stringResource(R.string.finder_link_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
            }

            plan != null -> BatchPreflightPreview(
                plan = plan,
                installing = installing,
                onInstall = { pluginId ->
                    scope.launch {
                        installing = true
                        val report = provider.install(pluginId)
                        installing = false
                        dev.mirutime.core.log.MiruLog.event(
                            "plugins",
                            "install-by-link",
                            "plugin" to pluginId,
                            "installed" to report.installed.size,
                            "failed" to report.failed.size,
                        )
                        recheck++
                    }
                },
            )
        }
    }
}

@Composable
private fun BatchPreflightPreview(
    plan: LinkBatch.Preflight,
    installing: Boolean,
    onInstall: (String) -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    if (plan.blocking.isNotEmpty()) {
        Text(
            text = stringResource(R.string.finder_link_batch_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        plan.blocking.forEach { blocked ->
            val message = when (val resolution = blocked.resolution) {
                is LinkResolution.UnknownHost ->
                    stringResource(R.string.finder_link_unknown_fmt, resolution.host)
                else -> stringResource(R.string.finder_link_invalid)
            }
            Text(
                text = "• $message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = MiruSpace.Xs),
            )
        }
        val suggestion = plan.blocking
            .mapNotNull { (it.resolution as? LinkResolution.UnknownHost)?.suggestion }
            .firstOrNull()
        if (suggestion != null) {
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Button(
                onClick = { onInstall(suggestion.id) },
                enabled = !installing,
                modifier = Modifier.height(MiruSize.Button),
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(16.dp).height(16.dp),
                    )
                    Spacer(modifier = Modifier.width(MiruSpace.S))
                }
                Text(
                    text = if (installing) {
                        stringResource(R.string.loading_installing)
                    } else {
                        stringResource(R.string.finder_link_install_fmt, suggestion.name)
                    },
                    maxLines = 1,
                )
            }
        }
    }

    if (plan.ready.isNotEmpty()) {
        Text(
            text = stringResource(R.string.finder_link_batch_ready_fmt, plan.ready.size),
            style = MaterialTheme.typography.bodyMedium,
            color = extra.accent,
            modifier = Modifier.padding(
                top = if (plan.blocking.isEmpty()) 0.dp else MiruSpace.M,
            ),
        )
        // A compact preview: the first few resolved books.
        plan.ready.take(4).forEach { ready ->
            MiruCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.S),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
                    BookCover(
                        title = ready.found.book.title,
                        coverUrl = ready.found.book.coverUrl,
                        corner = 8.dp,
                        modifier = Modifier.width(44.dp).aspectRatio(1f / 1.45f),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ready.found.book.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                        )
                        Text(
                            text = stringResource(
                                R.string.finder_link_found_fmt,
                                ready.found.source.name,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.accent,
                            modifier = Modifier.padding(top = MiruSpace.Xs),
                        )
                    }
                }
            }
        }
    }
}
