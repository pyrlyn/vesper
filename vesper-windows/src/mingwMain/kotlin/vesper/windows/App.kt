package vesper.windows

import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.posix.getenv
import platform.windows.HWND__
import platform.windows.InvalidateRect
import platform.windows.MB_OK
import platform.windows.MessageBeep
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.SetWindowTextW
import platform.windows.WM_APP
import platform.windows.WM_ENTERSIZEMOVE
import platform.windows.WM_EXITSIZEMOVE
import platform.windows.WM_SIZING
import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.RowSources
import vesper.host.cellAt
import vesper.host.clipboardText
import vesper.host.gridSize
import vesper.host.windowTitle
import vesper.pty.PtySize
import vesper.pty.SpawnOptions
import vesper.session.Session
import vesper.term.Event
import vesper.term.Frame

/** Posted from the PTY's thread when the session has output or events to look at. */
private const val WM_WAKE = WM_APP

/** The window's title until the shell sets one, and again when it clears its own. */
private const val TITLE = "Vesper"

/**
 * One terminal window: the user's shell in a session, drawn with Direct3D into the window's swap
 * chain, with the window's keyboard, mouse and focus going to it.
 */
class App(
    private val gpu: Gpu,
    private val quitOnClose: Boolean = true,
) : WindowListener,
    InputTarget,
    AutoCloseable {
    private val palette = Palette()
    private val fonts = FontSystem.create()
    private val text =
        TextStyle.create(fonts) ?: TextStyle.create(fonts, "Courier New") ?: run {
            fonts.close()
            error("neither Consolas nor Courier New is installed")
        }
    private val renderer = D3DRenderer.create(gpu, text, palette)
    private val frame = Frame()
    private val sources = RowSources()
    private var grid = Cells(1, 1)

    // The cell's size in physical pixels last told to the session, which a DPI change alters on its own.
    private var cellPixels = 0 to 0
    private var focused = false
    private val liveResize = LiveResize()
    private val computerName = getenv("COMPUTERNAME")?.toKString().orEmpty()

    /** The shell's working directory, from the last OSC 7 that named one on this computer. */
    var directory: String? = null
        private set
    override var session: Session? = null
        private set

    // Declared before the window: WM_SIZE arrives inside CreateWindowExW, before there is a chain.
    private var chain: SwapChain? = null
    val window = Window(TITLE, 960, 600, this)

    // Kept from WM_DPICHANGED rather than asked of the window, which tests cannot move between monitors.
    private var dpi = window.dpi
    internal val swapChain: SwapChain get() = chain!!

    // Null while CreateWindowExW sends its first messages, before the window is assigned.
    private var input: WindowInput? = null

    init {
        val (width, height) = window.clientSize
        chain = SwapChain.create(gpu, window.hwnd, width, height)
        input = WindowInput(window.hwnd, this)
    }

    /** Pixels per DIP on the window's monitor. */
    private val scale: Double get() = dpi.toDouble() / DEFAULT_DPI

    /**
     * Starts [program] with [args] (the user's shell, `COMSPEC`, when null) in the user's profile
     * directory at the window's size.
     */
    fun start(
        program: String? = null,
        args: List<String> = emptyList(),
    ) {
        grid = currentGrid()
        // The window's handle as a number: the wakeup runs on the PTY's thread and only posts to it.
        val hwnd = window.hwnd.toLong()
        val options = SpawnOptions(program, args, getenv("USERPROFILE")?.toKString(), size = PtySize(grid.rows, grid.cols))
        val started = Session.spawn(options) { PostMessageW(hwnd.toCPointer<HWND__>(), WM_WAKE.convert(), 0u, 0) }
        session = started
        cellPixels = currentCellPixels()
        started.resize(grid.cols, grid.rows, cellPixels.first, cellPixels.second)
    }

    /** What the screen shows, one line per row; for tests. */
    internal fun screenText(): String = (0 until frame.rows).joinToString("\n") { frame.row(it)?.text.orEmpty() }

    /** The grid and the cell's pixels the session was last told; for tests. */
    internal val sessionSize: Pair<Cells, Pair<Int, Int>> get() = grid to cellPixels

    override fun resized(
        width: Int,
        height: Int,
    ) {
        chain?.resize(width, height)
        if (liveResize.held) invalidate() else fit()
    }

    // The WM_SIZE that Window's SetWindowPos sends next refits the grid at this DPI.
    override fun dpiChanged(dpi: Int) {
        this.dpi = dpi
    }

    override fun paint() {
        val chain = chain ?: return
        if (session == null) {
            chain.clear(palette.background)
        } else {
            renderer.render(frame, sources, chain.view, chain.width, chain.height, scale, focused)
        }
        chain.present()
    }

    override fun message(
        message: Int,
        wParam: ULong,
        lParam: Long,
    ): Boolean {
        // The sizing loop's messages are only watched: the default handler still runs them.
        when (message) {
            WM_WAKE -> {
                refresh()
                return true
            }

            WM_ENTERSIZEMOVE -> {
                liveResize.enter()
            }

            WM_SIZING -> {
                if (liveResize.sizing()) session?.pauseOutput()
            }

            WM_EXITSIZEMOVE -> {
                if (liveResize.exit()) {
                    fit()
                    session?.resumeOutput()
                }
            }

            else -> {
                return input?.handle(message, wParam, lParam) ?: false
            }
        }
        return false
    }

    override fun closed() {
        if (quitOnClose) PostQuitMessage(0)
    }

    override fun place(
        x: Int,
        y: Int,
    ): Place {
        val scale = scale
        val at = cellAt(x / scale, y / scale, text.cell, grid)
        // The text area's pixels start inside the padding; a pointer captured outside the window reports negatives.
        val inset = PADDING * scale
        return Place(at.cols, at.rows, (x - inset).toInt().coerceAtLeast(0), (y - inset).toInt().coerceAtLeast(0))
    }

    override val pageRows: Int get() = grid.rows

    override fun scrolled() = refresh()

    override fun focusChanged(focused: Boolean) {
        this.focused = focused
        invalidate()
    }

    /** Reads the new frame and the events since the last look; the window closes once the shell is gone. */
    private fun refresh() {
        val current = session ?: return
        if (current.update(frame)) {
            sources.absorb(frame.damage, frame.rows)
            invalidate()
        }
        while (true) {
            when (val event = current.pollEvent() ?: break) {
                Event.Bell -> MessageBeep(MB_OK.convert())
                is Event.Title -> event.windowTitle?.let { SetWindowTextW(window.hwnd, it.ifEmpty { TITLE }) }
                is Event.Directory -> windowsPath(event.uri, computerName)?.let { directory = it }
                is Event.ClipboardWrite -> event.clipboardText?.let { writeClipboardText(window.hwnd, it) }
                else -> Unit
            }
        }
        if (current.exitStatus != null || current.failure != null) {
            current.close()
            session = null
            if (quitOnClose) window.close()
        }
    }

    private fun currentGrid(): Cells {
        val (width, height) = window.clientSize
        return gridSize(width / scale, height / scale, text.cell)
    }

    private fun currentCellPixels(): Pair<Int, Int> {
        val scale = scale
        return (text.cell.width * scale).toInt() to (text.cell.height * scale).toInt()
    }

    /** Tells the session the window's grid and cell pixels when either changed. */
    private fun fit() {
        val current = session ?: return
        val next = currentGrid()
        val pixels = currentCellPixels()
        if (next == grid && pixels == cellPixels) return
        grid = next
        cellPixels = pixels
        current.resize(grid.cols, grid.rows, pixels.first, pixels.second)
        invalidate()
        refresh()
    }

    private fun invalidate() {
        InvalidateRect(window.hwnd, null, 0)
    }

    override fun close() {
        session?.close()
        session = null
        chain?.close()
        chain = null
        window.close()
        renderer.close()
        text.close()
        fonts.close()
    }
}

/** Shows the window, runs the user's shell in it and its messages until it closes; the process exits with their code. */
fun run(): Int {
    enablePerMonitorDpi()
    return Gpu.best().use { gpu ->
        App(gpu).use { app ->
            app.window.show()
            app.start()
            Window.runMessageLoop()
        }
    }
}

fun main() {
    kotlin.system.exitProcess(run())
}
