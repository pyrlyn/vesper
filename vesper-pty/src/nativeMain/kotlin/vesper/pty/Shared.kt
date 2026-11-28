package vesper.pty

import vesper.pty.PtyLimits.EXIT_DRAIN_MAX
import vesper.pty.PtyLimits.EXIT_IDLE
import vesper.pty.PtyLimits.MAX_LOCKED_BYTES
import vesper.pty.PtyLimits.MAX_PENDING_READ_BYTES
import vesper.pty.PtyLimits.MAX_PENDING_WRITE_BYTES
import vesper.pty.PtyLimits.WRITE_CHUNK_BYTES
import vesper.sync.Monitor
import kotlin.time.TimeSource

internal typealias Instant = TimeSource.Monotonic.ValueTimeMark

internal fun now(): Instant = TimeSource.Monotonic.markNow()

/** The threads of one terminal, as bits of what [Shared.waitSettled] returns. */
internal enum class Worker(
    val bit: Int,
) {
    READER(1),
    LOOP(2),
    WRITER(4),
    WAITER(8),
}

/** What the I/O loop has to do next. */
internal sealed interface Work {
    /** Output is queued. */
    data object Data : Work

    /** The terminal is shutting down. */
    data object Stop : Work

    /** The sink's deadline passed with no output to feed. */
    data object Tick : Work

    /** The child ended and its output is drained: report it and finish. */
    data class Exited(
        val status: ExitStatus,
    ) : Work
}

// The conditions of the one monitor.
private const val LOOP_WAKE = 0
private const val READ_SPACE = 1
private const val WRITE_WAKE = 2
private const val SETTLED = 3
private const val CONDITIONS = 4

/**
 * The state the four threads of a terminal share: the read queue, the write queue, the child's
 * exit and the stop flag. Every cap lives, and is tested, here. Its lock is a leaf: nothing is
 * called out while it is held, so it may be taken under the sink lock and never the other way.
 */
internal class Shared {
    private val monitor = Monitor(CONDITIONS)
    private val chunks = ArrayDeque<ByteArray>()
    private var queued = 0

    /** The reader hit EOF or a read error: no more output will come. */
    private var readerDone = false
    private var exit: ExitStatus? = null
    private var exitAt: Instant? = null

    /** Instant of the last queued chunk; null until the first one. */
    private var lastData: Instant? = null
    private val outbox = ByteRing(MAX_PENDING_WRITE_BYTES)
    private var writerFailed = false
    private var stopped = false

    /** Output stays queued, as during an interactive resize: the reader fills the queue, then the child blocks. */
    private var paused = false

    /** Bits of [Worker] whose thread has returned. */
    private var finished = 0

    private var firstFailure: Throwable? = null

    /** What ended a worker thread early, for whoever asks after the terminal stopped. */
    val failure: Throwable? get() = monitor.locked { firstFailure }

    // Reader side.

    /** Queues one read. Blocks while the queue is full (back-pressure); false when the terminal stopped meanwhile. */
    fun pushChunk(chunk: ByteArray): Boolean =
        monitor.locked {
            // Strict: the queue never holds more than the cap, so the total in memory is the cap
            // plus the one read the reader holds while it waits.
            while (!stopped && queued + chunk.size > MAX_PENDING_READ_BYTES) monitor.await(READ_SPACE)
            if (stopped) return false
            queued += chunk.size
            chunks.addLast(chunk)
            lastData = now()
            monitor.signal(LOOP_WAKE)
            true
        }

    /** The reader reached EOF or failed: no more output. */
    fun readerDone() =
        monitor.locked {
            readerDone = true
            monitor.signal(LOOP_WAKE)
        }

    // Waiter side.

    fun setExit(status: ExitStatus) =
        monitor.locked {
            exit = status
            exitAt = now()
            monitor.signal(LOOP_WAKE)
        }

    // I/O loop side.

    /**
     * Blocks until there is something for the loop to do, or [deadline] (the sink's) passes.
     * Output always comes before the exit, and the exit is held back until the output is drained:
     * EOF, or quiet for [EXIT_IDLE] after the last chunk, or [EXIT_DRAIN_MAX] after the exit.
     * If no chunk has arrived yet, only [EXIT_DRAIN_MAX] applies: ConPTY never sends EOF, and the
     * first bytes can land after the process handle already says the child has ended. While paused,
     * output and the exit behind it wait.
     */
    fun waitForWork(deadline: Instant?): Work {
        monitor.lock()
        try {
            while (true) {
                if (stopped) return Work.Stop
                if (queued > 0 && !paused) return Work.Data
                val now = now()
                if (deadline != null && now >= deadline) return Work.Tick
                var exitBy: Instant? = null
                val at = exitAt
                if (at != null && queued == 0) {
                    val last = lastData
                    val by =
                        if (last == null) {
                            at + EXIT_DRAIN_MAX
                        } else {
                            minOf(maxOf(last, at) + EXIT_IDLE, at + EXIT_DRAIN_MAX)
                        }
                    val status = exit
                    if ((readerDone || now >= by) && status != null) return Work.Exited(status)
                    exitBy = by
                }
                val wakeAt = listOfNotNull(exitBy, deadline).minOrNull()
                monitor.await(LOOP_WAKE, wakeAt?.let { it - now() })
            }
        } finally {
            monitor.unlock()
        }
    }

