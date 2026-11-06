package dev.mirutime.app.features.migration.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.mirutime.app.R
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.design.components.CatalogSearchField
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.app.features.migration.domain.MigrationSearchHit
import dev.mirutime.app.features.migration.domain.MigrationLibrarySource
import dev.mirutime.app.features.migration.domain.filterMigrationBooks
import dev.mirutime.app.features.migration.domain.groupMigrationHits
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.app.design.components.PluginAvatar
import dev.mirutime.uikit.ChipRail
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SectionHeader

@Composable
fun MigrationOptionsChip(
    selectedSources: Int,
    totalSources: Int,
    onClick: () -> Unit,
) {
    MiruFilterChip(
        // Accent when the target list is narrowed or empty: both need attention.
        selected = selectedSources != totalSources || selectedSources == 0,
        onClick = onClick,
        label = stringResource(
            R.string.migration_filter_action_fmt,
            selectedSources,
            totalSources,
        ),
        leadingIcon = Icons.Filled.Tune,
    )
}

// Step 1: the old source. One row per installed plugin that has books in the
// library, plus ONE bucket for books whose plugin is gone. Counts are real.
@Composable
fun MigrationLibrarySourceStep(
    sources: List<MigrationLibrarySource>,
    onPick: (MigrationLibrarySource) -> Unit,
) {
    if (sources.isEmpty()) {
        MigrationStateCard(
            title = stringResource(R.string.migration_empty_title),
            description = stringResource(R.string.migration_books_empty),
            icon = Icons.Filled.MenuBook,
        )
        return
    }
    MiruSectionCard(
        title = stringResource(R.string.migration_choose_source),
        description = stringResource(R.string.migration_choose_source_desc),
        contentPadding = PaddingValues(),
    ) {
        sources.forEachIndexed { index, source ->
            if (index > 0) MiruRowDivider()
            MigrationLibrarySourceRow(source = source, onClick = { onPick(source) })
        }
    }
}

@Composable
private fun MigrationLibrarySourceRow(
    source: MigrationLibrarySource,
    onClick: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val unknownName = stringResource(R.string.migration_unknown_source_bucket)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        if (source.unknown) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MiruSize.Control)
                    .clip(RoundedCornerShape(MiruRadius.Inner))
                    .background(extra.accent.copy(alpha = 0.14f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.LinkOff,
                    contentDescription = null,
                    tint = extra.muted,
                    modifier = Modifier.size(MiruSize.Touch / 2),
                )
            }
        } else {
            PluginAvatar(name = source.name, iconUrl = source.iconUrl)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (source.unknown) unknownName else source.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (source.unknown) {
                    stringResource(R.string.migration_unknown_source_bucket_desc)
                } else {
                    listOf(source.language, source.type?.let { contentTypeLabel(it) })
                        .filterNotNull()
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SourceCountPill(count = source.bookCount)
    }
}

@Composable
private fun SourceCountPill(count: Int) {
    val extra = LocalMiruExtraColors.current
    Surface(
        shape = RoundedCornerShape(MiruRadius.Badge),
        color = extra.accent.copy(alpha = 0.14f),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = extra.accent,
            modifier = Modifier.padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xxs),
        )
    }
}

