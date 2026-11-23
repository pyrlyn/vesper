package scull.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FairLockTest {
    @Test
    fun aWaiterGoesBeforeTheHolderThatRetakesTheLock() {
        val lock = FairLock()
        val order = Monitor(1)
        val taken = ArrayList<String>()
        lock.lock()
        val waiter =
            Background {
                lock.withLock { order.locked { taken += "waiter" } }
            }
        // Long enough for the waiter to draw its ticket; the order below is then fixed.
        sleep(100.milliseconds)
        lock.unlock()
        lock.withLock { order.locked { taken += "holder" } }
        waiter.join()
        assertEquals(listOf("waiter", "holder"), order.locked { taken.toList() })
    }

    @Test
    fun tryLockTakesOnlyAFreeLock() {
        val lock = FairLock()
        assertTrue(lock.tryLock())
        assertFalse(lock.tryLock())
        lock.unlock()
        assertTrue(lock.tryLock())
        lock.unlock()
    }

    @Test
    fun unlockingAFreeLockFails() {
        assertFailsWith<IllegalStateException> { FairLock().unlock() }
    }
}
