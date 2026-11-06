@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.finder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.uikit.ChipOption
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruSpace
import kotlinx.coroutines.delay
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.uikit.MiruSearchDebounceMillis

// Multi-select chip group for long option lists.
//   <= SearchThreshold options: plain chips, collapsed to 3 rows with
//     "Show all (N)" past CollapseAfter options.
//   >  SearchThreshold: a search field appears; at most RenderCap chips are
//     rendered at once, the rest is reached by typing (substring, ignoring
//     case, debounced 200 ms). Selected options are pinned on top whatever
//     the query is, so a choice is never lost from view; an empty match shows
//     a "nothing found" line instead of a void.
private const val SearchThreshold = 20
private const val CollapseAfter = 9
private const val RenderCap = 100

@Composable
fun FilterChipGroup(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    FilterChipGroup(
        options = options.map { ChipOption(it, it) },
        selected = selected,
        onToggle = onToggle,
    )
}

@Composable
fun FilterChipGroup(
    options: List<ChipOption>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    // App-owned genre lists are alphabetical; a plugin's declared order is
    // meaningful and must not be re-sorted.
    sortAlphabetically: Boolean = true,
) {
    val extra = LocalMiruExtraColors.current
    val sorted = remember(options, sortAlphabetically) {
        val unique = options.distinctBy { it.id }
        if (sortAlphabetically) unique.sortedBy { it.label.lowercase() } else unique
    }
    val searchable = sorted.size > SearchThreshold

    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        // Clearing is instant, typing waits for a pause.
        if (query.isNotEmpty()) delay(MiruSearchDebounceMillis)
        debounced = query
    }

    val pinned = remember(sorted, selected) { sorted.filter { it.id in selected } }
    val matches = remember(sorted, debounced, searchable) {
        if (!searchable || debounced.isBlank()) {
            sorted
        } else {
            sorted.filter {
                it.label.contains(debounced.trim(), ignoreCase = true) ||
                    it.id.contains(debounced.trim(), ignoreCase = true)
            }
        }
    }
    val rest = remember(matches, pinned) {
        matches.filter { it.id !in selected }.take((RenderCap - pinned.size).coerceAtLeast(0))
    }

    if (searchable) {
        MiruSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.finder_options_search_hint),
            onClear = { query = "" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MiruSpace.S),
            plate = false,
        )
    }

    val collapsible = !searchable && sorted.size > CollapseAfter
    val shown = pinned + rest
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        maxLines = if (collapsible && !expanded) 3 else Int.MAX_VALUE,
    ) {
        shown.forEach { option ->
            MiruFilterChip(
                selected = option.id in selected,
                onClick = { onToggle(option.id) },
                label = option.label,
            )
        }
    }
    if (searchable && debounced.isNotBlank() && rest.isEmpty()) {
        Text(
            text = stringResource(R.string.finder_options_nothing_found),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.S),
        )
    }
    val pinnedInMatches = pinned.count { option -> matches.any { it.id == option.id } }
    if (searchable && matches.size - pinnedInMatches > rest.size) {
        Text(
            text = stringResource(R.string.finder_options_type_to_narrow),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.S),
        )
    }
    if (collapsible) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.finder_genres_less)
                } else {
                    stringResource(R.string.finder_genres_show_all, sorted.size)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
