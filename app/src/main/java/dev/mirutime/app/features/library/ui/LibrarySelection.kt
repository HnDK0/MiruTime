package dev.mirutime.app.features.library.ui

// Pure library multi-select state. Identity is the opaque BookKey
// (installId + URL): two installs may expose different works under one URL.
data class LibrarySelection(
    val active: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    val count: Int get() = selected.size
    val isEmpty: Boolean get() = selected.isEmpty()

    fun isSelected(bookKey: String): Boolean = bookKey in selected
}

sealed interface LibrarySelectionAction {
    data class LongPress(val bookKey: String) : LibrarySelectionAction
    data class Toggle(val bookKey: String) : LibrarySelectionAction
    data class SelectAll(val bookKeys: Collection<String>) : LibrarySelectionAction
    data object Clear : LibrarySelectionAction
    data object Close : LibrarySelectionAction
}

fun reduceLibrarySelection(
    state: LibrarySelection,
    action: LibrarySelectionAction,
): LibrarySelection = when (action) {
    is LibrarySelectionAction.LongPress -> {
        val key = action.bookKey
        if (state.active) {
            state.toggled(key)
        } else {
            LibrarySelection(active = true, selected = setOf(key))
        }
    }
    is LibrarySelectionAction.Toggle ->
        if (state.active) state.toggled(action.bookKey) else state
    is LibrarySelectionAction.SelectAll ->
        if (state.active) {
            state.copy(
                selected = action.bookKeys.filter { it.isNotBlank() }.toSet(),
            )
        } else {
            state
        }
    LibrarySelectionAction.Clear ->
        if (state.active) state.copy(selected = emptySet()) else state
    LibrarySelectionAction.Close -> LibrarySelection()
}

private fun LibrarySelection.toggled(key: String): LibrarySelection =
    copy(selected = if (key in selected) selected - key else selected + key)
