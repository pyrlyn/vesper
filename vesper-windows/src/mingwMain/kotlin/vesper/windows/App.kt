package vesper.windows

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import platform.posix.getenv
import platform.windows.AppendMenuW
import platform.windows.GetSystemMenu
import platform.windows.HWND
import platform.windows.HWND__
import platform.windows.InvalidateRect
import platform.windows.MB_OK
import platform.windows.MF_SEPARATOR
import platform.windows.MF_STRING
import platform.windows.MessageBeep
import platform.windows.POINT
import platform.windows.PostMessageW
import platform.windows.PostQuitMessage
import platform.windows.ScreenToClient
import platform.windows.SetWindowTextW
import platform.windows.WM_APP
import platform.windows.WM_CAPTURECHANGED
import platform.windows.WM_ENTERSIZEMOVE
import platform.windows.WM_EXITSIZEMOVE
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_MBUTTONDOWN
import platform.windows.WM_MBUTTONUP
import platform.windows.WM_MOUSEHWHEEL
import platform.windows.WM_MOUSEMOVE
import platform.windows.WM_MOUSEWHEEL
import platform.windows.WM_RBUTTONDOWN
import platform.windows.WM_RBUTTONUP
import platform.windows.WM_SIZING
import platform.windows.WM_SYSCOMMAND
import platform.windows.WM_XBUTTONDOWN
import platform.windows.WM_XBUTTONUP
import vesper.config.Action
import vesper.config.Font
import vesper.config.LiveConfig
import vesper.config.Settings
import vesper.config.defaultConfigPath
import vesper.host.CellSize
import vesper.host.Cells
import vesper.host.KeyBindings
import vesper.host.Palette
import vesper.host.PaneSet
import vesper.host.PaneTree
import vesper.host.SplitAxis
import vesper.host.paneAt
import vesper.host.promptDirection
import vesper.host.scrollDelta
import vesper.host.zoomedSize
import vesper.input.KeyEvent
import vesper.pty.PtyException
import vesper.session.Session
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.roundToInt

/** Posted from a PTY's thread when a pane's session has output or events to look at. */
internal const val WM_WAKE = WM_APP

/** Posted from the config watcher's thread when the file's settings or its error changed. */
private const val WM_CONFIG = WM_APP + 1

/** The system menu's Settings item; WM_SYSCOMMAND keeps the low four bits of an id for itself. */
private const val SC_SETTINGS = 0x0010

/** The window's title until the shell sets one, and again when it clears its own. */
private const val TITLE = "Vesper"

/** `%APPDATA%\vesper\config.toml`, or null when the environment has no APPDATA. */
fun defaultConfigFile(): String? = defaultConfigPath(windows = true) { getenv(it)?.toKString() }

private val BUTTON_DOWN = setOf(WM_LBUTTONDOWN, WM_RBUTTONDOWN, WM_MBUTTONDOWN, WM_XBUTTONDOWN)
private val BUTTON_UP = setOf(WM_LBUTTONUP, WM_RBUTTONUP, WM_MBUTTONUP, WM_XBUTTONUP)

/**
 * One terminal window: its panes' shells in sessions, drawn with Direct3D into viewports of the
 * window's one swap chain. The keyboard goes to the focused pane and the mouse to the pane under it;
 * a click focuses that pane. A pane whose program exits closes alone, and the last one closes the
 * window. The config file at [configPath] (none when null) is watched, and its settings apply as
 * soon as it changes. [onNewWindow] opens another window; without it, asking for one beeps.
 */