// Step 2: books from the chosen source. Single picks exactly one (tap again to
// clear); Mass selects many with the shared select-all/clear pair. Both use the
// library cards, so list/grid/style follow AppSettings immediately.
@Composable
fun MigrationBooksStep(
    source: MigrationLibrarySource,
    books: List<MigrationCandidateBook>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedUrls: Set<String>,
    multiSelect: Boolean,
    optionsSelected: Int,
    optionsTotal: Int,
    onOpenOptions: () -> Unit,
    onChangeSource: () -> Unit,
    onBook: (MigrationCandidateBook) -> Unit,
    onSelectAll: (Set<String>) -> Unit,
    onClearAll: () -> Unit,
) {
    val filtered = filterMigrationBooks(books, query)
    val shown = filtered.take(50)
    val displayName = if (source.unknown) {
        stringResource(R.string.migration_unknown_source_bucket)
    } else {
        source.name
    }
    Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
        SectionHeader(
            title = displayName,
            description = stringResource(
                R.string.migration_meta_join_fmt,
                pluralStringResource(
                    R.plurals.migration_library_count,
                    source.bookCount,
                    source.bookCount,
                ),
                stringResource(
                    R.string.migration_selected_short_fmt,
                    selectedUrls.size,
                ),
            ),
        )
        // Navigation, filtering and bulk selection share one scrolling line
        // (the app-wide chip rail): no second wrapped block of chips.
        ChipRail {
            item(key = "change-source") {
                MiruFilterChip(
                    selected = false,
                    onClick = onChangeSource,
                    label = stringResource(R.string.migration_change_source),
                    leadingIcon = Icons.Filled.SwapHoriz,
                )
            }
            item(key = "options") {
                MigrationOptionsChip(
                    selectedSources = optionsSelected,
                    totalSources = optionsTotal,
                    onClick = onOpenOptions,
                )
            }
            if (multiSelect && filtered.isNotEmpty()) {
                // Both bulk actions stay on screen: the user never has to
                // infer "Clear" from a toggled label.
                item(key = "select-all") {
                    MiruFilterChip(
                        selected = false,
                        onClick = { onSelectAll(shown.map { it.bookUrl }.toSet()) },
                        label = stringResource(R.string.migration_select_all),
                        leadingIcon = Icons.Filled.Check,
                    )
                }
                item(key = "clear-all") {
                    MiruFilterChip(
                        selected = false,
                        onClick = onClearAll,
                        label = stringResource(R.string.migration_clear_all),
                        leadingIcon = Icons.Filled.Close,
                    )
                }
            }
        }
        SectionHeader(
            title = stringResource(
                if (multiSelect) R.string.migration_select_books else R.string.migration_select_book,
            ),
            description = if (selectedUrls.isEmpty()) {
                stringResource(
                    if (multiSelect) R.string.migration_select_books_desc else R.string.migration_select_book_desc,
                )
            } else {
                stringResource(
                    R.string.migration_selected_count_fmt,
                    selectedUrls.size,
                    books.size,
                )
            },
        )
        MiruCard {
            CatalogSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.migration_filter_books),
                onClear = { onQueryChange("") },
            )
        }
        when {
            books.isEmpty() -> MigrationStateCard(
                title = stringResource(R.string.migration_empty_title),
                description = stringResource(R.string.migration_no_books_in_source),
                icon = Icons.Filled.MenuBook,
            )
            filtered.isEmpty() -> MigrationStateCard(
                title = stringResource(R.string.migration_empty_title),
                description = stringResource(R.string.migration_no_matches),
                icon = Icons.Filled.Search,
            )
            else -> {
                // The step header above already names the source and counts
                // the books; a second titled card here only added a band of
                // empty space on top of the list.
                MigrationLibraryCards(
                    books = shown,
                    selectedUrls = selectedUrls,
                    selectionMode = true,
                    onBook = onBook,
                )
            }
        }
    }
}

// Step 3 for one book: search, then the chosen target and its match plan.
// Result tiles are never inline in the picker.
@Composable
fun MigrationSingleResultsStep(
    state: MigrationSingleState,
    source: MigrationLibrarySource,
    optionsSelected: Int,
    optionsTotal: Int,
    onOpenOptions: () -> Unit,
    onChangeBook: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenCatalogue: (String, String) -> Unit,
) {
    val sourceName = if (source.unknown) {
        stringResource(R.string.migration_unknown_source_bucket)
    } else {
        source.name
    }
    Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
        SectionHeader(
            title = stringResource(R.string.migration_results_title),
            description = stringResource(
                R.string.migration_meta_join_fmt,
                sourceName,
                state.candidate.title,
            ),
        )
        ChipRail {
            item(key = "change-book") {
                MiruFilterChip(
                    selected = false,
                    onClick = onChangeBook,
                    label = stringResource(R.string.migration_change_book),
                    leadingIcon = Icons.Filled.SwapHoriz,
                )
            }
            item(key = "options") {
                MigrationOptionsChip(
                    selectedSources = optionsSelected,
                    totalSources = optionsTotal,
                    onClick = onOpenOptions,
                )
            }
        }
        MiruSectionCard(
            title = stringResource(R.string.migration_find_target_title),
            description = stringResource(R.string.migration_find_target_desc),
            contentPadding = PaddingValues(MiruSpace.S),
        ) {
            CatalogSearchField(
                value = state.query,
                onValueChange = { state.query = it },
                placeholder = stringResource(R.string.migration_search_query),
                onClear = state::clearQuery,
                onSearch = state::search,
            )
        }
        val target = state.target
        val plan = state.plan
        val planError = state.planError
        when {
            state.targetLoading -> MigrationStateCard(
                title = stringResource(R.string.migration_loading_target),
                loading = true,
            )
            planError != null -> MigrationErrorCard(
                message = planError.ifBlank {
                    stringResource(R.string.migration_unknown_error)
                },
                onRetry = state::retryPlan,
            )
            target != null && plan != null -> {
                MigrationTargetCard(
                    details = target,
                    plan = plan,
                    onOptions = onOpenOptions,
                    onChangeTarget = state::clearTarget,
                )
                when (val run = state.runUi) {
                    MigrationRunUi.Idle -> Unit
                    is MigrationRunUi.Running -> MigrationRunCard(
                        current = run.current,
                        total = run.total,
                        title = run.title,
                    )
                    is MigrationRunUi.Success -> MigrationSuccessCard(
                        result = run.result,
                        onOpenTarget = { onOpenBook(target.bookUrl) },
                    )
                    is MigrationRunUi.Failure -> MigrationErrorCard(
                        message = run.message.ifBlank {
                            stringResource(R.string.migration_unknown_error)
                        },
                        onRetry = state::retryMigrate,
                    )
                }
            }
            else -> MigrationSingleSearchResults(
                state = state,
                onOpenCatalogue = onOpenCatalogue,
            )
        }
    }
}

