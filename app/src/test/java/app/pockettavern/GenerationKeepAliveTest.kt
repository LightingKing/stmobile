package app.pockettavern

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationKeepAliveTest {
    private class FakeLock : GenerationKeepAlive.Lock {
        var acquired = 0
        var released = 0
        var timeoutMs = 0L
        override var isHeld = false
        override fun acquire(timeoutMs: Long) { acquired++; this.timeoutMs = timeoutMs; isHeld = true }
        override fun release() { released++; isHeld = false }
    }

    @Test fun holdsOneCpuLockForTheGenerationAndReleasesItWhenTheServiceStops() {
        val lock = FakeLock()
        val keepAlive = GenerationKeepAlive { lock }
        assertTrue(keepAlive.hold())
        assertTrue(keepAlive.hold())
        assertTrue(keepAlive.held)
        assertEquals("a token handoff must not stack a second lock", 1, lock.acquired)
        assertTrue("the lock must expire even if a stop callback is lost", lock.timeoutMs in 1..GenerationKeepAlive.TIMEOUT_MS)
        keepAlive.release()
        keepAlive.release()
        assertFalse(keepAlive.held)
        assertEquals(1, lock.released)
    }

    @Test fun expiredLockIsAcquiredAgainForTheNextGeneration() {
        val locks = mutableListOf<FakeLock>()
        val keepAlive = GenerationKeepAlive { FakeLock().also(locks::add) }
        keepAlive.hold()
        locks.single().isHeld = false
        assertFalse(keepAlive.held)
        keepAlive.hold()
        assertEquals(2, locks.size)
        assertTrue(locks.last().isHeld)
    }

    @Test fun missingPowerServiceOrPlatformErrorNeverBreaksGeneration() {
        assertFalse(GenerationKeepAlive { null }.hold())
        val failing = GenerationKeepAlive { object : GenerationKeepAlive.Lock {
            override val isHeld = false
            override fun acquire(timeoutMs: Long) = throw SecurityException("WAKE_LOCK denied")
            override fun release() = Unit
        } }
        assertFalse(failing.hold())
        failing.release()
        assertFalse(failing.held)
    }

    @Test fun safetyTimeoutOutlastsTheLongestIdleStream() {
        assertTrue(GenerationKeepAlive.TIMEOUT_MS >= 30 * 60_000L)
    }

    @Test fun manifestDeclaresOnlyTheWakeLockNeededForScreenOffGeneration() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))
        assertFalse("no battery-optimization exemption without device evidence", manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        val service = Regex("<service[^>]*GenerationService[^>]*/>").find(manifest)?.value.orEmpty()
        assertTrue(service.contains("android:stopWithTask=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"dataSync\""))
    }
}
