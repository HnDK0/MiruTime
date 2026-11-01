package dev.mirutime.app.features.library.ui

// Pure library multi-select state, keyed by the stable book URL. Kept out of
// the composable so the toggle/all/clear rules stay unit-testable.
data class LibrarySelection(
    val active: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    val count: Int get() = selected.size
    val isEmpty: Boolean get() = selected.isEmpty()

    fun isSelected(bookUrl: String): Boolean = bookUrl in selected
}

sealed interface LibrarySelectionAction {
    data class LongPress(val bookUrl: String) : LibrarySelectionAction
    data class Toggle(val bookUrl: String) : LibrarySelectionAction
    data class SelectAll(val bookUrls: Collection<String>) : LibrarySelectionAction
    data object Clear : LibrarySelectionAction
    data object Close : LibrarySelectionAction
}

fun reduceLibrarySelection(
    state: LibrarySelection,
    action: LibrarySelectionAction,
): LibrarySelection = when (action) {
    is LibrarySelectionAction.LongPress -> {
        val url = action.bookUrl
        if (state.active) {
            state.copy(selected = state.selected.toggled(url))
        } else {
            LibrarySelection(active = true, selected = setOf(url))
        }
    }
    is LibrarySelectionAction.Toggle ->
        if (state.active) state.copy(selected = state.selected.toggled(action.bookUrl)) else state
    is LibrarySelectionAction.SelectAll ->
        if (state.active) {
            state.copy(selected = action.bookUrls.filter { it.isNotBlank() }.toSet())
        } else {
            state
        }
    LibrarySelectionAction.Clear ->
        if (state.active) state.copy(selected = emptySet()) else state
    LibrarySelectionAction.Close -> LibrarySelection()
}

private fun Set<String>.toggled(url: String): Set<String> =
    if (url in this) this - url else this + url
