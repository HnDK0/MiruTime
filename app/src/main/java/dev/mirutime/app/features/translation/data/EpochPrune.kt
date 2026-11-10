package dev.mirutime.app.features.translation.data

/**
 * Bounded epoch table. The prune keeps the newest entries and never drops a
 * chapter whose request is still in flight: `forgotten()` compares against the
 * epoch, and evicting it would let a cancelled worker publish late results.
 */
internal fun pruneEpochs(
    epochs: LinkedHashMap<String, Int>,
    inflight: Set<String>,
    limit: Int,
): Int {
    var removed = 0
    val iterator = epochs.entries.iterator()
    while (epochs.size > limit && iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key in inflight) continue
        iterator.remove()
        removed++
    }
    return removed
}
