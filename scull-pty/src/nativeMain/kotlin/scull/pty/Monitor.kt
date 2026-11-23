package scull.pty

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import scull.pty.sys.scull_monitor_broadcast
import scull.pty.sys.scull_monitor_free
import scull.pty.sys.scull_monitor_lock
import scull.pty.sys.scull_monitor_new
import scull.pty.sys.scull_monitor_signal
import scull.pty.sys.scull_monitor_unlock
import scull.pty.sys.scull_monitor_wait
import scull.pty.sys.scull_thread_start
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner
import kotlin.time.Duration

/**
 * A system mutex with [conditions] condition variables, numbered from 0. Waits sit in native code,
 * so a thread waiting here never holds up the collector.
 */
internal class Monitor(
    conditions: Int,
) {
    private val handle: COpaquePointer =
        checkNotNull(scull_monitor_new(conditions)) { "the system could not create a lock" }

    @OptIn(ExperimentalNativeApi::class)
    @Suppress("unused") // Frees the native lock once the monitor is unreachable.
    private val cleaner: Cleaner = createCleaner(handle) { scull_monitor_free(it) }

    fun lock() = scull_monitor_lock(handle)

    fun unlock() = scull_monitor_unlock(handle)

    inline fun <T> locked(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    /**
     * Waits on [condition] with the lock held, for at most [timeout] (null: no limit). Returns true
     * when the time ran out. Wakeups may be spurious; callers recheck their condition.
     */
    fun await(
        condition: Int,
        timeout: Duration? = null,
    ): Boolean {
        val nanos = timeout?.inWholeNanoseconds?.coerceAtLeast(0) ?: -1L
        return scull_monitor_wait(handle, condition, nanos) != 0
    }

    fun signal(condition: Int) = scull_monitor_signal(handle, condition)

    fun broadcast(condition: Int) = scull_monitor_broadcast(handle, condition)
}

private val threadEntry =
    staticCFunction { arg: COpaquePointer? ->
        val ref = checkNotNull(arg).asStableRef<() -> Unit>()
        val body = ref.get()
        ref.dispose()
        // An exception must not cross back into C, where it would end the process.
        runCatching(body)
        Unit
    }

/**
 * Starts a detached system thread running [body]. Nothing joins it: whoever needs to know that it
 * finished has [body] say so.
 */
internal fun startThread(
    name: String,
    body: () -> Unit,
) {
    val ref = StableRef.create(body)
    val error = scull_thread_start(threadEntry, ref.asCPointer(), name)
    if (error != 0) {
        ref.dispose()
        throw PtyException.Thread(name, error)
    }
}
