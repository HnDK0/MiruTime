package dev.mirutime.app.design

import dev.mirutime.core.log.MiruLog

/**
 * Pure anchor-aware multi-selection state. Long-pressing when inactive starts
 * selection and anchors on that item; long-pressing another item selects the
 * inclusive span between the current anchor and the target inside [orderedKeys].
 */
data class RangeSelection<T>(
    val active: Boolean = false,
    val selected: Set<T> = emptySet(),
    val anchor: T? = null,
) {
    val count: Int get() = selected.size
    val isEmpty: Boolean get() = selected.isEmpty()

    fun isSelected(key: T): Boolean = key in selected

    fun longPress(key: T, orderedKeys: List<T> = emptyList()): RangeSelection<T> {
        if (!active) {
            return RangeSelection(active = true, selected = setOf(key), anchor = key)
        }
        val currentAnchor = anchor
        if (currentAnchor != null && currentAnchor != key && orderedKeys.isNotEmpty()) {
            val from = orderedKeys.indexOf(currentAnchor)
            val to = orderedKeys.indexOf(key)
            if (from >= 0 && to >= 0) {
                val range = orderedKeys.subList(minOf(from, to), maxOf(from, to) + 1)
                return copy(selected = selected + range, anchor = key)
            }
        }
        return copy(selected = selected + key, anchor = key)
    }

    fun toggle(key: T, autoDeactivateWhenEmpty: Boolean = false): RangeSelection<T> {
        if (!active) return this
        if (key in selected) {
            val nextSelected = selected - key
            if (autoDeactivateWhenEmpty && nextSelected.isEmpty()) return close()
            return copy(
                selected = nextSelected,
                anchor = if (anchor == key) null else anchor,
            )
        }
        return copy(selected = selected + key, anchor = key)
    }

    fun selectAll(keys: Iterable<T>, additive: Boolean = false): RangeSelection<T> {
        if (!active) return this
        val next = keys.toSet()
        return copy(
            selected = if (additive) selected + next else next,
            anchor = null,
        )
    }

    fun clear(keepActive: Boolean = true): RangeSelection<T> =
        if (keepActive && active) RangeSelection(active = true) else close()

    fun close(): RangeSelection<T> = RangeSelection()

    fun prune(validKeys: Set<T>, autoDeactivateWhenEmpty: Boolean = false): RangeSelection<T> {
        if (!active) return this
        val kept = selected.intersect(validKeys)
        if (autoDeactivateWhenEmpty && kept.isEmpty()) return close()
        val keptAnchor = anchor?.takeIf { it in validKeys }
        return copy(selected = kept, anchor = keptAnchor)
    }
}

internal fun logRangeSelect(surface: String, previousCount: Int, nextCount: Int) {
    MiruLog.d("selection", "event=selection_change surface=$surface prev=$previousCount count=$nextCount")
}