    /** Holds output back (true) or lets it flow again. */
    fun setPaused(paused: Boolean) =
        monitor.locked {
            this.paused = paused
            monitor.signal(LOOP_WAKE)
        }

    /**
     * Takes queued output for one lock hold: whole chunks, at most [MAX_LOCKED_BYTES] in all.
     * Every chunk is at most that size, so the first always fits and none is ever split.
     */
    fun takeBatch(): List<ByteArray> =
        monitor.locked {
            val batch = ArrayList<ByteArray>()
            var taken = 0
            while (chunks.isNotEmpty() && taken + chunks.first().size <= MAX_LOCKED_BYTES) {
                val chunk = chunks.removeFirst()
                taken += chunk.size
                batch += chunk
            }
            queued -= taken
            monitor.signal(READ_SPACE)
            batch
        }

    /**
     * Moves the sink's replies into the write queue, clamped to its room. The clamp is ours, not the
     * sink's: whatever the sink returns, the cap holds. The sink is asked outside this lock.
     */
    fun queueReplies(sink: Sink) {
        val room = monitor.locked { if (writerFailed || stopped) 0 else outbox.room }
        if (room == 0) return
        val replies = sink.drainReplies(room) ?: return
        if (replies.isEmpty()) return
        monitor.locked {
            if (writerFailed || stopped) return
            // Input may have taken some of the room since it was measured.
            val take = minOf(replies.size, room, outbox.room)
            outbox.push(replies, 0, take)
            if (take > 0) monitor.signal(WRITE_WAKE)
        }
    }

    // Writer side.

    /** Blocks until bytes are queued; a copy of the next chunk, which stays counted against the cap until [outputWritten]. */
    fun nextOutput(): ByteArray? {
        monitor.lock()
        try {
            while (true) {
                if (stopped) return null
                if (outbox.size > 0) return outbox.peek(WRITE_CHUNK_BYTES)
                monitor.await(WRITE_WAKE)
            }
        } finally {
            monitor.unlock()
        }
    }

    fun outputWritten(count: Int) = monitor.locked { outbox.drop(count) }

    /** The child no longer reads: drop what is queued and refuse more. */
    fun writerFailed() =
        monitor.locked {
            writerFailed = true
            outbox.clear()
        }

    // Caller side.

    /** Queues input for the child; how many leading bytes were taken, fewer when the queue is full. */
    fun pushInput(bytes: ByteArray): Int = push(bytes, whole = false)

    /**
     * Queues input only if all of it fits: a key or mouse report cut in two would reach the child
     * as a broken escape sequence. False, queueing nothing, when it does not fit.
     */
    fun pushInputWhole(bytes: ByteArray): Boolean = push(bytes, whole = true) == bytes.size

    private fun push(
        bytes: ByteArray,
        whole: Boolean,
    ): Int =
        monitor.locked {
            if (stopped || writerFailed) throw PtyException.Closed()
            val room = outbox.room
            if (whole && bytes.size > room) return 0
            val take = minOf(bytes.size, room)
            outbox.push(bytes, 0, take)
            if (take > 0) monitor.signal(WRITE_WAKE)
            take
        }

    /** Stops everything: every waiter wakes, notices and returns. */
    fun stop() =
        monitor.locked {
            stopped = true
            monitor.broadcast(LOOP_WAKE)
            monitor.broadcast(READ_SPACE)
            monitor.broadcast(WRITE_WAKE)
        }

    val isStopped: Boolean get() = monitor.locked { stopped }

    /** Records what ended a worker early; the first cause wins. */
    fun fail(cause: Throwable) = monitor.locked { if (firstFailure == null) firstFailure = cause }

    fun markFinished(worker: Worker) =
        monitor.locked {
            finished = finished or worker.bit
            monitor.broadcast(SETTLED)
        }

    /** Waits until every thread in [expected] (bits of [Worker]) has returned or [deadline] passes; the bits of those that did. */
    fun waitSettled(
        expected: Int,
        deadline: Instant,
    ): Int =
        monitor.locked {
            while (finished and expected != expected) {
                val left = deadline - now()
                if (!left.isPositive()) break
                monitor.await(SETTLED, left)
            }
            finished
        }

    internal val queuedBytes: Int get() = monitor.locked { queued }

    internal val outboxBytes: Int get() = monitor.locked { outbox.size }
}

/**
 * Runs one worker thread's [body] and then marks the thread finished, also when [body] throws. The
 * loop ending for any reason, a throwing sink included, stops the terminal, so the reader cannot
 * block forever on a queue nobody empties.
 */
internal fun Shared.runWorker(
    worker: Worker,
    body: () -> Unit,
) {
    try {
        body()
    } catch (cause: Throwable) {
        fail(cause)
    } finally {
        if (worker == Worker.LOOP) stop()
        markFinished(worker)
    }
}
