package vesper.term

import vesper.grid.Grid
import vesper.grid.LinkId
import vesper.grid.Style
import vesper.image.ImageStore
import vesper.input.ByteSink
import vesper.input.Key
import vesper.input.KeyEvent
import vesper.input.KeyModes
import vesper.input.MouseAction
import vesper.input.MouseButton
import vesper.input.MouseEvent
import vesper.input.MouseModes
import vesper.input.MouseTracking
import vesper.input.encodeKey
import vesper.input.encodeMouse
import vesper.input.encodePaste
import vesper.parser.Parser
import vesper.unicode.ClusterPolicy
import vesper.unicode.WidthOptions
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
    private var selected: Selection? = null
    private var search: Search? = null

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
        state.images.prune(state.grid.firstLine)
        selected?.let { if (!state.grid.holds(it, state.altActive)) selected = null }
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
     * Contract for the host: during an interactive resize the host pauses PTY reads
     * (Pty.pauseOutput) and keeps drawing the last frame, then calls this once with the final size,
     * resizes the PTY and resumes reads. A rewrap per drag step would cost a pass over all history each
     * step and, at a narrow intermediate width, could push lines past the history cap for good.
     *
     * A change of size drops the selection and forgets the current search match: rewrapping moves their cells.
     *
     * @throws IllegalArgumentException when a size is zero or past what a sequence can address; nothing changes then.
     */
    public fun resize(
        cols: Int,
        rows: Int,
    ) {
        val same = cols == state.grid.cols && rows == state.grid.screenRows
        state.resize(cols, rows)
        if (same) return
        selected = null
        search?.current = null
    }

    /**
     * Sets the size of a cell in pixels, which images are measured with: sixel and iTerm2 sizes turn into cells
     * with it, and kitty is told it. Until it is called a cell counts as 8 x 16. False, and nothing changes, when
     * a side is outside 1..[vesper.image.MAX_CELL_PIXELS].
     */
    public fun setCellSize(
        width: Int,
        height: Int,
    ): Boolean = state.setCellSize(width, height)

    /** The shown screen's images and their placements, anchored to absolute lines. Valid until the next [feed]. */
    public val images: ImageStore get() = state.images.store

    /** The shown screen's placements in the viewport into [out], lowest z first. */
    internal fun placements(out: MutableList<FramePlacement>): Unit = state.visiblePlacements(out)

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

    /** How printed text is measured, so the frame lays a preedit out as the child's text would be. */
    internal val widthOptions: WidthOptions get() = state.width

    internal val clusterPolicy: ClusterPolicy get() = state.policy

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

    /** The absolute line of the top screen row. Lines count every row the screen ever had, so a line keeps its number while output scrolls; the oldest one held is [Grid.evicted]. */
    public val screenTopLine: Long get() = state.grid.screenTopLine

    /** The cell at viewport [row], [col], clamped to the viewport, as an absolute point. */
    public fun viewportPoint(
        row: Int,
        col: Int,
    ): Point = state.grid.viewportPoint(row, col)

    /** The viewport row showing absolute [line], or -1 when it is not shown. */
    public fun viewportRow(line: Long): Int = state.grid.viewportRow(line)

    /**
     * Starts a selection of [kind] at [at], replacing any other. Until it is extended it covers the
     * cell, word or line at [at]; a host clears it on a click that did not drag. A point outside the
     * ring selects nothing. Output that rewrites its rows, a resize and a screen switch drop it.
     */
    public fun selectStart(
        kind: SelectionKind,
        at: Point,
    ) {
        selected = null
        val grid = state.grid
        if (grid.lineRow(at.line) == null) return
        val anchor = at.copy(col = at.col.coerceIn(0, grid.lastCol))
        selected = Selection(kind, anchor, state.altActive).also { grid.resolve(it, anchor) }
    }

    /** Moves the selection's free end to [to], clamped to the ring. No selection is no change. */
    public fun selectExtend(to: Point) {
        val sel = selected ?: return
        val grid = state.grid
        grid.resolve(sel, Point(to.line.coerceIn(grid.firstLine, grid.lastLine), to.col.coerceIn(0, grid.lastCol)))
    }

    /** Drops the selection. */
    public fun selectClear() {
        selected = null
    }

    /** The selection, or null. */
    public val selection: SelectionRange? get() = selected?.let { SelectionRange(it.kind, it.start, it.end) }

    /**
     * The selected text, empty without a selection: soft-wrapped rows joined, hard line ends (and
     * block rows) as `\n`, wide characters once, blank and concealed cells as spaces, trailing spaces
     * trimmed per line, cut at [MAX_SELECTION_BYTES] UTF-8 bytes.
     */
    public fun selectionText(): String = selected?.let(state.grid::selectedText).orEmpty()

    /**
     * Searches for [pattern], literally, replacing any earlier search. Returns false and clears the
     * search for an empty pattern or one longer than [MAX_SEARCH_PATTERN] UTF-8 bytes.
     */
    public fun searchSet(
        pattern: String,
        caseSensitive: Boolean,
    ): Boolean {
        search = null
        if (pattern.isEmpty() || pattern.encodeToByteArray().size > MAX_SEARCH_PATTERN) return false
        val folded = foldPattern(pattern, !caseSensitive)
        search = Search(folded, countCodePoints(folded), !caseSensitive)
        return true
    }

    /** Ends the search. */
    public fun searchClear() {
        search = null
    }

    /** The match last stepped to, which the frame marks as current. */
    public val searchCurrent: Match? get() = search?.current

    /**
     * Steps to the first match after [from] (by default after the current match, or from the top of
     * the viewport), wrapping round to the oldest line, and scrolls the viewport to show it.
     */
    public fun searchNext(from: Point? = null): Match? = step(from, forward = true)

    /**
     * Steps to the last match before [from] (by default before the current match, or from the bottom
     * of the viewport), wrapping round to the screen's bottom, and scrolls the viewport to show it.
     */
    public fun searchPrev(from: Point? = null): Match? = step(from, forward = false)

    private fun step(
        from: Point?,
        forward: Boolean,
    ): Match? {
        val s = search ?: return null
        val grid = state.grid
        val m = (if (forward) grid.searchNext(s, from) else grid.searchPrev(s, from)) ?: return null
        grid.stepTo(s, m)
        return m
    }

    /** Every match, oldest first, up to [MAX_SEARCH_MATCHES]; empty without a search. */
    public fun searchMatches(): List<Match> = search?.let(state.grid::matches).orEmpty()

    /** How many matches there are, counting up to [MAX_SEARCH_MATCHES]. */
    public fun searchCount(): Int = searchMatches().size

    /** The selection and search highlights over the viewport, for the frame. */
    internal fun marks(out: MutableList<Mark>) {
        val grid = state.grid
        selected?.let { grid.selectionMarks(it, out) }
        search?.let { grid.searchMarks(it, out) }
    }

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
    ): Unit = vesper.input.encodeText(text, out)

    /** The mouse tracking and encoding the program asked for. */
    public fun mouseModes(): MouseModes = state.modes.mouse

    /** Appends pasted text, bracketed while the program asked for it (`?2004`). */
    public fun encodePaste(
        text: String,
        out: ByteSink,
    ): Unit = encodePaste(text, state.modes.bracketedPaste, out)

    /** Appends a focus report while the program asked for them (`?1004`). */
    public fun encodeFocus(
        focused: Boolean,
        out: ByteSink,
    ) {
        if (state.modes.focusEvents) vesper.input.encodeFocus(focused, out)
    }

    /**
     * Appends what the program gets for a mouse event and says whether the program took it: always
     * while it tracks the mouse, and for a wheel step that alternate scroll (`?1007`) turns into a
     * cursor key on the alternate screen. False leaves the event to the UI, which selects or
     * scrolls the history.
     */
    public fun encodeMouse(
        event: MouseEvent,
        out: ByteSink,
    ): Boolean {
        val modes = state.modes
        if (modes.mouse.tracking != MouseTracking.Off) {
            encodeMouse(event, modes.mouse, out)
            return true
        }
        if (!(state.altActive && modes.alternateScroll) || event.action != MouseAction.Press) return false
        val key =
            when (event.button) {
                MouseButton.WheelUp -> Key.Up
                MouseButton.WheelDown -> Key.Down
                else -> return false
            }
        encodeKey(KeyEvent(key), out)
        return true
    }
}
