package vesper.windows

import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.posix.getenv
import platform.windows.HWND__
import platform.windows.InvalidateRect
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.WM_APP
import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.RowSources
import vesper.host.cellAt
import vesper.host.gridSize
import vesper.pty.PtySize
import vesper.pty.SpawnOptions
import vesper.session.Session
import vesper.term.Frame

/** Posted from the PTY's thread when the session has output or events to look at. */
private const val WM_WAKE = WM_APP

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
    private var focused = false
    override var session: Session? = null
        private set

    // Declared before the window: WM_SIZE arrives inside CreateWindowExW, before there is a chain.
    private var chain: SwapChain? = null
    val window = Window("Vesper", 960, 600, this)
    internal val swapChain: SwapChain get() = chain!!

    // Null while CreateWindowExW sends its first messages, before the window is assigned.
    private var input: WindowInput? = null

    init {
        val (width, height) = window.clientSize
        chain = SwapChain.create(gpu, window.hwnd, width, height)
        input = WindowInput(window.hwnd, this)
    }

    /** Pixels per DIP on the window's monitor. */
    private val scale: Double get() = window.dpi.toDouble() / DEFAULT_DPI

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
        resizeSession(started)
    }

    /** What the screen shows, one line per row; for tests. */
    internal fun screenText(): String = (0 until frame.rows).joinToString("\n") { frame.row(it)?.text.orEmpty() }

    override fun resized(
        width: Int,
        height: Int,
    ) {
        chain?.resize(width, height)
        val current = session ?: return
        val next = currentGrid()
        if (next == grid) return
        grid = next
        resizeSession(current)
        refresh()
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
        if (message != WM_WAKE) return input?.handle(message, wParam, lParam) ?: false
        refresh()
        return true
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

    /** Reads the new frame and drops the events since the last look; the window closes once the shell is gone. */
    private fun refresh() {
        val current = session ?: return
        if (current.update(frame)) {
            sources.absorb(frame.damage, frame.rows)
            invalidate()
        }
        // Title, directory, clipboard and the bell are slice 7's; until then they are read and let go.
        do {
            val event = current.pollEvent()
        } while (event != null)
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

    private fun resizeSession(session: Session) {
        val scale = scale
        session.resize(grid.cols, grid.rows, (text.cell.width * scale).toInt(), (text.cell.height * scale).toInt())
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
