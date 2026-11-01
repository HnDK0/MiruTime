package dev.mirutime.app.features.migration.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.di.MigrationGraph
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Book-page entry. It is a personal flow: the sheet already knows the library
 * book, so it searches that title across every installed source once and never
 * routes to the full-screen Migration screen.
 */
@Composable
fun MigrationBookSheet(
    bookUrl: String,
    bookSource: String,
    graph: MigrationGraph,
    onClose: () -> Unit,
    onLibraryChanged: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var candidate by remember(bookUrl) { mutableStateOf<MigrationCandidateBook?>(null) }
    var loadError by remember(bookUrl) { mutableStateOf<String?>(null) }
    var candidateTick by remember(bookUrl) { mutableIntStateOf(0) }
    var sourcesLoading by remember { mutableStateOf(true) }
    var sourcesError by remember { mutableStateOf<String?>(null) }
    var sourcesTick by remember { mutableIntStateOf(0) }
    var autoSearchStarted by remember(bookUrl) { mutableStateOf(false) }
    var optionsOpen by remember(bookUrl) { mutableStateOf(false) }

    LaunchedEffect(bookUrl, bookSource, graph, candidateTick) {
        loadError = null
        candidate = try {
            graph.targets.candidate(bookUrl, bookSource)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message.orEmpty()
            null
        }
        if (candidate == null && loadError == null) {
            loadError = ""
        }
    }

    val loadedCandidate = candidate
    val state = remember(loadedCandidate, graph) {
        loadedCandidate?.let { MigrationSingleState(it, graph, scope) }
    }
    LaunchedEffect(state, graph, sourcesTick) {
        val current = state ?: return@LaunchedEffect
        sourcesLoading = true
        sourcesError = null
        try {
            current.syncSources(graph.search.sources())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sourcesError = e.message.orEmpty()
        } finally {
            sourcesLoading = false
        }
        // One automatic search per open, only after the source list is known.
        // Search Retry calls state.search() instead of re-entering this path.
        if (!autoSearchStarted && sourcesError == null && current.sources.isNotEmpty()) {
            autoSearchStarted = true
            current.autoSearch()
        }
    }

    val success = state?.runUi is MigrationRunUi.Success
    AppBottomSheet(
        title = stringResource(R.string.migration_sheet_title),
        description = stringResource(R.string.migration_sheet_desc),
        onClose = onClose,
        doneLabel = stringResource(
            when {
                optionsOpen -> R.string.lib_done
                success -> R.string.lib_done
                else -> R.string.migration_action
            },
        ),
        onDone = {
            when {
                optionsOpen -> optionsOpen = false
                success -> onClose()
                else -> state?.migrate {
                    onLibraryChanged()
                }
            }
        },
        doneEnabled = optionsOpen || state?.canMigrate == true || success,
        doneBusy = state?.runUi is MigrationRunUi.Running,
    ) {
        when {
            loadError != null -> MigrationErrorNotice(
                message = loadError!!.ifBlank {
                    stringResource(R.string.migration_not_in_library)
                },
                onRetry = { candidateTick++ },
            )
            loadedCandidate == null -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    strokeWidth = MiruSpace.Xxs,
                    modifier = Modifier.padding(vertical = MiruSpace.L),
                )
                Text(
                    text = stringResource(R.string.migration_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                )
            }
            state != null -> {
                MigrationBookSheetBody(
                    state = state,
                    sourcesLoading = sourcesLoading,
                    sourcesError = sourcesError,
                    onRetrySources = { sourcesTick++ },
                    onRetrySearch = state::search,
                    onEditOptions = { optionsOpen = !optionsOpen },
                )
                // Inline expansion in the same sheet: a second
                // ModalBottomSheet on top would break back/dismiss ordering.
                if (optionsOpen) {
                    Text(
                        text = stringResource(R.string.migration_options_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = MiruSpace.M),
                    )
                    MigrationOptionsEditor(
                        options = state.options,
                        onChange = { state.options = it },
                        // Flush with the sheet's own padding; the rows must not double-indent.
                        horizontalPadding = 0.dp,
                    )
                }
                if (success) {
                    val target = state.target
                    if (target != null) {
                        TextButton(
                            onClick = { onOpenBook(target.bookUrl) },
                            modifier = Modifier.padding(top = MiruSpace.S),
                        ) {
                            Text(stringResource(R.string.migration_open_target))
                        }
                    }
                }
            }
        }
    }

}
