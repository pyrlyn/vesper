package vesper.windows

import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.posix.getenv
import platform.windows.HWND
import platform.windows.HWND__
import platform.windows.MB_OK
import platform.windows.MessageBeep
import platform.windows.PostMessageW
import vesper.host.CellSize
import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.PaneViewport
import vesper.host.RowSources
import vesper.host.cellAt
import vesper.host.clipboardText
import vesper.host.gridSize
import vesper.host.launchEnvironment
import vesper.host.windowTitle
import vesper.input.KeyEvent
import vesper.pty.PtySize
import vesper.pty.SpawnOptions
import vesper.session.Session
import vesper.term.Event
import vesper.term.Frame

/** What a pane needs from the window it is a part of. */
interface PaneWindow {
    val hwnd: HWND

    /** Pixels per DIP on the window's monitor. */
    val scale: Double

    /** The cell in DIPs, the same for every pane of the window. */
    val cell: CellSize

    /** Runs the window's binding for [event], if it has one. */
    fun shortcut(event: KeyEvent): Boolean

    fun invalidate()

    /** [pane]'s program named the window, or cleared its name. */
    fun titleChanged(pane: TerminalPane)

    /** [pane]'s program exited or its terminal failed; the pane is to go. */
    fun exited(pane: TerminalPane)
}

/**
 * One pane of a window: a session, the frame it last showed, and the mouse input over it. The window
 * owns the pixels and the keyboard: it gives the pane its [viewport] and draws it with [quads].
 */
class TerminalPane(
    val id: Int,
    private val window: PaneWindow,
    val quads: D3DRenderer.PaneQuads,
) : InputTarget,
    AutoCloseable {
    val frame = Frame()
    val sources = RowSources()
    private var grid = Cells(1, 1)

    // The cell's size in physical pixels last told to the session, which a DPI change alters on its own.
    private var cellPixels = 0 to 0
    private val computerName = getenv("COMPUTERNAME")?.toKString().orEmpty()

    /** The pane's part of the client area in physical pixels; the window lays it out. */
    var viewport = PaneViewport(id, 0, 0, 0, 0)

    /** Whether the keys come here: the pane is the focused one of a focused window. */
    var focused = false
        private set

    /** The program's name for the window, or null when it has given none. */
    var title: String? = null
        private set

    /** The shell's working directory, from the last OSC 7 that named one on this computer. */
    var directory: String? = null
        private set

    /** The mouse over this pane; the window's keyboard goes through the window's own input. */
    internal val input = WindowInput(window.hwnd, this)

    override var session: Session? = null
        private set

    /** The grid and the cell's pixels the session was last told; for tests. */
    val sessionSize: Pair<Cells, Pair<Int, Int>> get() = grid to cellPixels

    /**
     * Starts [program] with [args] (the user's shell, `COMSPEC`, when null) in the user's profile
     * directory at the pane's size, keeping [scrollback] rows of history.
     */
    fun start(
        program: String?,
        args: List<String>,
        scrollback: Int,
    ) {
        grid = currentGrid()
        // The window's handle as a number: the wakeup runs on the PTY's thread and only posts to it.
        val hwnd = window.hwnd.toLong()
        // Programs under WSL or ssh read TERM and the locale; native ones ignore them.
        val environment = launchEnvironment { name -> getenv(name)?.toKString() }
        val options = SpawnOptions(program, args, getenv("USERPROFILE")?.toKString(), environment, PtySize(grid.rows, grid.cols))
        val started = Session.spawn(options, scrollback) { PostMessageW(hwnd.toCPointer<HWND__>(), WM_WAKE.convert(), 0u, 0) }
        session = started
        cellPixels = currentCellPixels()
        started.resize(grid.cols, grid.rows, cellPixels.first, cellPixels.second)
    }

    /** What the screen shows, one line per row; for tests. */
    fun screenText(): String = (0 until frame.rows).joinToString("\n") { frame.row(it)?.text.orEmpty() }

    // Client-area pixels, which the window hands on as they came; the pane's text starts at its corner.
    override fun place(
        x: Int,
        y: Int,
    ): Place {
        val scale = window.scale
        val px = x - viewport.x
        val py = y - viewport.y
        val at = cellAt(px / scale, py / scale, window.cell, grid)
        // The text area's pixels start inside the padding; a pointer captured outside the pane reports negatives.
        val inset = PADDING * scale
        return Place(at.cols, at.rows, (px - inset).toInt().coerceAtLeast(0), (py - inset).toInt().coerceAtLeast(0))
    }

    override val pageRows: Int get() = grid.rows

    override fun scrolled() = refresh()

    override fun focusChanged(focused: Boolean) {
        this.focused = focused
        window.invalidate()
    }

    /** Tells the program and the cursor that the keys came or went. */
    fun focus(focused: Boolean) {
        session?.focus(focused)
        focusChanged(focused)
    }

    override fun shortcut(event: KeyEvent): Boolean = window.shortcut(event)

    /** Reads the new frame and the events since the last look; the window hears when the program is gone. */
    fun refresh() {
        val current = session ?: return
        if (current.update(frame)) {
            sources.absorb(frame.damage, frame.rows)
            window.invalidate()
        }
        while (true) {
            when (val event = current.pollEvent() ?: break) {
                Event.Bell -> {
                    MessageBeep(MB_OK.convert())
                }

                is Event.Title -> {
                    event.windowTitle?.let {
                        title = it.ifEmpty { null }
                        window.titleChanged(this)
                    }
                }

                is Event.Directory -> {
                    windowsPath(event.uri, computerName)?.let { directory = it }
                }

                is Event.ClipboardWrite -> {
                    event.clipboardText?.let { writeClipboardText(window.hwnd, it) }
                }

                else -> {}
            }
        }
        if (current.exitStatus != null || current.failure != null) {
            current.close()
            session = null
            window.exited(this)
        }
    }

    private fun currentGrid(): Cells {
        val scale = window.scale
        return gridSize(viewport.width / scale, viewport.height / scale, window.cell)
    }

    private fun currentCellPixels(): Pair<Int, Int> {
        val scale = window.scale
        return (window.cell.width * scale).toInt() to (window.cell.height * scale).toInt()
    }

    /** Tells the session the pane's grid and cell pixels when either changed. */
    fun fit() {
        val current = session ?: return
        val next = currentGrid()
        val pixels = currentCellPixels()
        if (next == grid && pixels == cellPixels) return
        grid = next
        cellPixels = pixels
        current.resize(grid.cols, grid.rows, pixels.first, pixels.second)
        window.invalidate()
        refresh()
    }

    override fun close() {
        session?.close()
        session = null
        quads.close()
    }
}
