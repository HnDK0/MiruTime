package dev.mirutime.app.features.finder.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.MiruCard
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.finder.domain.LinkResolution
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Add a book by link: the pasted address is
// resolved against INSTALLED plugins by host. Found -> a preview and "Add to
// library"; the host is unknown -> say so and, when the repo has a plugin for
// it, offer a one-tap install; not a link -> a plain hint. Fixture address
// scheme: https://<plugin-id>.example/<book-slug>.
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

    var url by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf<LinkResolution?>(null) }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var recheck by remember { mutableIntStateOf(0) }

    // Debounced resolve: waits for a pause in typing/pasting.
    LaunchedEffect(url, recheck) {
        resolution = null
        if (url.isBlank()) {
            checking = false
            return@LaunchedEffect
        }
        checking = true
        addError = null
        delay(400)
        resolution = provider.resolveLink(url)
        checking = false
    }

    val found = resolution as? LinkResolution.Found

    AppBottomSheet(
        title = stringResource(R.string.finder_link_title),
        description = stringResource(R.string.finder_link_desc),
        onClose = onClose,
        doneLabel = stringResource(if (adding) R.string.finder_link_adding else R.string.finder_link_add),
        doneEnabled = found != null && !adding,
        doneBusy = adding,
        onDone = {
            val book = found?.book ?: return@AppBottomSheet
            if (adding) return@AppBottomSheet
            scope.launch {
                adding = true
                addError = null
                val result = try {
                    provider.setInLibrary(book.bookUrl, true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    MembershipResult(success = false)
                } finally {
                    adding = false
                }
                dev.mirutime.core.log.MiruLog.event(
                    "plugins",
                    "add-by-link",
                    "ok" to result.success,
                    "book" to dev.mirutime.core.log.MiruLog.idHash(book.bookUrl),
                )
                if (result.success) {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.finder_added_fmt, book.title),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onAdded(book.bookUrl)
                } else {
                    // Keep the sheet open so the user can retry; never claim a
                    // book was added when the write failed.
                    addError = resources.getString(R.string.finder_link_add_failed)
                }
            }
        },
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = {
                Text(
                    text = stringResource(R.string.finder_link_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.finder_link_example),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.Xs, bottom = MiruSpace.M),
        )

        when {
            addError != null -> Text(
                text = addError.orEmpty(),
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
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(20.dp).height(20.dp))
                Text(
                    text = stringResource(R.string.finder_link_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                )
            }
            found != null -> MiruCard(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
                    BookCover(
                        title = found.book.title,
                        corner = 8.dp,
                        modifier = Modifier.width(56.dp).aspectRatio(1f / 1.45f),
                    )
                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = found.book.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                        )
                        Text(
                            text = stringResource(R.string.finder_link_found_fmt, found.source.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.accent,
                            modifier = Modifier.padding(top = MiruSpace.Xs),
                        )
                        if (found.book.chapters > 0) {
                            Text(
                                text = stringResource(R.string.finder_chapters_short_fmt, found.book.chapters),
                                style = MaterialTheme.typography.bodySmall,
                                color = extra.muted,
                            )
                        }
                    }
                }
            }
            resolution is LinkResolution.UnknownHost -> {
                val unknown = resolution as LinkResolution.UnknownHost
                Text(
                    text = stringResource(R.string.finder_link_unknown_fmt, unknown.host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val suggestion = unknown.suggestion
                if (suggestion != null) {
                    Spacer(modifier = Modifier.height(MiruSpace.M))
                    Button(
                        onClick = {
                            scope.launch {
                                installing = true
                                val report = provider.install(suggestion.id)
                                installing = false
                                dev.mirutime.core.log.MiruLog.event(
                                    "plugins",
                                    "install-by-link",
                                    "plugin" to suggestion.id,
                                    "installed" to report.installed.size,
                                    "failed" to report.failed.size,
                                )
                                recheck++
                            }
                        },
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
                } else {
                    Text(
                        text = stringResource(R.string.finder_link_no_plugin),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                        modifier = Modifier.padding(top = MiruSpace.Xs),
                    )
                }
            }
            resolution is LinkResolution.SourceError -> {
                val failed = resolution as LinkResolution.SourceError
                Text(
                    text = stringResource(
                        R.string.finder_link_source_error_fmt,
                        failed.sourceName,
                        failed.reason,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            resolution is LinkResolution.Invalid -> Text(
                text = stringResource(R.string.finder_link_invalid),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