@OptIn(ExperimentalAtomicApi::class)
class App(
    private val gpu: Gpu,
    private val quitOnClose: Boolean = true,
    configPath: String? = defaultConfigFile(),
    private val onNewWindow: (() -> Unit)? = null,
    // Told once the window is gone, so whoever keeps the windows can let go of this one.
    private val onClosed: (App) -> Unit = {},
) : WindowListener,
    InputTarget,
    PaneWindow,
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
    private var windowFocused = false
    private val liveResize = LiveResize()

    // What every pane of the window runs, from [start].
    private var program: String? = null
    private var args = emptyList<String>()

    // Declared before the window: WM_SIZE arrives inside CreateWindowExW, before there is a chain or a pane.
    private var chain: SwapChain? = null
    private var panes: PaneSet<TerminalPane>? = null

    // The pane a button went down in, which takes the mouse until every button is up.
    private var pressed: TerminalPane? = null
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
        panes = PaneSet { id -> TerminalPane(id, this, renderer.paneQuads()) }
        layout()
        if (live != null) {
            val menu = GetSystemMenu(window.hwnd, 0)
            AppendMenuW(menu, MF_SEPARATOR.convert(), 0u, null)
            AppendMenuW(menu, MF_STRING.convert(), SC_SETTINGS.convert(), "Settings…\tCtrl+,")
            configTarget.store(window.hwnd.toLong())
            configChanged()
        }
    }

    override val hwnd: HWND get() = window.hwnd

    override val scale: Double get() = dpi.toDouble() / DEFAULT_DPI

    override val cell: CellSize get() = text.cell

    /** The panes in reading order. */
    internal val paneList: List<TerminalPane> get() = panes?.let { set -> set.tree.panes.mapNotNull { set[it] } }.orEmpty()

    /** The pane that takes the keys; null once the window is closed. */
    internal val focusedPane: TerminalPane? get() = panes?.let { it[it.tree.focused] }

    /**
     * Starts [program] with [args] (the user's shell, `COMSPEC`, when null) in the first pane; every
     * split runs the same.
     */
    fun start(
        program: String? = null,
        args: List<String> = emptyList(),
    ) {
        this.program = program
        this.args = args
        focusedPane?.start(program, args, applied.scrollback)
    }

    /** What the focused pane shows, one line per row; for tests. */
    internal fun screenText(): String = focusedPane?.screenText().orEmpty()

    /** The grid and the cell's pixels the focused pane's session was last told; for tests. */
    internal val sessionSize: Pair<Cells, Pair<Int, Int>> get() = focusedPane!!.sessionSize

    /** The focused pane's shell directory, from its last OSC 7. */
    val directory: String? get() = focusedPane?.directory

    override val session: Session? get() = focusedPane?.session

    override fun resized(
        width: Int,
        height: Int,
    ) {
        chain?.resize(width, height)
        layout()
        if (liveResize.held) invalidate() else fit()
    }

    // The WM_SIZE that Window's SetWindowPos sends next refits the grid at this DPI.
    override fun dpiChanged(dpi: Int) {
        this.dpi = dpi
    }

    override fun paint() {
        val chain = chain ?: return
        val drawn = paneList.filter { it.session != null }
        if (drawn.isEmpty()) {
            chain.clear(palette.background)
        } else {
            val draws =
                drawn.map {
                    val v = it.viewport
                    PaneDraw(it.quads, it.frame, it.sources, v.x, v.y, v.width, v.height, it.focused)
                }
            renderer.render(chain.view, chain.width, chain.height, scale, draws)
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
                // A pane may close on the way, so the list is the one from before.
                paneList.forEach { it.refresh() }
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
                if (liveResize.sizing()) paneList.forEach { it.session?.pauseOutput() }
            }

            WM_EXITSIZEMOVE -> {
                if (liveResize.exit()) {
                    fit()
                    paneList.forEach { it.session?.resumeOutput() }
                }
            }

            else -> {
                return mouse(message, wParam, lParam) ?: input?.handle(message, wParam, lParam) ?: false
            }
        }
        return false
    }

    /** Hands a mouse message to its pane; null when [message] is not the mouse's. */
    private fun mouse(
        message: Int,
        wParam: ULong,
        lParam: Long,
    ): Boolean? {
        val pane =
            when (message) {
                in BUTTON_DOWN -> paneAt(lParam)?.also { pane -> if (pane.id != panes?.tree?.focused) focus(pane.id) }
                in BUTTON_UP, WM_MOUSEMOVE -> pressed ?: paneAt(lParam)
                WM_MOUSEWHEEL, WM_MOUSEHWHEEL -> paneAtScreen(lParam)
                WM_CAPTURECHANGED -> pressed
                else -> return null
            } ?: return true
        if (message in BUTTON_DOWN) pressed = pane
        val handled = pane.input.handle(message, wParam, lParam)
        if (message == WM_CAPTURECHANGED || (message in BUTTON_UP && !pane.input.anyHeld)) pressed = null
        return handled
    }

    /** The pane under client point [lParam]; null over a divider. */
    private fun paneAt(lParam: Long): TerminalPane? = paneAt(loWord(lParam).toShort().toInt(), hiWord(lParam).toShort().toInt())

    // Wheel messages carry the pointer in screen coordinates.
    private fun paneAtScreen(lParam: Long): TerminalPane? =
        memScoped {
            val point = alloc<POINT>()
            point.x = loWord(lParam).toShort().toInt()
            point.y = hiWord(lParam).toShort().toInt()
            ScreenToClient(window.hwnd, point.ptr)
            paneAt(point.x, point.y)
        }

    private fun paneAt(
        x: Int,
        y: Int,
    ): TerminalPane? {
        val set = panes ?: return null
        return paneList.map { it.viewport }.paneAt(x.toDouble(), y.toDouble())?.let { set[it] }
    }

    override fun closed() {
        // The children end now; the GPU's objects go when the owner closes this.
        panes?.close()
        pressed = null
        onClosed(this)
        if (quitOnClose) PostQuitMessage(0)
    }

    override fun place(
        x: Int,
        y: Int,
    ): Place = focusedPane?.place(x, y) ?: Place(0, 0, 0, 0)

    override val pageRows: Int get() = focusedPane?.pageRows ?: 1

    override fun scrolled() {
        focusedPane?.refresh()
    }

    override fun focusChanged(focused: Boolean) {
        windowFocused = focused
        focusedPane?.focusChanged(focused)
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
        val pane = focusedPane ?: return
        scrollDelta(action, pane.pageRows)?.let { rows ->
            pane.session?.scrollDisplay(rows)
            pane.refresh()
            return
        }
        promptDirection(action)?.let { forward ->
            if (pane.session?.scrollToPrompt(forward) == true) pane.refresh() else MessageBeep(MB_OK.convert())
            return
        }
        when (action) {
            Action.Paste -> readClipboardText(window.hwnd)?.let { pane.session?.paste(it) }

            Action.SplitRight -> split(SplitAxis.Horizontal)

            Action.SplitDown -> split(SplitAxis.Vertical)

            Action.ClosePane -> closePane(pane)

            Action.NextPane -> focusNext(1)

            Action.PreviousPane -> focusNext(-1)

            Action.NewWindow -> onNewWindow?.invoke() ?: MessageBeep(MB_OK.convert())

            // Tabs wait for T21's tab strip.
            else -> MessageBeep(MB_OK.convert())
        }
    }

    /** Splits the focused pane and starts the new pane's program; a beep, and no new pane, when it cannot start. */
    internal fun split(axis: SplitAxis) {
        val set = panes ?: return
        val before = focusedPane
        val id = set.split(axis)
        layout()
        val pane = set[id] ?: return
        try {
            pane.start(program, args, applied.scrollback)
        } catch (_: PtyException) {
            set.close(id)
            // Closing a pane focuses its neighbour, which need not be the pane that was split.
            before?.let { set.tree.focus(it.id) }
            layout()
            MessageBeep(MB_OK.convert())
            return
        }
        if (windowFocused) before?.focus(false)
        focused()
        fit()
    }

    /** Closes [pane]; the last one closes the window. */
    internal fun closePane(pane: TerminalPane) {
        val set = panes ?: return
        if (set.tree.panes.size == 1) {
            window.close()
            return
        }
        val wasFocused = pane.id == set.tree.focused
        if (pressed === pane) pressed = null
        set.close(pane.id)
        layout()
        if (wasFocused) focused()
        fit()
    }

    override fun exited(pane: TerminalPane) = closePane(pane)

    /** Moves the keys [step] panes along the reading order. */
    internal fun focusNext(step: Int) = moveFocus { it.focusNext(step) }

    private fun focus(id: Int) = moveFocus { it.focus(id) }

    private fun moveFocus(change: (PaneTree) -> Unit) {
        val set = panes ?: return
        if (windowFocused) focusedPane?.focus(false)
        change(set.tree)
        focused()
    }

    // The tree's focused pane has just changed: it takes the keys if the window has them, and names the window.
    private fun focused() {
        if (windowFocused) focusedPane?.focus(true)
        updateTitle()
        invalidate()
    }

    override fun titleChanged(pane: TerminalPane) {
        if (pane === focusedPane) updateTitle()
    }

    private fun updateTitle() {
        SetWindowTextW(window.hwnd, focusedPane?.title ?: TITLE)
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

    /** Cuts the client area into the panes' viewports, a divider of one DIP between them. */
    private fun layout() {
        val set = panes ?: return
        val (width, height) = window.clientSize
        val divider = maxOf(1, scale.roundToInt())
        for (v in set.tree.layout(width, height, divider)) set[v.pane]?.viewport = v
    }

    /** Tells each pane's session its grid and cell pixels when either changed. */
    private fun fit() {
        paneList.forEach { it.fit() }
    }

    override fun invalidate() {
        InvalidateRect(window.hwnd, null, 0)
    }

    override fun close() {
        // Stopped first, so no change is posted to a window on its way out.
        live?.close()
        settingsDialog?.close()
        settingsDialog = null
        panes?.close()
        panes = null
        chain?.close()
        chain = null
        window.close()
        renderer.close()
        text.close()
        fonts.close()
    }
}