@Composable
private fun MigrationSingleSearchResults(
    state: MigrationSingleState,
    onOpenCatalogue: (String, String) -> Unit,
) {
    when (val search = state.searchUi) {
        MigrationSearchUi.Idle -> Unit
        is MigrationSearchUi.Searching -> {
            MigrationStateCard(
                title = stringResource(R.string.migration_searching),
                description = stringResource(
                    R.string.migration_search_progress_fmt,
                    search.done,
                    search.total,
                ),
                loading = true,
            )
            MigrationCardHitGroups(
                hits = search.hits,
                state = state,
                onOpenCatalogue = onOpenCatalogue,
            )
            MigrationPartialNotice(search.failedSources)
        }
        is MigrationSearchUi.Failed -> MigrationErrorCard(
            message = search.message.ifBlank {
                stringResource(R.string.migration_unknown_error)
            },
            onRetry = state::search,
        )
        is MigrationSearchUi.Ready -> {
            val groups = groupMigrationHits(search.result.hits)
            if (groups.isEmpty()) {
                MigrationStateCard(
                    title = stringResource(R.string.migration_search_empty_title),
                    description = stringResource(R.string.migration_search_empty),
                    icon = Icons.Filled.Search,
                    actionLabel = stringResource(R.string.migration_retry),
                    onAction = state::search,
                )
            } else {
                MigrationCardHitGroups(
                    hits = search.result.hits,
                    state = state,
                    onOpenCatalogue = onOpenCatalogue,
                )
            }
            MigrationPartialNotice(search.result.failedSources)
        }
    }
}

@Composable
private fun MigrationCardHitGroups(
    hits: List<MigrationSearchHit>,
    state: MigrationSingleState,
    onOpenCatalogue: (String, String) -> Unit,
) {
    groupMigrationHits(hits).forEach { group ->
        MiruSectionCard(
            title = group.sourceName,
            description = stringResource(
                R.string.migration_results_count_fmt,
                group.hits.size,
            ),
            actionLabel = stringResource(R.string.finder_gs_open_catalogue),
            onAction = { onOpenCatalogue(group.sourceId, state.query) },
            contentPadding = PaddingValues(MiruSpace.S),
        ) {
            MigrationResultTiles(
                hits = group.hits,
                selectedBookUrl = state.target?.bookUrl,
                selectedChapterCount = state.target?.chapters?.size,
                onSelect = state::select,
            )
        }
    }
}

