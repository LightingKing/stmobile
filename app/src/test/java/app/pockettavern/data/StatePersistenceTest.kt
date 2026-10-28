package app.pockettavern.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StatePersistenceTest {
    @Test fun committedRepairDiscardMarkerRemainsApplicableAfterConversationChanges() {
        val restoredFingerprint = "a".repeat(64)
        val laterFingerprint = "b".repeat(64)
        val prepared = RepairBackupDiscardMarker.prepared(restoredFingerprint)

        assertTrue(prepared.isApplicableTo(restoredFingerprint))
        assertFalse(prepared.isApplicableTo(laterFingerprint))

        val committed = prepared.committed()
        assertTrue(committed.isApplicableTo(laterFingerprint))
        assertEquals(committed, RepairBackupDiscardMarker.decode(committed.encode()))
    }

    @Test fun repairDiscardMarkerReadsLegacyPreparedStateAndRejectsUnsafeInput() {
        val fingerprint = "c".repeat(64)

        assertEquals(
            RepairBackupDiscardMarker.prepared(fingerprint),
            RepairBackupDiscardMarker.decode("{\"targetStateSha256\":\"$fingerprint\"}")
        )
        assertNull(RepairBackupDiscardMarker.decode("{\"phase\":\"committed\",\"targetStateSha256\":\"$fingerprint\"}"))
        assertNull(RepairBackupDiscardMarker.decode("{\"version\":99,\"phase\":\"committed\",\"targetStateSha256\":\"$fingerprint\"}"))
        assertNull(RepairBackupDiscardMarker.decode("{\"version\":1,\"phase\":\"committed\",\"targetStateSha256\":\"not-a-fingerprint\"}"))
        assertNull(RepairBackupDiscardMarker.decode("not json"))
    }

    @Test(timeout = 10_000) fun highFrequencySubmitCoalescesToNewestPendingSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val persistence = StatePersistence<Int>(scope, write = {
                writes += it
                if (it == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            })

            persistence.submit(1)
            entered.await()
            (2..100).forEach(persistence::submit)
            val flushed = async(start = CoroutineStart.UNDISPATCHED) { persistence.flush(100) }
            release.complete(Unit)
            flushed.await()

            assertEquals(listOf(1, 100), writes)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test(timeout = 10_000) fun flushWaitsForActualWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            var written = false
            val persistence = StatePersistence<Int>(scope, write = {
                started.complete(Unit)
                release.await()
                written = true
            })

            val pending = async { persistence.flush(7) }
            started.await()
            delay(20)
            assertTrue(!written)
            assertTrue(!pending.isCompleted)
            release.complete(Unit)
            pending.await()
            assertTrue(written)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test(timeout = 10_000) fun flushReplacesOlderPendingSnapshotAndNewValueIsActuallyWritten() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val writes = mutableListOf<Int>()
            val persistence = StatePersistence<Int>(scope, write = {
                writes += it
                if (it == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            })

            val first = async { persistence.flush(1) }
            firstStarted.await()
            persistence.submit(2)
            val second = async(start = CoroutineStart.UNDISPATCHED) { persistence.flush(3) }
            releaseFirst.complete(Unit)
            first.await()
            second.await()

            assertEquals(listOf(1, 3), writes)
            assertEquals(3, writes.last())
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test(timeout = 10_000) fun failedWritePropagatesAndLaterFlushCanContinue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val errors = mutableListOf<Throwable>()
            val errorObserved = CompletableDeferred<Unit>()
            var attempts = 0
            val persistence = StatePersistence<Int>(scope, write = {
                attempts++
                if (it == 1) error("disk full")
            }, onError = { error -> errors += error; errorObserved.complete(Unit) })

            try {
                persistence.flush(1)
                fail("expected failed flush")
            } catch (error: IllegalStateException) {
                assertEquals("disk full", error.message)
            }
            errorObserved.await()
            persistence.flush(2)

            assertEquals(2, attempts)
            assertEquals(1, errors.size)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }

    @Test(timeout = 10_000) fun scopeCancellationDoesNotLeaveFlushSuspended() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val started = CompletableDeferred<Unit>()
            val persistence = StatePersistence<Int>(scope, write = {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
            })
            val pending = async { persistence.flush(1) }
            started.await()
            scope.cancel()

            val failure = runCatching { withTimeout(1_000) { pending.await() } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(pending.isCompleted)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }
}
