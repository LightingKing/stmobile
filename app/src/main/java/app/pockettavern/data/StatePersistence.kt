package app.pockettavern.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal enum class RepairBackupDiscardPhase(val wireValue: String) {
    PREPARED("prepared"),
    COMMITTED("committed")
}

/**
 * A prepared marker is only valid while the restored database still has the
 * expected fingerprint. Once the database write succeeds, a committed marker
 * remains valid across later conversation writes until the old repair snapshots
 * have actually been removed.
 */
internal data class RepairBackupDiscardMarker(
    val targetStateSha256: String,
    val phase: RepairBackupDiscardPhase
) {
    val isCommitted: Boolean get() = phase == RepairBackupDiscardPhase.COMMITTED

    fun isApplicableTo(currentStateSha256: String): Boolean =
        isCommitted || targetStateSha256 == currentStateSha256

    fun committed(): RepairBackupDiscardMarker = copy(phase = RepairBackupDiscardPhase.COMMITTED)

    fun encode(): String = JSONObject()
        .put("version", VERSION)
        .put("phase", phase.wireValue)
        .put("targetStateSha256", targetStateSha256)
        .toString()

    companion object {
        private const val VERSION = 1
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun prepared(targetStateSha256: String): RepairBackupDiscardMarker =
            RepairBackupDiscardMarker(targetStateSha256, RepairBackupDiscardPhase.PREPARED)

        fun decode(raw: String): RepairBackupDiscardMarker? = runCatching {
            val root = JSONObject(raw)
            val version = if (root.has("version")) root.getInt("version") else 0
            require(version == 0 || version == VERSION)
            val target = root.getString("targetStateSha256")
            require(SHA256.matches(target))
            val phase = if (version == 0) {
                require(!root.has("phase"))
                RepairBackupDiscardPhase.PREPARED
            } else when (root.optString("phase")) {
                "", RepairBackupDiscardPhase.PREPARED.wireValue -> RepairBackupDiscardPhase.PREPARED
                RepairBackupDiscardPhase.COMMITTED.wireValue -> RepairBackupDiscardPhase.COMMITTED
                else -> error("未知的修复快照清理阶段")
            }
            RepairBackupDiscardMarker(target, phase)
        }.getOrNull()
    }
}

/**
 * Serializes state writes while coalescing snapshots that have not started yet.
 *
 * [submit] only updates an in-memory pending snapshot and signals the worker. A
 * writer that is already running is allowed to finish; every snapshot submitted
 * while it is running is merged into the newest pending value. [flush] enqueues
 * the supplied value as a new version and waits for
 * a real successful write, rather than merely waiting for the value to enter a
 * channel.
 */
class StatePersistence<T>(
    scope: CoroutineScope,
    private val write: suspend (T) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    private data class Pending<T>(val version: Long, val snapshot: T)
    private class Waiter(val version: Long, val completion: CompletableDeferred<Unit>)

    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val waiters = ArrayList<Waiter>()
    private var pending: Pending<T>? = null
    private var nextVersion = 0L
    private var closed = false
    private val worker: Job = scope.launch {
        try {
            for (ignored in signal) {
                drain()
            }
        } finally {
            val cancellations: List<CompletableDeferred<Unit>>
            synchronized(lock) {
                closed = true
                pending = null
                cancellations = waiters.map { it.completion }
                waiters.clear()
            }
            val cancellation = CancellationException("状态写入作用域已取消")
            cancellations.forEach { it.cancel(cancellation) }
            signal.close()
        }
    }

    /**
     * Non-blocking submission. If a write is in progress, older not-yet-started
     * snapshots are replaced by this one.
     */
    fun submit(snapshot: T) {
        synchronized(lock) {
            if (closed || !worker.isActive) return
            pending = Pending(++nextVersion, snapshot)
            signal.trySend(Unit)
        }
    }

    /**
     * Enqueues [snapshot] as the newest version and waits for it (or a later
     * version submitted concurrently) to be written successfully. A writer
     * failure is propagated.
     */
    suspend fun flush(snapshot: T) {
        val waiter: Waiter
        synchronized(lock) {
            check(!closed && worker.isActive) { "状态写入作用域已取消" }
            // Always give the caller's snapshot a fresh version. Reusing an
            // existing pending value could silently drop the final state that
            // flush was explicitly asked to persist.
            val target = Pending(++nextVersion, snapshot).also { pending = it }
            waiter = Waiter(target.version, CompletableDeferred())
            waiters += waiter
            signal.trySend(Unit)
            waiter.completion.invokeOnCompletion {
                synchronized(lock) { waiters.remove(waiter) }
            }
        }
        waiter.completion.await()
    }

    private suspend fun drain() {
        while (true) {
            val current = synchronized(lock) {
                pending.also { pending = null }
            } ?: return

            var failure: Throwable? = null
            try {
                write(current.snapshot)
            } catch (error: Throwable) {
                if (!currentCoroutineContext().isActive) throw error
                failure = error
            }

            val toComplete: List<Waiter>
            synchronized(lock) {
                toComplete = waiters.filter { it.version <= current.version }
                waiters.removeAll(toComplete.toSet())
            }
            if (failure == null) {
                toComplete.forEach { it.completion.complete(Unit) }
            } else {
                toComplete.forEach { it.completion.completeExceptionally(failure!!) }
                runCatching { onError(failure!!) }
            }
            // A submit racing with the write leaves a newer value in pending; it
            // is drained in this same worker before waiting for another signal.
        }
    }
}