/**
 * Every window of the process, each with its own panes; the process quits when the last one closes.
 * A closed window's GPU objects go at the next open or at the end, outside its own messages.
 */
private class AppWindows(
    private val gpu: Gpu,
) : AutoCloseable {
    private val open = ArrayList<App>()
    private val gone = ArrayList<App>()

    /** Shows a new window running the user's shell; a beep, and no window, when the shell cannot start. */
    fun open() {
        gone.forEach { it.close() }
        gone.clear()
        val app =
            App(gpu, quitOnClose = false, onNewWindow = ::open) {
                open.remove(it)
                gone += it
                if (open.isEmpty()) PostQuitMessage(0)
            }
        open += app
        app.window.show()
        try {
            app.start()
        } catch (_: PtyException) {
            app.window.close()
            MessageBeep(MB_OK.convert())
        }
    }

    override fun close() {
        (open + gone).forEach { it.close() }
        open.clear()
        gone.clear()
    }
}

/** Shows a window, runs the user's shell in it and every window's messages until the last closes; the process exits with their code. */
fun run(): Int {
    enablePerMonitorDpi()
    return Gpu.best().use { gpu ->
        AppWindows(gpu).use { windows ->
            windows.open()
            Window.runMessageLoop()
        }
    }
}

fun main() {
    kotlin.system.exitProcess(run())
}
