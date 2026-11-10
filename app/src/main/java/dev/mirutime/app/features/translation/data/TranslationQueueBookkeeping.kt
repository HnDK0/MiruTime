package dev.mirutime.app.features.translation.data

/**
 * Queue keys are owned by one enqueue. A superseded request must not delete the
 * key a newer request just added, so each enqueue gets an id and only that id
 * may release the key.
 */
internal class TranslationQueueBookkeeping {
    private val owners = HashMap<String, Long>()
    private var nextId = 1L

    fun claim(key: String): Long {
        val id = nextId++
        owners[key] = id
        return id
    }

    fun isQueued(key: String): Boolean = owners.containsKey(key)

    fun release(key: String, id: Long): Boolean {
        if (owners[key] != id) return false
        owners.remove(key)
        return true
    }

    fun remove(key: String): Boolean = owners.remove(key) != null

    fun removeAll(predicate: (String) -> Boolean): Int {
        val victims = owners.keys.filter(predicate)
        victims.forEach { owners.remove(it) }
        return victims.size
    }

    fun size(): Int = owners.size
}
