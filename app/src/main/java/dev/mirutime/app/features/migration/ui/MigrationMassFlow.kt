package dev.mirutime.app.features.migration.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.app.di.MigrationGraph
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.app.features.migration.domain.MigrationTargetDetails
import dev.mirutime.app.features.migration.domain.bestMigrationHit
import dev.mirutime.data.library.migration.BookMigrationOptions
import dev.mirutime.data.library.migration.BookMigrationOutcome
import dev.mirutime.data.library.migration.BookMigrationPlan
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

sealed interface MassBookOutcome {
    data class Matched(
        val target: MigrationTargetDetails,
        val plan: BookMigrationPlan,
    ) : MassBookOutcome
    data object NotFound : MassBookOutcome
    data class Failed(val message: String) : MassBookOutcome
}

data class MassBookResult(
    val candidate: MigrationCandidateBook,
    val outcome: MassBookOutcome,
)

sealed interface MassSearchUi {
    data object Idle : MassSearchUi
    data object Searching : MassSearchUi
    data class Ready(val results: List<MassBookResult>) : MassSearchUi
    data class Failed(val message: String) : MassSearchUi
}

sealed interface MassRunUi {
    data object Idle : MassRunUi
    data class Running(val done: Int, val total: Int, val title: String) : MassRunUi
    data class Finished(
        val succeeded: Int,
        val failed: Int,
        val failures: List<String>,
    ) : MassRunUi
}

/**
 * Mass flow: one real search per selected old book, then one coordinator call
 * per matched target. Runs sequentially so retries are idempotent per batch
 * and the UI never owns more than one in-flight book migration.
 */
