package dev.mirutime.data.library

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One process-wide lock registry per logical write target. A downloaded page,
 * a cache-on-read body, a migration target and a deletion must never write the
 * same book at the same time.
 *
 * Keys are strings so a migration can lock a target that has no bookId yet.
 * Multi-lock acquisition is sorted to avoid deadlocks.
 */
class BookWriteLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun bookKey(bookId: Long): String = "book:$bookId"

    fun targetKey(installId: String, url: String): String = "target:$installId|$url"

    fun mutex(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    suspend fun <T> withLock(key: String, block: suspend () -> T): T =
        mutex(key).withLock { block() }

    suspend fun <T> withLocks(keys: Collection<String>, block: suspend () -> T): T {
        val ordered = keys.distinct().sorted().map(::mutex)
        var acquired = 0
        try {
            for (mutex in ordered) {
                mutex.lock()
                acquired++
            }
            return block()
        } finally {
            for (index in acquired - 1 downTo 0) {
                ordered[index].unlock()
            }
        }
    }
}
