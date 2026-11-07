package dev.mirutime.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class BookWriteLocksTest {
    @Test
    fun `same book key serializes writers`() = runTest {
        val locks = BookWriteLocks()
        var active = 0
        var maxActive = 0

        val first = async {
            locks.withLock(locks.bookKey(1)) {
                active++
                maxActive = maxOf(maxActive, active)
                yield()
                active--
            }
        }
        val second = async {
            locks.withLock(locks.bookKey(1)) {
                active++
                maxActive = maxOf(maxActive, active)
                yield()
                active--
            }
        }
        first.await()
        second.await()

        assertEquals(1, maxActive)
    }

    @Test
    fun `multi lock acquisition is sorted and does not deadlock`() = runTest {
        val locks = BookWriteLocks()
        val first = async {
            locks.withLocks(listOf("book:2", "book:1")) {
                yield()
                "first"
            }
        }
        val second = async {
            locks.withLocks(listOf("book:1", "book:2")) {
                yield()
                "second"
            }
        }

        assertEquals(setOf("first", "second"), setOf(first.await(), second.await()))
    }
}
