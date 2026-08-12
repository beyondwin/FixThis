package io.github.beyondwin.fixthis.mcp.session.source

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SourceIndexRegistry {
    private val mutex = Mutex()
    private val entries = mutableMapOf<SourceIndexCacheKey, SourceIndex?>()

    suspend fun cached(packageName: String): SourceIndex? = cached(packageName, installEpochMillis = null)

    suspend fun cached(packageName: String, installEpochMillis: Long?): SourceIndex? = mutex.withLock {
        entries[SourceIndexCacheKey(packageName, installEpochMillis)]
    }

    suspend fun contains(packageName: String): Boolean = contains(packageName, installEpochMillis = null)

    suspend fun contains(packageName: String, installEpochMillis: Long?): Boolean = mutex.withLock {
        entries.containsKey(SourceIndexCacheKey(packageName, installEpochMillis))
    }

    suspend fun put(packageName: String, sourceIndex: SourceIndex?) = put(packageName, installEpochMillis = null, sourceIndex)

    suspend fun put(packageName: String, installEpochMillis: Long?, sourceIndex: SourceIndex?) {
        mutex.withLock {
            val key = SourceIndexCacheKey(packageName, installEpochMillis)
            entries.keys.removeAll { cached -> cached.packageName == packageName && cached != key }
            entries[key] = sourceIndex
        }
    }
}

private data class SourceIndexCacheKey(
    val packageName: String,
    val installEpochMillis: Long?,
)
