package vesper.windows

import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.posix.getenv
import platform.windows.AppendMenuW
import platform.windows.GetSystemMenu
import platform.windows.HWND__
import platform.windows.InvalidateRect
import platform.windows.MB_OK
import platform.windows.MF_SEPARATOR
import platform.windows.MF_STRING
import platform.windows.MessageBeep
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.SetWindowTextW
import platform.windows.WM_APP
import platform.windows.WM_ENTERSIZEMOVE
import platform.windows.WM_EXITSIZEMOVE
import platform.windows.WM_SIZING
import platform.windows.WM_SYSCOMMAND
import vesper.config.Action
import vesper.config.Font
import vesper.config.LiveConfig
import vesper.config.Settings
import vesper.config.defaultConfigPath
import vesper.host.Cells
import vesper.host.KeyBindings
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.RowSources
import vesper.host.cellAt
import vesper.host.clipboardText
import vesper.host.gridSize
import vesper.host.launchEnvironment
import vesper.host.promptDirection
import vesper.host.scrollDelta
import vesper.host.windowTitle
import vesper.host.zoomedSize
import vesper.input.KeyEvent
import vesper.pty.PtySize
import vesper.pty.SpawnOptions
import vesper.session.Session
import vesper.term.Event
import vesper.term.Frame
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Posted from the PTY's thread when the session has output or events to look at. */
private const val WM_WAKE = WM_APP

/** Posted from the config watcher's thread when the file's settings or its error changed. */
private const val WM_CONFIG = WM_APP + 1

/** The system menu's Settings item; WM_SYSCOMMAND keeps the low four bits of an id for itself. */
private const val SC_SETTINGS = 0x0010

/** The window's title until the shell sets one, and again when it clears its own. */
private const val TITLE = "Vesper"

/** `%APPDATA%\vesper\config.toml`, or null when the environment has no APPDATA. */
fun defaultConfigFile(): String? = defaultConfigPath(windows = true) { getenv(it)?.toKString() }

/**
 * One terminal window: the user's shell in a session, drawn with Direct3D into the window's swap
 * chain, with the window's keyboard, mouse and focus going to it. The config file at [configPath]
 * (none when null) is watched, and its settings apply as soon as it changes.
 */
@OptIn(ExperimentalAtomicApi::class)
class App(
    private val gpu: Gpu,
    private val quitOnClose: Boolean = true,
    configPath: String? = defaultConfigFile(),
) : WindowListener,
    InputTarget,
    AutoCloseable {
    // The window's handle once there is one, for the watcher's thread to post to; until then a
    // change is only missed, since the window applies the latest snapshot as it is made.
    private val configTarget = AtomicLong(0)
    private val live =
        configPath?.let { path ->
            LiveConfig(path) {
                val hwnd = configTarget.load()
                if (hwnd != 0L) PostMessageW(hwnd.toCPointer<HWND__>(), WM_CONFIG.convert(), 0u, 0)
            }
        }

    /** The settings in force: the file's when it was read. */
    internal var applied = live?.snapshot()?.settings ?: Settings.DEFAULT
        private set
    private var palette = Palette(applied.colors)
    private val fonts = FontSystem.create()

    /** The text in use, at the zoomed size after a zoom. */
    internal var text =
        TextStyle.of(fonts, applied.font) ?: run {
            fonts.close()
            live?.close()
            error("neither Consolas nor Courier New is installed")
        }
        private set
    private val renderer = D3DRenderer.create(gpu, text, palette)
    private var bindings = KeyBindings(applied.bindings)

    /** The settings dialog once opened. */
    internal var settingsDialog: SettingsDialog? = null
        private set
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
        if (live != null) {
            val menu = GetSystemMenu(window.hwnd, 0)
            AppendMenuW(menu, MF_SEPARATOR.convert(), 0u, null)
            AppendMenuW(menu, MF_STRING.convert(), SC_SETTINGS.convert(), "Settings…\tCtrl+,")
            configTarget.store(window.hwnd.toLong())
            configChanged()
        }
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
        // Programs under WSL or ssh read TERM and the locale; native ones ignore them.
        val environment = launchEnvironment { name -> getenv(name)?.toKString() }
        val options = SpawnOptions(program, args, getenv("USERPROFILE")?.toKString(), environment, PtySize(grid.rows, grid.cols))
        val started = Session.spawn(options, applied.scrollback) { PostMessageW(hwnd.toCPointer<HWND__>(), WM_WAKE.convert(), 0u, 0) }
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

            WM_CONFIG -> {
                configChanged()
                return true
            }

            WM_SYSCOMMAND -> {
                if (wParam.toInt() and 0xFFF0 != SC_SETTINGS) return false
                openSettings()
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

    override fun shortcut(event: KeyEvent): Boolean {
        val action = bindings.action(event)
        if (action != null) {
            perform(action)
            return true
        }
        if (!isSettings(event.key, event.mods)) return false
        openSettings()
        return true
    }

    /** Runs a key binding's [action]. */
    internal fun perform(action: Action) {
        zoomedSize(action, text.font.size, applied.font.size)?.let { size ->
            TextStyle.of(fonts, Font(applied.font.family, size))?.let { restyle(it, palette) }
            return
        }
        scrollDelta(action, grid.rows)?.let { rows ->
            session?.scrollDisplay(rows)
            refresh()
            return
        }
        promptDirection(action)?.let { forward ->
            if (session?.scrollToPrompt(forward) == true) refresh() else MessageBeep(MB_OK.convert())
            return
        }
        when (action) {
            Action.Paste -> readClipboardText(window.hwnd)?.let { session?.paste(it) }

            // Tabs, windows and panes wait for T21's Windows slices.
            else -> MessageBeep(MB_OK.convert())
        }
    }

    /**
     * Takes [settings] as they are: the bindings at once, the scrollback for the next session, and
     * the font and colours redrawn. A colour change keeps a zoom; a font change replaces it.
     */
    internal fun apply(settings: Settings) {
        if (settings == applied) return
        val previous = applied
        applied = settings
        bindings = KeyBindings(settings.bindings)
        val font = if (settings.font != previous.font) TextStyle.of(fonts, settings.font) else null
        if (font != null || settings.colors != previous.colors) restyle(font ?: text, Palette(settings.colors))
    }

    private fun configChanged() {
        val snapshot = live?.snapshot() ?: return
        apply(snapshot.settings)
        settingsDialog?.show(snapshot)
    }

    /** Opens the settings dialog, made the first time; a beep when there is no config file to write. */
    internal fun openSettings() {
        val config = live
        if (config == null) {
            MessageBeep(MB_OK.convert())
            return
        }
        (settingsDialog ?: SettingsDialog(config, window.hwnd).also { settingsDialog = it }).present(window.hwnd)
    }

    private fun restyle(
        next: TextStyle,
        colors: Palette,
    ) {
        val old = text
        text = next
        palette = colors
        renderer.restyle(next, colors)
        if (old !== next) old.close()
        fit()
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
        // Stopped first, so no change is posted to a window on its way out.
        live?.close()
        settingsDialog?.close()
        settingsDialog = null
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
