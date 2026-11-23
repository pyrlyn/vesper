package scull.pty

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * The lock the I/O thread feeds the [Sink] under and the UI reads frames under. First come, first
 * served: a system mutex lets the thread that just released it take it again before a waiting
 * frame read wakes, so a flooding child could starve the UI; here the waiter that asked first is
 * always next.
 */
public class FairLock {
    private val monitor = Monitor(1)

    /** The ticket handed to the next caller of [lock]. */
    private var next = 0L

    /** The ticket that holds the lock, or would if it were taken. */
    private var serving = 0L

    /** Blocks until the lock is this caller's. */
    public fun lock() {
        monitor.locked {
            val ticket = next++
            while (serving != ticket) monitor.await(0)
        }
    }

    /** Takes the lock if it is free and nobody waits for it. */
    public fun tryLock(): Boolean =
        monitor.locked {
            if (serving != next) return false
            next++
            true
        }

    /** Hands the lock to the caller that waited longest. Only the holder may call this. */
    public fun unlock() {
        monitor.locked {
            check(serving < next) { "unlock of a lock nobody holds" }
            serving++
            monitor.broadcast(0)
        }
    }
}

/** Runs [block] with the lock held. */
@OptIn(ExperimentalContracts::class)
public inline fun <T> FairLock.withLock(block: () -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