@Composable
private fun MigrationPartialNotice(failedSources: List<String>) {
    if (failedSources.isEmpty()) return
    Text(
        text = stringResource(
            R.string.migration_search_partial_fmt,
            failedSources.joinToString(", "),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = LocalMiruExtraColors.current.muted,
    )
}

// Step 3 for many books: per-book search status, then the grouped matches.
@Composable
fun MigrationMassResultsStep(
    state: MassMigrationState,
    books: List<MigrationCandidateBook>,
    sources: List<dev.mirutime.app.features.finder.domain.CatalogSource>,
    sourceName: String,
    optionsSelected: Int,
    optionsTotal: Int,
    onOpenOptions: () -> Unit,
    onChangeBooks: () -> Unit,
    onOpenCatalogue: (String, String) -> Unit,
) {
    val selectedBooks = books.filter { it.bookUrl in state.selectedUrls }
    val search = state.searchUi
    val matched = (search as? MassSearchUi.Ready)
        ?.results
        ?.mapNotNull { result ->
            (result.outcome as? MassBookOutcome.Matched)?.let { result to it }
        }
        .orEmpty()
    val unmatched = (search as? MassSearchUi.Ready)
        ?.results
        ?.filter { it.outcome !is MassBookOutcome.Matched }
        .orEmpty()
    val notFound = unmatched.filter { it.outcome == MassBookOutcome.NotFound }
    val failed = unmatched.filter { it.outcome is MassBookOutcome.Failed }
    val summary = stringResource(
        R.string.migration_results_summary_fmt,
        matched.size,
        notFound.size,
        failed.size,
    )
    Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
        SectionHeader(
            title = stringResource(R.string.migration_results_title),
            description = stringResource(
                R.string.migration_meta_join_fmt,
                sourceName,
                when (search) {
                    MassSearchUi.Searching -> stringResource(R.string.migration_searching_short)
                    is MassSearchUi.Ready -> summary
                    is MassSearchUi.Failed -> stringResource(R.string.migration_check_failed_title)
                    MassSearchUi.Idle -> stringResource(
                        R.string.migration_selected_short_fmt,
                        selectedBooks.size,
                    )
                },
            ),
        )
        ChipRail {
            item(key = "change-books") {
                MiruFilterChip(
                    selected = false,
                    onClick = onChangeBooks,
                    label = stringResource(R.string.migration_change_books),
                    leadingIcon = Icons.Filled.SwapHoriz,
                )
            }
            item(key = "options") {
                MigrationOptionsChip(
                    selectedSources = optionsSelected,
                    totalSources = optionsTotal,
                    onClick = onOpenOptions,
                )
            }
        }
        when (val run = state.runUi) {
            MassRunUi.Idle -> Unit
            is MassRunUi.Running -> MigrationRunCard(run.done, run.total, run.title)
            is MassRunUi.Finished -> MassFinishedCard(run)
        }
        when (val current = state.searchUi) {
            MassSearchUi.Idle -> MigrationStateCard(
                title = stringResource(R.string.migration_search_empty_title),
                description = stringResource(R.string.migration_select_books_desc),
                icon = Icons.Filled.Search,
            )
            MassSearchUi.Searching -> MiruSectionCard(
                title = stringResource(R.string.migration_searching),
                description = stringResource(R.string.migration_mass_progress_desc),
                contentPadding = PaddingValues(),
            ) {
                selectedBooks.forEachIndexed { index, book ->
                    if (index > 0) MiruRowDivider()
                    MassSearchProgressRow(
                        book = book,
                        result = state.progress.firstOrNull {
                            it.candidate.bookUrl == book.bookUrl
                        },
                        searching = book.bookUrl in state.searchingUrls,
                    )
                }
            }
            is MassSearchUi.Failed -> MigrationErrorCard(
                message = current.message.ifBlank {
                    stringResource(R.string.migration_unknown_error)
                },
                onRetry = { state.search(books, sources) },
            )
            is MassSearchUi.Ready -> {
                if (matched.isEmpty() && unmatched.isEmpty()) {
                    MigrationStateCard(
                        title = stringResource(R.string.migration_search_empty_title),
                        description = stringResource(R.string.migration_search_empty),
                        icon = Icons.Filled.Search,
                    )
                } else {
                    matched.groupBy { it.second.target.sourceName }.forEach { (groupName, entries) ->
                        val firstTarget = entries.first().second.target
                        MiruSectionCard(
                            title = groupName,
                            description = stringResource(
                                R.string.migration_results_count_fmt,
                                entries.size,
                            ),
                            actionLabel = stringResource(R.string.finder_gs_open_catalogue),
                            onAction = {
                                onOpenCatalogue(firstTarget.sourceId, firstTarget.title)
                            },
                            contentPadding = PaddingValues(),
                        ) {
                            entries.forEachIndexed { index, entry ->
                                if (index > 0) MiruRowDivider()
                                MassMatchedRow(result = entry.first, matched = entry.second)
                            }
                        }
                    }
                    if (notFound.isNotEmpty()) {
                        MiruSectionCard(
                            title = stringResource(R.string.migration_unmatched_title),
                            description = pluralStringResource(
                                R.plurals.migration_library_count,
                                notFound.size,
                                notFound.size,
                            ),
                            contentPadding = PaddingValues(),
                        ) {
                            notFound.forEachIndexed { index, result ->
                                if (index > 0) MiruRowDivider()
                                MassOutcomeRow(result)
                            }
                        }
                    }
                    if (failed.isNotEmpty()) {
                        MiruSectionCard(
                            title = stringResource(R.string.migration_check_failed_title),
                            description = pluralStringResource(
                                R.plurals.migration_library_count,
                                failed.size,
                                failed.size,
                            ),
                            contentPadding = PaddingValues(),
                        ) {
                            failed.forEachIndexed { index, result ->
                                if (index > 0) MiruRowDivider()
                                MassOutcomeRow(result)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MassSearchProgressRow(
    book: MigrationCandidateBook,
    result: MassBookResult?,
    searching: Boolean,
) {
    val extra = LocalMiruExtraColors.current
    val outcome = result?.outcome
    val tint = when {
        outcome is MassBookOutcome.Failed -> MaterialTheme.colorScheme.error
        outcome is MassBookOutcome.NotFound -> extra.muted
        outcome is MassBookOutcome.Matched -> extra.accent
        else -> extra.muted
    }
    val detail = when {
        searching -> stringResource(R.string.migration_searching_short)
        outcome is MassBookOutcome.Matched -> stringResource(
            R.string.migration_search_found_fmt,
            outcome.target.sourceName,
        )
        outcome is MassBookOutcome.NotFound -> stringResource(R.string.migration_mass_not_found)
        outcome is MassBookOutcome.Failed -> outcome.message.ifBlank {
            stringResource(R.string.migration_unknown_error)
        }
        else -> stringResource(R.string.migration_search_waiting)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(MiruSize.Touch)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
        ) {
            when {
                searching -> CircularProgressIndicator(
                    strokeWidth = MiruSpace.Xxs,
                    color = tint,
                    modifier = Modifier.size(MiruSize.Touch / 3),
                )
                outcome is MassBookOutcome.Matched -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(MiruSize.Touch / 2),
                )
                outcome is MassBookOutcome.Failed -> Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(MiruSize.Touch / 2),
                )
                else -> Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(MiruSize.Touch / 2),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MassMatchedRow(
    result: MassBookResult,
    matched: MassBookOutcome.Matched,
) {
    val extra = LocalMiruExtraColors.current
    val noChapters = matched.plan.chaptersTotal <= 0
    MassResultRow(
        title = matched.target.title,
        subtitle = stringResource(R.string.migration_from_book_fmt, result.candidate.title),
        detail = if (noChapters) {
            stringResource(R.string.migration_no_chapters_to_match)
        } else {
            stringResource(
                R.string.migration_match_short_fmt,
                matched.plan.chaptersMatched,
                matched.plan.chaptersTotal,
            )
        },
        detailColor = if (noChapters) extra.muted else extra.accent,
        coverTitle = matched.target.title,
        coverUrl = matched.target.target.book.coverUrl,
    )
}

@Composable
private fun MassOutcomeRow(result: MassBookResult) {
    val failed = result.outcome is MassBookOutcome.Failed
    val extra = LocalMiruExtraColors.current
    val detail = when (val outcome = result.outcome) {
        MassBookOutcome.NotFound -> stringResource(R.string.migration_mass_not_found)
        is MassBookOutcome.Failed -> outcome.message.ifBlank {
            stringResource(R.string.migration_unknown_error)
        }
        is MassBookOutcome.Matched -> return
    }
    MassResultRow(
        title = result.candidate.title,
        subtitle = result.candidate.sourceName,
        detail = detail,
        detailColor = if (failed) MaterialTheme.colorScheme.error else extra.muted,
        icon = if (failed) Icons.Filled.Warning else Icons.Filled.Search,
        failed = failed,
    )
}

@Composable
private fun MassResultRow(
    title: String,
    subtitle: String,
    detail: String,
    detailColor: Color,
    coverTitle: String? = null,
    coverUrl: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    failed: Boolean = false,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        if (coverTitle != null) {
            BookCover(
                title = coverTitle,
                coverUrl = coverUrl,
                corner = MiruRadius.Cover,
                modifier = Modifier
                    .width(MiruSize.ButtonPrimary)
                    .aspectRatio(2f / 3f),
                showInitial = false,
                placeholderIcon = Icons.Filled.MenuBook,
            )
        } else if (icon != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(MiruSize.Touch)
                    .clip(CircleShape)
                    .background(
                        (if (failed) MaterialTheme.colorScheme.error else extra.muted)
                            .copy(alpha = 0.14f),
                    ),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        extra.muted
                    },
                    modifier = Modifier.size(MiruSize.Touch / 2),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = detailColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun contentTypeLabel(type: ContentType): String = stringResource(
    when (type) {
        ContentType.Novel -> R.string.type_novel
        ContentType.Manga -> R.string.type_manga
        ContentType.Video -> R.string.type_video
    },
)
