package dev.mirutime.core.network.cache

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun <T> platformSynchronized(lock: Any, block: () -> T): T = synchronized(lock, block)