class MassMigrationState(
    private val graph: MigrationGraph,
    private val scope: CoroutineScope,
    private val sourceSelection: MigrationSourceSelection = MigrationSourceSelection(),
) {
    var selectedUrls by mutableStateOf<Set<String>>(emptySet())
    val selectedSources: Set<String> get() = sourceSelection.selected
    var options by mutableStateOf(BookMigrationOptions())
    var searchUi by mutableStateOf<MassSearchUi>(MassSearchUi.Idle)
    var runUi by mutableStateOf<MassRunUi>(MassRunUi.Idle)
    // Per-book completion while the search is still running. The result
    // screen reads this; the fan-out itself is unchanged.
    var progress by mutableStateOf<List<MassBookResult>>(emptyList())
    var searchingUrls by mutableStateOf<Set<String>>(emptySet())
    // The UI is the only reader; one generation per search keeps a cancelled
    // run from overwriting the answers of the run that replaced it.
    private var searchJob: Job? = null
    private var searchGeneration = 0

    fun syncSources(sources: List<CatalogSource>) {
        sourceSelection.sync(sources)
    }

    fun applySourceSelection(ids: Set<String>) {
        cancelSearch()
        sourceSelection.apply(ids)
    }

    fun resetForSource() {
        cancelSearch()
        selectedUrls = emptySet()
        searchUi = MassSearchUi.Idle
        runUi = MassRunUi.Idle
        progress = emptyList()
        searchingUrls = emptySet()
    }

    fun toggleBook(url: String) {
        selectedUrls = if (url in selectedUrls) selectedUrls - url else selectedUrls + url
        resetSearch()
    }

    fun toggleBooks(urls: Set<String>, select: Boolean) {
        selectedUrls = if (select) selectedUrls + urls else selectedUrls - urls
        resetSearch()
    }

    private fun resetSearch() {
        cancelSearch()
        searchUi = MassSearchUi.Idle
        runUi = MassRunUi.Idle
        progress = emptyList()
        searchingUrls = emptySet()
    }

    private fun cancelSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
    }

    fun search(
        books: List<MigrationCandidateBook>,
        sources: List<CatalogSource>,
    ) {
        val selected = books.filter { it.bookUrl in selectedUrls }
        val targets = sources.filter { it.id in selectedSources }
        if (selected.isEmpty() || targets.isEmpty()) return
        cancelSearch()
        val generation = searchGeneration
        searchUi = MassSearchUi.Searching
        runUi = MassRunUi.Idle
        progress = emptyList()
        searchingUrls = selected.map { it.bookUrl }.toSet()
        searchJob = scope.launch {
            try {
                // shortcut: two books at a time; every plugin
                // call is capped by the shared MigrationSourceGate.
                val parallel = Dispatchers.IO.limitedParallelism(2)
                val results = coroutineScope {
                    selected.map { book ->
                        async(parallel) { searchOne(book, targets) }
                    }.map { deferred ->
                        val result = deferred.await()
                        if (generation == searchGeneration) {
                            progress = progress + result
                            searchingUrls = searchingUrls - result.candidate.bookUrl
                        }
                        result
                    }
                }
                if (generation == searchGeneration) searchUi = MassSearchUi.Ready(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchGeneration) {
                    searchUi = MassSearchUi.Failed(e.message.orEmpty())
                }
            } finally {
                if (generation == searchGeneration) searchingUrls = emptySet()
            }
        }
    }

    fun migrate(onMigrated: () -> Unit) {
        val ready = (searchUi as? MassSearchUi.Ready)?.results
            ?.filter { it.outcome is MassBookOutcome.Matched }
            .orEmpty()
        if (ready.isEmpty() || runUi is MassRunUi.Running) return
        runUi = MassRunUi.Running(0, ready.size, "")
        scope.launch {
            var succeeded = 0
            var failed = 0
            val failures = mutableListOf<String>()
            for ((index, result) in ready.withIndex()) {
                val outcome = result.outcome as? MassBookOutcome.Matched ?: continue
                runUi = MassRunUi.Running(index, ready.size, result.candidate.title)
                try {
                    when (
                        val migrated = graph.coordinator.migrate(
                            result.candidate.bookId,
                            outcome.target.target,
                            options,
                        )
                    ) {
                        is BookMigrationOutcome.Success -> {
                            succeeded++
                            onMigrated()
                        }
                        is BookMigrationOutcome.Failure -> {
                            failed++
                            failures += "${result.candidate.title}: ${migrated.error}"
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    failures += "${result.candidate.title}: ${e.message.orEmpty()}"
                }
            }
            runUi = MassRunUi.Finished(succeeded, failed, failures)
        }
    }

    private suspend fun searchOne(
        book: MigrationCandidateBook,
        sources: List<CatalogSource>,
    ): MassBookResult {
        val found = try {
            graph.search.search(book.title, sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return MassBookResult(book, MassBookOutcome.Failed(e.message.orEmpty()))
        }
        // A search can return the old book itself from its current source.
        val candidates = found.hits.filterNot { it.book.bookUrl == book.bookUrl }
        val hit = bestMigrationHit(candidates, book.title)
            ?: return MassBookResult(book, MassBookOutcome.NotFound)
        val details = graph.targets.load(
            sourceId = hit.sourceId,
            sourceName = hit.sourceName,
            bookUrl = hit.book.bookUrl,
            fallbackTitle = hit.book.title,
        ) ?: return MassBookResult(book, MassBookOutcome.NotFound)
        val plan = when (val outcome = graph.coordinator.plan(book.bookId, details.target)) {
            is BookMigrationOutcome.Success -> outcome.value
            is BookMigrationOutcome.Failure ->
                return MassBookResult(book, MassBookOutcome.Failed(outcome.error))
        }
        return MassBookResult(book, MassBookOutcome.Matched(details, plan))
    }
}

@Composable
fun MassFinishedCard(run: MassRunUi.Finished) {
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.migration_result_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.migration_bulk_summary_fmt,
                run.succeeded,
                run.failed,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
        run.failures.take(10).forEach { failure ->
            Row(
                modifier = Modifier.padding(top = MiruSpace.Xs),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(MiruSize.Touch / 3),
                )
                Text(
                    text = stringResource(R.string.migration_error_fmt, failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
