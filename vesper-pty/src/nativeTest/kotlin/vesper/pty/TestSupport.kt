package vesper.pty

import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Long enough for any wait that should succeed, even on a loaded CI machine. */
internal val GENEROUS: Duration = 10.seconds

/** A thread whose result, or failure, the test collects with [join]. */
internal class Background<T>(
    name: String = "vesper-pty-test",
    body: () -> T,
) {
    private val monitor = Monitor(1)
    private var result: Result<T>? = null

    init {
        startThread(name) {
            val outcome = runCatching(body)
            monitor.locked {
                result = outcome
                monitor.broadcast(0)
            }
        }
    }

    val isDone: Boolean get() = monitor.locked { result != null }

    fun join(timeout: Duration = GENEROUS): T {
        val end = now() + timeout
        val outcome =
            monitor.locked {
                while (result == null) {
                    val left = end - now()
                    if (!left.isPositive()) break
                    monitor.await(0, left)
                }
                result
            } ?: fail("the thread did not finish within $timeout")
        return outcome.getOrThrow()
    }
}

internal fun sleep(duration: Duration) {
    val monitor = Monitor(1)
    val end = now() + duration
    monitor.locked {
        while (true) {
            val left = end - now()
            if (!left.isPositive()) break
            monitor.await(0, left)
        }
    }
}

/**
 * A sink that keeps everything it is fed and the exit, and answers output once with [reply]: given
 * all output so far, the reply to queue, if any.
 */
internal class Recorder(
    private val reply: (String) -> String? = { null },
) : Sink {
    private val monitor = Monitor(1)
    private val chunks = ArrayList<ByteArray>()
    private var pendingReply: ByteArray? = null
    private var replied = false
    private var exit: ExitStatus? = null

    // The monitor is not reentrant: these are read through the getters below, never under the lock.
    private var fedLate = false

    val fedAfterExit: Boolean get() = monitor.locked { fedLate }

    override fun feed(bytes: ByteArray) {
        monitor.locked {
            if (exit != null) fedLate = true
            chunks += bytes
            if (!replied) {
                reply(textLocked())?.let {
                    pendingReply = it.encodeToByteArray()
                    replied = true
                }
            }
            monitor.broadcast(0)
        }
    }

    override fun drainReplies(limit: Int): ByteArray? =
        monitor.locked {
            val out = pendingReply ?: return null
            check(out.size <= limit) { "a test reply larger than the room" }
            pendingReply = null
            out
        }

    override fun childExited(status: ExitStatus) {
        monitor.locked {
            exit = status
            monitor.broadcast(0)
        }
    }

    private fun textLocked(): String {
        val all = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(all, at)
            at += chunk.size
        }
        return all.decodeToString()
    }

    fun text(): String = monitor.locked { textLocked() }

    val exitStatus: ExitStatus? get() = monitor.locked { exit }

    /** Waits until [done] holds for the output and exit so far, or [timeout] passes. */
    fun awaitUntil(
        timeout: Duration = GENEROUS,
        done: (String, ExitStatus?) -> Boolean,
    ): Boolean {
        val end = now() + timeout
        return monitor.locked {
            while (!done(textLocked(), exit)) {
                val left = end - now()
                if (!left.isPositive()) return false
                monitor.await(0, left)
            }
            true
        }
    }

    fun awaitExit(timeout: Duration = GENEROUS): ExitStatus =
        if (awaitUntil(timeout) { _, status -> status != null }) exitStatus!! else fail("no exit within $timeout; output: ${text()}")
}
