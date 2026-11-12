package app.pockettavern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * One foreground service keeps every background reply alive: it starts with the first chat that
 * needs it and stops only after the last one has finished.
 */
class SharedLeaseTest {
    private class Lease
    private val opened = mutableListOf<Lease>()
    private val closed = mutableListOf<Lease>()
    private val leases = SharedLease(open = { Lease().also(opened::add) }, close = { closed += it })

    @Test fun severalChatsShareOneServiceUntilTheLastFinishes() {
        val first = leases.acquire()
        val second = leases.acquire()
        val third = leases.acquire()
        assertSame(first, second)
        assertSame(first, third)
        assertEquals("the service is started once", 1, opened.size)
        leases.release()
        leases.release()
        assertEquals("two chats finished, one is still replying", 0, closed.size)
        assertSame(first, leases.current)
        leases.release()
        assertEquals(listOf(first), closed)
        assertNull(leases.current)
    }

    @Test fun aReplyStartedAfterEverythingFinishedGetsAFreshService() {
        val first = leases.acquire()
        leases.release()
        val second = leases.acquire()
        assertNotSame("a finished service's token must not be reused", first, second)
        assertEquals(2, opened.size)
        assertEquals(listOf(first), closed)
        assertSame(second, leases.current)
    }

    @Test fun anExtraReleaseNeitherClosesTwiceNorBreaksTheNextStart() {
        leases.release()
        assertEquals(0, closed.size)
        leases.acquire()
        leases.release()
        leases.release()
        assertEquals(1, closed.size)
        leases.acquire()
        assertEquals(2, opened.size)
        leases.release()
        assertEquals(2, closed.size)
    }
}
