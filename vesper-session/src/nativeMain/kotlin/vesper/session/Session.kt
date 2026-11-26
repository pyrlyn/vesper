package vesper.session

import vesper.input.ByteSink
import vesper.input.KeyEvent
import vesper.input.MouseEvent
import vesper.pty.ExitStatus
import vesper.pty.FairLock
import vesper.pty.Pty
import vesper.pty.PtyException
import vesper.pty.PtySize
import vesper.pty.Sink
import vesper.pty.SpawnOptions
import vesper.pty.withLock
import vesper.term.Event
import vesper.term.Frame
import vesper.term.SelectionKind
import vesper.term.Terminal
import vesper.term.ViewportText
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/** Lines kept above the screen unless the host asks for another count. */
public const val DEFAULT_SCROLLBACK: Int = 10_000

/** Replies held for the child past one drain; more are dropped, as the terminal drops past its own cap. */
private const val MAX_HELD_REPLIES = 64 * 1024

/** Input held while the child does not read; a paste past it is dropped rather than grow memory. */
private const val MAX_OUTBOX = 1 shl 20

/** The PTY's pixel fields are 16-bit. */
private const val MAX_PIXELS = 0xFFFF

private val EMPTY = ByteArray(0)

/**
 * One terminal and the child it runs: what a host window or pane holds. The terminal never sees
 * the PTY and the PTY never sees a grid; this is where they meet.
 *
 * [wakeup] runs on a PTY thread with no lock held whenever there is output or an event to look
 * at, at most once until the host calls [update]; it must only post to the host's own thread.
 * Every other call comes from that one host thread.
 *
 * An exception inside the terminal stops this session only: [failure] holds it, the calls turn
 * into no-ops, and the host shows a notice instead of the grid.
 */
