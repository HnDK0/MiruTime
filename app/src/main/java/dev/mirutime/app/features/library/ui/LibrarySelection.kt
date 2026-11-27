package dev.mirutime.app.features.library.ui

import dev.mirutime.app.design.RangeSelection

// Pure library multi-select state. Identity is the opaque BookKey
// (installId + URL): two installs may expose different works under one URL.
data class LibrarySelection(
    val active: Boolean = false,
    val selected: Set<String> = emptySet(),
    val anchor: String? = null,
) {
    val count: Int get() = selected.size
    val isEmpty: Boolean get() = selected.isEmpty()

    fun isSelected(bookKey: String): Boolean = bookKey in selected
}

sealed interface LibrarySelectionAction {
    data class LongPress(
        val bookKey: String,
        val orderedKeys: List<String> = emptyList(),
    ) : LibrarySelectionAction
    data class Toggle(val bookKey: String) : LibrarySelectionAction
    data class SelectAll(val bookKeys: Collection<String>) : LibrarySelectionAction
    data object Clear : LibrarySelectionAction
    data object Close : LibrarySelectionAction
}

fun reduceLibrarySelection(
    state: LibrarySelection,
    action: LibrarySelectionAction,
): LibrarySelection = when (action) {
    is LibrarySelectionAction.LongPress ->
        state.toRangeSelection().longPress(action.bookKey, action.orderedKeys).toLibrarySelection()
    is LibrarySelectionAction.Toggle ->
        state.toRangeSelection().toggle(action.bookKey).toLibrarySelection()
    is LibrarySelectionAction.SelectAll ->
        state.toRangeSelection().selectAll(action.bookKeys.filter { it.isNotBlank() }).toLibrarySelection()
    LibrarySelectionAction.Clear ->
        state.toRangeSelection().clear(keepActive = true).toLibrarySelection()
    LibrarySelectionAction.Close -> LibrarySelection()
}

private fun LibrarySelection.toRangeSelection(): RangeSelection<String> =
    RangeSelection(active = active, selected = selected, anchor = anchor)

private fun RangeSelection<String>.toLibrarySelection(): LibrarySelection =
    LibrarySelection(active = active, selected = selected, anchor = anchor)
