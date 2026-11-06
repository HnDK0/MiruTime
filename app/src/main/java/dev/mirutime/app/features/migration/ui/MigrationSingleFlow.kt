package dev.mirutime.app.features.migration.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.app.di.MigrationGraph
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.app.features.migration.domain.MigrationSearchHit
import dev.mirutime.app.features.migration.domain.MigrationSearchResult
import dev.mirutime.app.features.migration.data.dedupedMigrationHits
import dev.mirutime.app.features.migration.data.migrationHit
import dev.mirutime.app.features.migration.domain.MigrationTargetDetails
import dev.mirutime.app.features.migration.domain.autoMigrationSearchQuery
import dev.mirutime.data.library.migration.BookMigrationOptions
import dev.mirutime.data.library.migration.BookMigrationOutcome
import dev.mirutime.data.library.migration.BookMigrationPlan
import dev.mirutime.data.library.migration.BookMigrationResult
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One book's search -> target -> plan -> options -> migrate state. The sheet
 * and the full screen share it, so a single run has one owner of the progress
 * state and one idempotent coordinator call.
 */
class MigrationSingleState(
    val candidate: MigrationCandidateBook,
    private val graph: MigrationGraph,
    private val scope: CoroutineScope,
    private val sourceSelection: MigrationSourceSelection = MigrationSourceSelection(),
) {
    var query by mutableStateOf("")
    var sources by mutableStateOf<List<CatalogSource>>(emptyList())
    val selectedSources: Set<String> get() = sourceSelection.selected
    var searchUi by mutableStateOf<MigrationSearchUi>(MigrationSearchUi.Idle)
    var target by mutableStateOf<MigrationTargetDetails?>(null)
    var targetLoading by mutableStateOf(false)
    var plan by mutableStateOf<BookMigrationPlan?>(null)
    var planError by mutableStateOf<String?>(null)
    var options by mutableStateOf(BookMigrationOptions())
    var runUi by mutableStateOf<MigrationRunUi>(MigrationRunUi.Idle)
    private var lastHit: MigrationSearchHit? = null
    private var lastOnDone: ((BookMigrationResult) -> Unit)? = null
    private var searchJob: Job? = null
    private var searchSeq = 0
    private var selectSeq = 0
    private var autoSearchDone = false

    val canMigrate: Boolean
        get() = target != null && plan != null &&
            runUi !is MigrationRunUi.Running && runUi !is MigrationRunUi.Success

    fun syncSources(available: List<CatalogSource>) {
        if (available == sources) return
        sources = available
        sourceSelection.sync(available)
    }

    fun toggleSource(sourceId: String) {
        sourceSelection.toggle(sourceId)
    }

    fun selectAllSources() {
        sourceSelection.selectAll()
    }

    /** The filter sheet owns the draft; only installed ids can come back. */
    fun applySourceSelection(ids: Set<String>) {
        sourceSelection.apply(ids)
    }

    /**
     * Book-page entry point: the library title is the query and every
     * installed target is searched. The caller invokes this once after the
     * sources load; Retry goes through [search] so it cannot loop.
     */
    fun autoSearch() {
        query = autoMigrationSearchQuery(candidate)
        sourceSelection.selectAll()
        search()
    }

    /**
     * Full-screen entry point. The LazyColumn item can be disposed while
     * scrolling, so the one-shot guard lives in the state, not the composable.
     */
    fun autoSearchOnce() {
        if (autoSearchDone || sources.isEmpty()) return
        autoSearchDone = true
        autoSearch()
    }

    /**
     * Clear icon in the full-screen query field: drop the draft AND the
     * answers it produced, so an emptied field never sits above stale tiles.
     * Sequence bumps make any in-flight answer a no-op.
     */
    fun clearQuery() {
        query = ""
        cancelSearch()
        selectSeq++
        searchUi = MigrationSearchUi.Idle
        target = null
        plan = null
        planError = null
        targetLoading = false
        runUi = MigrationRunUi.Idle
    }

    /** Back to the result tiles without losing the search answer. */
    fun clearTarget() {
        selectSeq++
        target = null
        plan = null
        planError = null
        targetLoading = false
        runUi = MigrationRunUi.Idle
    }

    fun search() {
        val selected = sources.filter { it.id in selectedSources }
        if (query.isBlank() || selected.isEmpty()) return
        cancelSearch()
        val clean = query.trim()
        searchUi = MigrationSearchUi.Searching(total = selected.size)
        target = null
        plan = null
        planError = null
        runUi = MigrationRunUi.Idle
        val seq = searchSeq
        searchJob = scope.launch {
            try {
                // Per-source fan-out like the global search: every answer lands
                // in its own slot, so tiles appear while other sources are
                // still searching; Ready keeps the deterministic source order.
                val slots = AtomicReferenceArray<List<MigrationSearchHit>?>(selected.size)
                val failures = AtomicReferenceArray<String?>(selected.size)
                fun hits(): List<MigrationSearchHit> =
                    (0 until selected.size).flatMap { slots.get(it).orEmpty() }
                        .dedupedMigrationHits()

                fun failed(): List<String> =
                    (0 until selected.size).mapNotNull { failures.get(it) }

                fun done(): Int =
                    (0 until selected.size).count {
                        slots.get(it) != null || failures.get(it) != null
                    }

                // The state scope is the screen's Main scope, so these
                // snapshot writes stay Main-confined.
                coroutineScope {
                    selected.forEachIndexed { index, source ->
                        launch {
                            val result = graph.search.searchOne(source, clean)
                            if (seq != searchSeq) return@launch
                            result.onSuccess { books ->
                                slots.set(index, books.map { migrationHit(source, it) })
                            }.onFailure {
                                failures.set(index, source.name)
                            }
                            searchUi = MigrationSearchUi.Searching(
                                hits = hits(),
                                done = done(),
                                total = selected.size,
                                failedSources = failed(),
                            )
                        }
                    }
                }
                if (seq != searchSeq) return@launch
                searchUi = MigrationSearchUi.Ready(
                    MigrationSearchResult(hits = hits(), failedSources = failed()),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == searchSeq) searchUi = MigrationSearchUi.Failed(e.message.orEmpty())
            }
        }
    }

    private fun cancelSearch() {
        searchSeq++
        searchJob?.cancel()
        searchJob = null
    }

    fun select(hit: MigrationSearchHit) {
        lastHit = hit
        target = null
        plan = null
        planError = null
        runUi = MigrationRunUi.Idle
        targetLoading = true
        val seq = ++selectSeq
        scope.launch {
            try {
                val details = graph.targets.load(
                    sourceId = hit.sourceId,
                    sourceName = hit.sourceName,
                    bookUrl = hit.book.bookUrl,
                    fallbackTitle = hit.book.title,
                )
                if (seq != selectSeq) return@launch
                if (details == null) {
                    planError = ""
                } else {
                    target = details
                    loadPlan(details, seq)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                planError = e.message.orEmpty()
            } finally {
                if (seq == selectSeq) targetLoading = false
            }
        }
    }

    fun retryPlan() {
        val details = target
        if (details != null) {
            plan = null
            planError = null
            scope.launch { loadPlan(details, selectSeq) }
        } else {
            lastHit?.let(::select)
        }
    }

    fun migrate(onDone: (BookMigrationResult) -> Unit) {
        val current = target ?: return
        if (!canMigrate) return
        lastOnDone = onDone
        runUi = MigrationRunUi.Running(1, 1, candidate.title)
        scope.launch {
            try {
                when (val outcome = graph.coordinator.migrate(candidate.bookId, current.target, options)) {
                    is BookMigrationOutcome.Success -> {
                        runUi = MigrationRunUi.Success(outcome.value)
                        onDone(outcome.value)
                    }
                    is BookMigrationOutcome.Failure -> {
                        runUi = MigrationRunUi.Failure(outcome.error)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runUi = MigrationRunUi.Failure(e.message.orEmpty())
            }
        }
    }

    fun retryMigrate() {
        migrate(lastOnDone ?: {})
    }

    private suspend fun loadPlan(details: MigrationTargetDetails, seq: Int) {
        when (val outcome = graph.coordinator.plan(candidate.bookId, details.target)) {
            is BookMigrationOutcome.Success -> if (seq == selectSeq) plan = outcome.value
            is BookMigrationOutcome.Failure -> if (seq == selectSeq) planError = outcome.error
        }
    }
}

/**
 * Book-page sheet body: no query field and no source picker. The title came
 * from the library book and the search already covers every installed source.
 */
@Composable
fun MigrationBookSheetBody(
    state: MigrationSingleState,
    sourcesLoading: Boolean,
    sourcesError: String?,
    onRetrySources: () -> Unit,
    onRetrySearch: () -> Unit,
    onEditOptions: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.migration_old_book_fmt, state.candidate.title),
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                if (state.candidate.unknownSource) {
                    R.string.migration_unknown_source_fmt
                } else {
                    R.string.migration_source_line_fmt
                },
                state.candidate.sourceName,
            ),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
        )
        when {
            sourcesError != null -> MigrationErrorNotice(
                message = sourcesError.ifBlank {
                    stringResource(R.string.migration_unknown_error)
                },
                onRetry = onRetrySources,
            )
            sourcesLoading -> MigrationLoadingRow(
                stringResource(R.string.migration_loading_sources),
            )
            state.sources.isEmpty() -> MigrationEmptyState(
                stringResource(R.string.migration_no_sources),
            )
            else -> {
                MigrationGroupedResults(
                    state = state.searchUi,
                    selectedBookUrl = state.target?.bookUrl,
                    selectedChapterCount = state.target?.chapters?.size,
                    onSelect = state::select,
                    onRetry = onRetrySearch,
                )
                if (state.targetLoading) {
                    MigrationLoadingRow(stringResource(R.string.migration_loading_target))
                }
                state.planError?.let { error ->
                    MigrationErrorNotice(
                        message = error.ifBlank {
                            stringResource(R.string.migration_unknown_error)
                        },
                        onRetry = state::retryPlan,
                    )
                }
                val target = state.target
                val plan = state.plan
                if (target != null && plan != null) {
                    MigrationTargetSummary(target)
                    MigrationPlanSummary(plan)
                    TextButton(
                        onClick = onEditOptions,
                        modifier = Modifier.padding(top = MiruSpace.S),
                    ) {
                        Text(stringResource(R.string.migration_options_title))
                    }
                }
                when (val run = state.runUi) {
                    MigrationRunUi.Idle -> Unit
                    is MigrationRunUi.Running -> MigrationRunningRow(
                        run.current,
                        run.total,
                        run.title,
                    )
                    is MigrationRunUi.Success -> MigrationSuccessNotice(run.result)
                    is MigrationRunUi.Failure -> MigrationErrorNotice(
                        message = run.message.ifBlank {
                            stringResource(R.string.migration_unknown_error)
                        },
                        onRetry = state::retryMigrate,
                    )
                }
                if (state.runUi is MigrationRunUi.Success) {
                    Text(
                        text = stringResource(R.string.migration_idempotent_hint),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                        modifier = Modifier.padding(top = MiruSpace.S),
                    )
                }
            }
        }
    }
}