@OptIn(ExperimentalAtomicApi::class)
public class Session private constructor(
    private val terminal: Terminal,
    private val wakeup: () -> Unit,
) : AutoCloseable {
    private val lock = FairLock()
    private val adapter = Adapter()
    private lateinit var pty: Pty

    // Set when the host has been woken and has not looked yet: one wakeup per look, however much
    // output arrives meanwhile.
    private val armed = AtomicInt(0)

    private val scratch = ByteSink()

    // Encoded input the PTY's write queue had no room for yet, oldest first; host thread only.
    private var outbox = EMPTY

    /** How the child ended, once it has and all its output is in the terminal. */
    public val exitStatus: ExitStatus? get() = lock.withLock { adapter.exit }

    /** What stopped this session, if anything did. */
    public val failure: Throwable? get() = lock.withLock { adapter.failure } ?: pty.failure

    private fun wake() {
        if (armed.compareAndSet(0, 1)) wakeup()
    }

    /** Copies what changed into [frame]; true when it changed. Re-arms the wakeup and sends input left waiting. */
    public fun update(frame: Frame): Boolean {
        // Re-armed before the read, so output that lands after it wakes the host again.
        armed.store(0)
        flush()
        return guarded(false) { terminal.updateFrame(frame) }
    }

    /**
     * The next event, or null. A clipboard read (OSC 52 `?`) never comes out: it hands the user's
     * clipboard to whatever the shell runs, a remote host included, so it is answered with an
     * empty reply until a setting lets the user allow it. The answer keeps the program from waiting.
     */
    public fun pollEvent(): Event? {
        var denied = false
        val event =
            guarded(null) {
                var next = terminal.pollEvent()
                while (next is Event.ClipboardRead) {
                    terminal.denyClipboard(next.targets)
                    denied = true
                    next = terminal.pollEvent()
                }
                if (denied) queue(terminal.takeReplies())
                next
            }
        if (denied) flush()
        return event
    }

    /** A key press, repeat or release, encoded for the program's keyboard modes. */
    public fun key(event: KeyEvent): Unit = send { terminal.encodeKey(event, it) }

    /** Text from an input method or a dropped string, as typed. */
    public fun text(text: String): Unit = send { terminal.encodeText(text, it) }

    /** Pasted text, bracketed when the program asked for it. */
    public fun paste(text: String): Unit = send { terminal.encodePaste(text, it) }

    /** Focus gained or lost; reported only to a program that asked. */
    public fun focus(focused: Boolean): Unit = send { terminal.encodeFocus(focused, it) }

    /** A mouse event; false when the program does not take it, so the host may select or scroll. */
    public fun mouse(event: MouseEvent): Boolean {
        var taken = false
        send { taken = terminal.encodeMouse(event, it) }
        return taken
    }

    /** Scrolls the view by [delta] lines, up when positive; the next [update] shows it. */
    public fun scrollDisplay(delta: Int): Unit = guarded(Unit) { terminal.scrollDisplay(delta) }

    /** The viewport's text and the selection in it, for a screen reader. */
    public fun viewportText(): ViewportText = guarded(ViewportText.EMPTY) { terminal.viewportText() }

    /** Starts a selection of [kind] at viewport cell [row], [col], replacing any other; the next [update] shows it. */
    public fun select(
        kind: SelectionKind,
        row: Int,
        col: Int,
    ): Unit = guarded(Unit) { terminal.selectStart(kind, terminal.viewportPoint(row, col)) }

    /** Moves the selection's free end to viewport cell [row], [col]. */
    public fun selectExtend(
        row: Int,
        col: Int,
    ): Unit = guarded(Unit) { terminal.selectExtend(terminal.viewportPoint(row, col)) }

    /** Drops the selection. */
    public fun selectClear(): Unit = guarded(Unit) { terminal.selectClear() }

    /**
     * Sets the size in cells and the cell size in pixels, then tells the child. During an
     * interactive resize, call [pauseOutput] first and this once at the final size.
     */
    public fun resize(
        cols: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ) {
        guarded(Unit) {
            terminal.resize(cols, rows)
            terminal.setCellSize(cellWidth, cellHeight)
        }
        if (failure != null) return
        val size =
            PtySize(rows, cols, (cols * cellWidth).coerceIn(0, MAX_PIXELS), (rows * cellHeight).coerceIn(0, MAX_PIXELS))
        try {
            pty.resize(size)
        } catch (_: PtyException) {
            // The terminal has the new size either way; a child that missed this one redraws at the next.
        }
    }

    /** Holds the child's output back while the window is being resized; the host keeps the last frame. */
    public fun pauseOutput(): Unit = pty.pauseOutput()

    /** Lets output flow again after [pauseOutput]. */
    public fun resumeOutput(): Unit = pty.resumeOutput()

    /** Ends the child and the PTY's threads; no wakeup follows. */
    override fun close(): Unit = pty.close()

    private inline fun send(encode: (ByteSink) -> Unit) {
        scratch.clear()
        guarded(Unit) { encode(scratch) }
        if (scratch.isEmpty()) return
        queue(scratch.toByteArray())
        flush()
    }

    private fun queue(bytes: ByteArray) {
        if (bytes.isEmpty() || outbox.size + bytes.size > MAX_OUTBOX) return
        outbox = if (outbox.isEmpty()) bytes else outbox + bytes
    }

    // Partial writes keep their order: the rest of a cut report goes out before anything newer.
    private fun flush() {
        if (outbox.isEmpty()) return
        val taken =
            try {
                pty.writeInput(outbox)
            } catch (_: PtyException.Closed) {
                outbox.size
            }
        outbox = if (taken >= outbox.size) EMPTY else outbox.copyOfRange(taken, outbox.size)
    }

    private inline fun <T> guarded(
        otherwise: T,
        block: () -> T,
    ): T =
        lock.withLock {
            if (adapter.failure != null) return otherwise
            try {
                block()
            } catch (failure: Throwable) {
                adapter.failure = failure
                otherwise
            }
        }

    /** What the PTY's I/O thread drives, with [lock] held. A throw here would stop the PTY's threads, so it records the failure instead. */
    private inner class Adapter : Sink {
        private var replies = EMPTY
        var exit: ExitStatus? = null
        var failure: Throwable? = null

        override fun feed(bytes: ByteArray) {
            if (failure != null) return
            try {
                terminal.feed(bytes)
            } catch (thrown: Throwable) {
                failure = thrown
            }
        }

        override fun drainReplies(limit: Int): ByteArray? {
            if (failure != null) return null
            val fresh = terminal.takeReplies()
            val all = if (replies.isEmpty()) fresh else replies + fresh
            if (all.size <= limit) {
                replies = EMPTY
                return all
            }
            replies = all.copyOfRange(limit, minOf(all.size, limit + MAX_HELD_REPLIES))
            return all.copyOf(limit)
        }

        override fun childExited(status: ExitStatus) {
            exit = status
        }

        override fun wantsWakeup(now: TimeSource.Monotonic.ValueTimeMark): Boolean =
            failure != null ||
                try {
                    !terminal.syncHeld(now)
                } catch (thrown: Throwable) {
                    failure = thrown
                    true
                }

        override fun deadline(): TimeSource.Monotonic.ValueTimeMark? = if (failure != null) null else terminal.syncDeadline
    }

    public companion object {
        /**
         * Starts [options]' program (the user's shell when it names none) in a terminal of
         * [options]' size with [scrollback] lines of history.
         *
         * @throws PtyException when the child cannot be started.
         */
        public fun spawn(
            options: SpawnOptions,
            scrollback: Int = DEFAULT_SCROLLBACK,
            wakeup: () -> Unit,
        ): Session {
            val session = Session(Terminal(options.size.cols, options.size.rows, scrollback), wakeup)
            session.pty = Pty.spawn(options, session.lock, session.adapter, session::wake)
            return session
        }
    }
}
