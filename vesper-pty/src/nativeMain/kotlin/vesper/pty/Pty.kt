package vesper.pty

import vesper.sync.Monitor
import vesper.sync.ThreadStartException
import vesper.sync.startThread

/**
 * One child on one pseudo-terminal (ConPTY on Windows), with the four threads that serve it:
 *
 * - `vesper-pty-read` reads the child's output into a bounded queue;
 * - `vesper-pty-io` takes the [FairLock], feeds a bounded batch to the [Sink], collects its
 *   replies, releases the lock and fires the wakeup;
 * - `vesper-pty-write` writes replies and the user's input to the child;
 * - `vesper-pty-wait` waits for the child and reports its status.
 *
 * Four, because every call on the PTY blocks and none can be interrupted. The queues between them
 * are the back-pressure: a busy lock or a stalled sink fills the read queue, the reader blocks, the
 * kernel buffer fills and the child blocks in `write`. Nothing the child sends grows memory past
 * the caps in [PtyLimits].
 *
 * [close] shuts everything down: the child gets a hangup, the threads end, and no wakeup or sink
 * call happens afterwards. A shutdown is not reported through [Sink.childExited].
 */
public class Pty private constructor(
    private val shared: Shared,
    private val child: Child,
) : AutoCloseable {
    private var started = 0
    private val closing = Monitor(1)
    private var closed = false

    /** What ended a worker thread early, a throwing sink for one; the terminal has stopped then. */
    public val failure: Throwable? get() = shared.failure

    private fun start(
        worker: Worker,
        name: String,
        body: () -> Unit,
    ) {
        try {
            startThread(name) { shared.runWorker(worker, body) }
        } catch (e: ThreadStartException) {
            throw PtyException.Thread(e.name, e.code)
        }
        started = started or worker.bit
    }

    /**
     * Queues input for the child (keys, paste). Never blocks: returns how many leading bytes were
     * accepted, fewer than offered when the write queue is full because the child is not reading;
     * offer the rest later.
     *
     * @throws PtyException.Closed once the terminal has shut down or the child no longer accepts input.
     */
    public fun writeInput(bytes: ByteArray): Int = shared.pushInput(bytes)

    /**
     * Queues input only if all of it fits, for events that must not be cut (a key or mouse report).
     * Never blocks: false, queueing nothing, when the write queue lacks room.
     *
     * @throws PtyException.Closed as for [writeInput].
     */
    public fun writeInputWhole(bytes: ByteArray): Boolean = shared.pushInputWhole(bytes)

    /**
     * Tells the child the window changed size (`SIGWINCH` on Unix, `ResizePseudoConsole` on Windows).
     *
     * @throws PtyException.Closed after shutdown.
     * @throws PtyException.Resize when the system refuses.
     */
    public fun resize(size: PtySize) {
        closing.locked { if (closed) throw PtyException.Closed() }
        child.resize(size)
    }

    /**
     * Holds the child's output back, as during an interactive resize: the UI keeps drawing the last
     * frame, the reader fills its capped queue and then blocks, and the child blocks in `write`.
     * Resize once at the final size, then [resumeOutput].
     */
    public fun pauseOutput(): Unit = shared.setPaused(true)

    /** Lets output flow again after [pauseOutput]. */
    public fun resumeOutput(): Unit = shared.setPaused(false)

    /** Asks the child to end (`SIGHUP`, or termination on Windows). The exit still arrives through the sink. False when it already ended. */
    public fun kill(): Boolean = child.hangUp()

    /** Shuts the terminal down. Safe to call more than once. */
    override fun close() {
        if (closing.locked { closed.also { closed = true } }) return
        shared.stop()
        // Already ended is the common case and not worth reporting.
        child.hangUp()
        child.closeMaster()
        // A thread still in a blocking call (a child that ignores the hangup and keeps the PTY open)
        // is left behind rather than waited for: it ends by itself when the PTY does, holds nothing
        // of the caller's, and the stop flag keeps it from calling the sink or the wakeup.
        shared.waitSettled(started, now() + PtyLimits.SHUTDOWN_GRACE)
    }

    public companion object {
        /**
         * Starts the child and the threads that feed [sink]. [lock] is the lock the caller's frame
         * reads also take; [wakeup] is called from the I/O thread after each hold the sink wants
         * shown and after the exit, with no lock held, and must not block.
         *
         * @throws PtyException when the PTY cannot be opened, the child cannot be started or a
         *   thread cannot be created; nothing is left running then.
         */
        public fun spawn(
            options: SpawnOptions,
            lock: FairLock,
            sink: Sink,
            wakeup: () -> Unit,
        ): Pty {
            options.validate()
            val child = spawnChild(options)
            val shared = Shared()
            val pty = Pty(shared, child)
            try {
                val reader = child.openReader()
                val writer =
                    try {
                        child.openWriter()
                    } catch (failure: Throwable) {
                        reader.close()
                        throw failure
                    }
                pty.start(Worker.READER, "vesper-pty-read") {
                    try {
                        readLoop(shared, reader)
                    } finally {
                        reader.close()
                    }
                }
                pty.start(Worker.WRITER, "vesper-pty-write") {
                    try {
                        writeLoop(shared, writer)
                    } finally {
                        writer.close()
                    }
                }
                pty.start(Worker.WAITER, "vesper-pty-wait") { waitLoop(shared, child::waitExit) }
                pty.start(Worker.LOOP, "vesper-pty-io") { runIoLoop(shared, lock, sink, wakeup) }
            } catch (failure: Throwable) {
                pty.close()
                throw failure
            }
            return pty
        }
    }
}
