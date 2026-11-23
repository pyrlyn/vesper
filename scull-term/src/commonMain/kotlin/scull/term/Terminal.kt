package scull.term

import scull.grid.Grid
import scull.grid.LinkId
import scull.grid.Style
import scull.input.ByteSink
import scull.input.KeyEvent
import scull.input.KeyModes
import scull.input.encodeKey
import scull.parser.Parser
import scull.unicode.WidthOptions
import kotlin.time.TimeSource

/** The most rows a screen has: the largest row a control sequence can address. */
public const val MAX_ROWS: Int = 0xFFFF

/**
 * A headless terminal: bytes from the program in, a grid of cells, a cursor and replies out.
 *
 * Not thread-safe: one thread feeds and reads at a time; the caller that shares it holds the lock.
 * Everything the bytes can grow is capped: the grid by [scrollback], replies and events by their
 * queues.
 *
 * @param scrollback rows of history the main screen keeps; the alternate screen keeps none.
 * @throws IllegalArgumentException when [cols] or [rows] is zero or past what a sequence can address.
 */
public class Terminal(
    cols: Int,
    rows: Int,
    scrollback: Int,
    width: WidthOptions = WidthOptions.DEFAULT,
) {
    private val parser = Parser()
    private val state: State
    private val sync = SyncGate()

    init {
        require(rows in 1..MAX_ROWS) { "rows $rows not in 1..$MAX_ROWS" }
        state = State(Grid(cols, rows, scrollback), Grid(cols, rows, 0), width)
    }

    /** Applies the program's output [bytes] from [from] until [to]. A sequence may be split across calls. */
    public fun feed(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ) {
        parser.feed(bytes, from, to, state)
        if (sync.fed(state.modes.synchronizedOutput, to - from)) endSync()
    }

    /**
     * Whether synchronized output (mode 2026) holds the picture at [now]. The hold ends, and the mode
     * reads as reset, once it has lasted [MAX_SYNC_HOLD] from the first call that saw it or spanned
     * [MAX_SYNC_BYTES] fed bytes. Call it after every [feed] and wake the UI only when it is false.
     */
    public fun syncHeld(now: TimeSource.Monotonic.ValueTimeMark): Boolean {
        if (sync.holds(state.modes.synchronizedOutput, now)) return true
        endSync()
        return false
    }

    /** When a hold that [syncHeld] has seen ends by itself: how long the I/O thread may sleep while the picture is held. */
    public val syncDeadline: TimeSource.Monotonic.ValueTimeMark? get() = sync.deadline

    private fun endSync() {
        state.modes.synchronizedOutput = false
        sync.reset()
    }

    /**
     * Resizes both screens to [cols] by [rows]. The primary screen and its scrollback are rewrapped in
     * one pass and the alternate screen is cut or padded; the cursor and both saved cursors follow
     * their cells. The margins reset, the tab stops set so far are kept and new columns get the
     * power-on ones, and a pending wrap survives only a change of height. The same size is a no-op.
     *
     * Contract for the C ABI (T10): during an interactive resize the host pauses PTY reads
     * (Pty.pauseOutput) and keeps drawing the last frame, then calls this once with the final size,
     * resizes the PTY and resumes reads. A rewrap per drag step would cost a pass over all history each
     * step and, at a narrow intermediate width, could push lines past the history cap for good.
     *
     * @throws IllegalArgumentException when a size is zero or past what a sequence can address; nothing changes then.
     */
    public fun resize(
        cols: Int,
        rows: Int,
    ): Unit = state.resize(cols, rows)

    /** Moves the viewport [delta] rows back into history (negative: towards the screen), clamped to what history holds. */
    public fun scrollDisplay(delta: Int): Unit = state.grid.scrollDisplay(delta)

    /**
     * Brings [frame] up to date with the viewport: the only call that copies out of the terminal, so a
     * UI holds the terminal's lock just for it. While synchronized output holds the picture the frame
     * is left as it is with no damage and this returns false.
     */
    public fun updateFrame(
        frame: Frame,
        now: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    ): Boolean {
        if (syncHeld(now)) {
            frame.damage.clear()
            return false
        }
        frame.copyFrom(this)
        return true
    }

    /** The screen shown: the alternate one while [isAltScreen]. Valid until the next [feed]. */
    public val grid: Grid get() = state.grid

    public val cursor: Cursor get() = Cursor(state.row, state.col, state.pendingWrap)

    /** The SGR style the next printed character gets. */
    public val pen: Style get() = state.pen.style

    public val modes: Modes get() = state.modes

    public val margins: Margins get() = Margins(state.top, state.bottom, state.left, state.right)

    public val isAltScreen: Boolean get() = state.altActive

    /** Whether BEL arrived since the last call or the last polled [Event.Bell]. */
    public fun takeBell(): Boolean = state.events.takeBell()

    /** The oldest event not yet read, or null. */
    public fun pollEvent(): Event? = state.events.poll()

    /** The bytes to write back to the program (device reports), emptying the queue. */
    public fun takeReplies(): ByteArray = state.replies.take()

    /**
     * Answers an [Event.ClipboardRead] with [data] for [targets], queued with the replies.
     * Returns false when the replies not yet taken leave no room for it; take them and answer again.
     *
     * @throws IllegalArgumentException when [targets] names no OSC 52 selection or [data] passes [MAX_CLIPBOARD_BYTES].
     */
    public fun answerClipboard(
        targets: String,
        data: ByteArray,
    ): Boolean = state.replies.pushAnswer(clipboardReply(targets, data))

    /**
     * Refuses an [Event.ClipboardRead]: the program gets an empty selection, as xterm answers when
     * reads are disallowed, so it does not wait for an answer that never comes.
     */
    public fun denyClipboard(targets: String): Boolean = answerClipboard(targets, ByteArray(0))

    /**
     * The bytes that answer an [Event.ClipboardRead] with [data] for [targets], for a host that
     * writes them to the child itself: queued replies leave only when the child writes again, and a
     * program waiting for this answer writes nothing. Empty [data] is a denial.
     *
     * @throws IllegalArgumentException as [answerClipboard] does.
     */
    public fun clipboardReply(
        targets: String,
        data: ByteArray,
    ): ByteArray = state.clipboardReply(targets, data)

    /** The URI of the link [id] cells carry, or null once a sweep freed it because no cell held it. */
    public fun linkUri(id: LinkId): String? = state.links.uri(id.value)

    /** The modes key encoding reads now, with the kitty flags of the screen shown. */
    public fun keyModes(): KeyModes = state.modes.keyModes(state.altActive)

    /** Appends what the program gets for [event]: nothing for a release in legacy mode or a key being composed. */
    public fun encodeKey(
        event: KeyEvent,
        out: ByteSink,
    ): Unit = encodeKey(event, keyModes(), out)

    /**
     * Appends text an input method committed, without control characters: text is not a key, so it
     * must not type an escape sequence or a line break the user never pressed.
     */
    public fun encodeText(
        text: String,
        out: ByteSink,
    ): Unit = scull.input.encodeText(text, out)
}
