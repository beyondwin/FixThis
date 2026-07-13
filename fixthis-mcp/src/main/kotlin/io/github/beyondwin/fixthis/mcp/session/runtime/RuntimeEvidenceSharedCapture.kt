package io.github.beyondwin.fixthis.mcp.session.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface RuntimeEvidencePreparedCapture {
    data class Terminal(val result: RuntimeEvidenceCaptureResult) : RuntimeEvidencePreparedCapture

    data class Committed(
        val bundle: CommittedRuntimeEvidenceBundle,
        val attachments: List<RuntimeEvidenceAttachment>,
        val aggregate: RuntimeEvidenceStatus,
        val warnings: Set<RuntimeEvidenceWarning>,
    ) : RuntimeEvidencePreparedCapture
}

internal enum class RuntimeEvidenceLinkDisposition {
    SUCCESS,
    EXACT_REJECTION,
    PRESERVE,
    NONE,
}

internal data class RuntimeEvidenceLinkCompletion(
    val result: RuntimeEvidenceCaptureResult,
    val disposition: RuntimeEvidenceLinkDisposition,
)

internal data class CaptureKey(
    val projectRoot: String,
    val sessionId: String,
    val screenId: String,
    val preset: RuntimeEvidencePreset,
    val deviceSerial: String,
    val installEpochMillis: Long?,
)

internal class RuntimeEvidenceSharedLease(
    private val entry: RuntimeEvidenceSharedEntry,
) {
    private val released = AtomicBoolean()

    suspend fun await(): RuntimeEvidencePreparedCapture = entry.deferred.await()

    fun release(
        disposition: RuntimeEvidenceLinkDisposition,
        deleteUnlinkedBundle: (() -> Unit)? = null,
    ) {
        if (released.compareAndSet(false, true)) entry.release(disposition, deleteUnlinkedBundle)
    }
}

internal object RuntimeEvidenceCaptureRuntime {
    private val semaphore = Semaphore(MAX_CONCURRENT_COLLECTORS)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap<CaptureKey, RuntimeEvidenceSharedEntry>()

    suspend fun <T> collect(block: suspend () -> T): T = semaphore.withPermit { block() }

    fun acquire(
        key: CaptureKey,
        block: suspend () -> RuntimeEvidencePreparedCapture,
    ): RuntimeEvidenceSharedLease {
        while (true) {
            val candidate = RuntimeEvidenceSharedEntry(scope.async(start = CoroutineStart.LAZY) { block() })
            val existing = inFlight.putIfAbsent(key, candidate)
            if (existing == null) {
                candidate.start { inFlight.remove(key, candidate) }
                return RuntimeEvidenceSharedLease(candidate)
            }
            candidate.deferred.cancel()
            existing.tryAcquire()?.let { return it }
        }
    }
}

internal class RuntimeEvidenceSharedEntry(
    val deferred: Deferred<RuntimeEvidencePreparedCapture>,
) {
    private var activeLeases = 1
    private var successfulLinks = 0
    private var exactRejections = 0
    private var preservationSignals = 0
    private var closed = false
    private var deletionIssued = false
    private var deleteAction: (() -> Unit)? = null
    private lateinit var removeFromRegistry: () -> Unit

    fun start(removeFromRegistry: () -> Unit) {
        this.removeFromRegistry = removeFromRegistry
        deferred.invokeOnCompletion { closeIfIdle() }
        deferred.start()
    }

    fun tryAcquire(): RuntimeEvidenceSharedLease? = synchronized(this) {
        if (closed) {
            null
        } else {
            activeLeases += 1
            RuntimeEvidenceSharedLease(this)
        }
    }

    fun release(
        disposition: RuntimeEvidenceLinkDisposition,
        deleteUnlinkedBundle: (() -> Unit)?,
    ) {
        synchronized(this) {
            when (disposition) {
                RuntimeEvidenceLinkDisposition.SUCCESS -> successfulLinks += 1
                RuntimeEvidenceLinkDisposition.EXACT_REJECTION -> {
                    exactRejections += 1
                    if (deleteUnlinkedBundle != null) deleteAction = deleteUnlinkedBundle
                }
                RuntimeEvidenceLinkDisposition.PRESERVE -> preservationSignals += 1
                RuntimeEvidenceLinkDisposition.NONE -> Unit
            }
            activeLeases -= 1
            check(activeLeases >= 0) { "Runtime evidence shared lease released too many times" }
        }
        closeIfIdle()
    }

    private fun closeIfIdle() {
        val actions = synchronized(this) {
            if (closed || activeLeases != 0 || !deferred.isCompleted) {
                CloseActions()
            } else {
                closed = true
                val shouldDelete = exactRejections > 0 &&
                    successfulLinks == 0 &&
                    preservationSignals == 0 &&
                    !deletionIssued
                if (shouldDelete) deletionIssued = true
                CloseActions(remove = true, delete = if (shouldDelete) deleteAction else null)
            }
        }
        if (actions.remove) removeFromRegistry()
        actions.delete?.invoke()
    }

    private data class CloseActions(
        val remove: Boolean = false,
        val delete: (() -> Unit)? = null,
    )
}

private const val MAX_CONCURRENT_COLLECTORS = 2
