package scull.ffi

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import scull.ffi.c.tt_wakeup_fn
import scull.pty.ExitStatus
import scull.pty.FairLock
import scull.pty.Pty
import scull.pty.Sink
import scull.term.Terminal
import kotlin.concurrent.atomics.AtomicInt
import kotlin.time.TimeSource

/** Replies held for the child past one drain; more are dropped, as the terminal drops past its own cap. */
private const val MAX_HELD_REPLIES = 64 * 1024

/**
 * What a `tt_term*` points to: the terminal, the lock its readers share, and the PTY when it has a
 * child. Everything the terminal holds is touched only under [lock]; the flags are atomics because
 * the I/O thread and the host's threads both read them.
 */
internal class TermHandle(
    val terminal: Terminal,
    private val wakeup: tt_wakeup_fn?,
    private val userdata: COpaquePointer?,
) {
    val lock = FairLock()
    var pty: Pty? = null

    private val poisoned = AtomicInt(0)

    // Set when the host has been woken and has not looked yet: one wakeup per look, however much
    // output arrives meanwhile.
    private val armed = AtomicInt(0)

    /** How the child ended, once it has; under [lock]. */
    var exit: ExitStatus? = null
    var exitReported = false

    /** The serial of the last polled event and its text; under [lock]. */
    var serial = 0uL
    var heldSerial = 0uL
    var heldText: ByteArray? = null

    val isPoisoned: Boolean get() = poisoned.load() != 0

    fun poison() = poisoned.store(1)

    /** The host looked: the next news wakes it again. */
    fun rearm() = armed.store(0)

    fun wake() {
        val fn = wakeup ?: return
        if (armed.compareAndSet(0, 1)) fn.invoke(userdata)
    }

    /** What the PTY's I/O thread drives, with [lock] held. A throw here would stop the PTY's threads, so it poisons instead. */
    val sink: Sink =
        object : Sink {
            private var replies = ByteArray(0)

            override fun feed(bytes: ByteArray) {
                if (isPoisoned) return
                try {
                    terminal.feed(bytes)
                } catch (_: Throwable) {
                    poison()
                }
            }

            override fun drainReplies(limit: Int): ByteArray? {
                if (isPoisoned) return null
                val fresh = terminal.takeReplies()
                val all = if (replies.isEmpty()) fresh else replies + fresh
                if (all.size <= limit) {
                    replies = ByteArray(0)
                    return all
                }
                replies = all.copyOfRange(limit, minOf(all.size, limit + MAX_HELD_REPLIES))
                return all.copyOf(limit)
            }

            override fun childExited(status: ExitStatus) {
                exit = status
            }

            override fun wantsWakeup(now: TimeSource.Monotonic.ValueTimeMark): Boolean =
                isPoisoned ||
                    try {
                        !terminal.syncHeld(now)
                    } catch (_: Throwable) {
                        poison()
                        true
                    }

            override fun deadline(): TimeSource.Monotonic.ValueTimeMark? = if (isPoisoned) null else terminal.syncDeadline
        }
}

/** The handle behind a `tt_term*`, or null for `NULL`. */
internal fun CPointer<*>?.term(): TermHandle? = this?.asStableRef<TermHandle>()?.get()

internal fun TermHandle.toPointer(): COpaquePointer = StableRef.create(this).asCPointer()
