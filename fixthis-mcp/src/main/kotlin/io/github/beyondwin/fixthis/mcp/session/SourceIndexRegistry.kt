package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SourceIndexRegistry {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, SourceIndex?>()

    suspend fun cached(packageName: String): SourceIndex? = mutex.withLock {
        entries[packageName]
    }

    suspend fun contains(packageName: String): Boolean = mutex.withLock {
        entries.containsKey(packageName)
    }

    suspend fun put(packageName: String, sourceIndex: SourceIndex?) {
        mutex.withLock {
            entries[packageName] = sourceIndex
        }
    }
}
