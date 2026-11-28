package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.toKString
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSApplicationDelegateProtocol
import platform.AppKit.NSBeep
import platform.AppKit.NSFloatingWindowLevel
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSWindowAbove
import platform.Foundation.NSActivityLatencyCritical
import platform.Foundation.NSActivityUserInitiated
import platform.Foundation.NSMakePoint
import platform.Foundation.NSNotification
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSelectorFromString
import platform.Foundation.beginActivityWithOptions
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.fflush
import platform.posix.getenv
import platform.posix.stdout
import vesper.config.Action
import vesper.config.LiveConfig
import vesper.config.Settings
import vesper.config.defaultConfigPath
import vesper.pty.PtyException

/** The app: windows and tabs of panes running the user's shell; it quits when the last window closes. */
private class AppDelegate(
    // Measures key-to-screen latency in a window running cat, prints it and quits.
    private val probe: Boolean,
) : NSObject(),
    NSApplicationDelegateProtocol {
    // AppKit keeps a window's delegate weakly, so the app holds every window here.
    private val windows = ArrayList<TerminalWindow>()

    // Null when the environment names no home for the file: the defaults then, and no settings to save.
    private var live: LiveConfig? = null
    private var settingsWindow: SettingsWindow? = null
    private var settings = Settings.DEFAULT

    override fun applicationDidFinishLaunching(notification: NSNotification) {
        val config =
            defaultConfigPath(windows = false) { getenv(it)?.toKString() }?.let { path ->
                LiveConfig(path) { dispatch_async(dispatch_get_main_queue()) { configChanged() } }
            }
        live = config
        settings = config?.snapshot()?.settings ?: Settings.DEFAULT
        val first = makeWindow()
        first.window.center()
        first.start()
        if (probe) startProbe(first.panes.focusedView)
        NSApplication.sharedApplication.activate()
    }

    private fun makeWindow(): TerminalWindow =
        TerminalWindow(
            TextStyle(settings.font),
            settings,
            program = if (probe) "/bin/cat" else null,
            onClosed = { windows.remove(it) },
            onWindowAction = ::perform,
        ).also { windows += it }

    private fun perform(
        from: TerminalWindow,
        action: Action,
    ): Boolean {
        when (action) {
            Action.NewTab -> open(from, tab = true)
            Action.NewWindow -> open(from, tab = false)
            else -> return false
        }
        return true
    }

    /** Opens a window next to [from], or a tab in its group; a beep, and nothing, when its shell cannot start. */
    private fun open(
        from: TerminalWindow?,
        tab: Boolean,
    ) {
        val next = makeWindow()
        val near = from?.window
        when {
            near == null -> next.window.center()

            tab -> near.addTabbedWindow(next.window, NSWindowAbove)

            // A point of (0, 0) leaves the window in place and returns where the next one cascades to.
            else -> next.window.cascadeTopLeftFromPoint(near.cascadeTopLeftFromPoint(NSMakePoint(0.0, 0.0)))
        }
        try {
            next.start()
        } catch (_: PtyException) {
            next.window.close()
            NSBeep()
        }
    }

    /** The tab bar's + button; AppKit shows it only when something in the responder chain answers this. */
    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun newWindowForTab(sender: NSObject?) {
        open(windows.firstOrNull { it.window.keyWindow } ?: windows.lastOrNull(), tab = true)
    }

    private fun configChanged() {
        val snapshot = live?.snapshot() ?: return
        settings = snapshot.settings
        windows.forEach { it.apply(snapshot.settings) }
        settingsWindow?.show(snapshot)
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun showSettings(sender: NSObject?) {
        val config = live ?: return NSBeep()
        (settingsWindow ?: SettingsWindow(config).also { settingsWindow = it }).present()
    }

    private var activity: NSObjectProtocol? = null

    private fun startProbe(terminal: TerminalView) {
        val finish = { report: String ->
            println(report)
            fflush(stdout)
            NSApplication.sharedApplication.terminate(null)
        }
        if (terminal.renderer == null) return finish("No Metal device: nothing measured.")
        // Started from a shell, the app may stay behind other windows and be napped: an occluded,
        // napping window's frames are never composited, and its timers drift by seconds.
        terminal.window?.level = NSFloatingWindowLevel
        activity = NSProcessInfo.processInfo.beginActivityWithOptions(NSActivityUserInitiated or NSActivityLatencyCritical, "latency probe")
        LatencyProbe(terminal, done = finish).start()
    }

    override fun applicationShouldTerminateAfterLastWindowClosed(sender: NSApplication): Boolean = true

    override fun applicationWillTerminate(notification: NSNotification) {
        windows.toList().forEach { it.panes.stop() }
        live?.close()
    }
}

private fun mainMenu(): NSMenu {
    val menu = NSMenu()
    val app = NSMenu()
    app.addItemWithTitle("Settings…", NSSelectorFromString("showSettings:"), ",")
    app.addItem(NSMenuItem.separatorItem())
    app.addItemWithTitle("Quit vesper", NSSelectorFromString("terminate:"), "q")
    menu.addItem(NSMenuItem().also { it.submenu = app })
    val edit = NSMenu(title = "Edit")
    edit.addItemWithTitle("Copy", NSSelectorFromString("copy:"), "c")
    edit.addItemWithTitle("Paste", NSSelectorFromString("paste:"), "v")
    edit.addItem(NSMenuItem.separatorItem())
    // A capital key equivalent adds Shift.
    edit.addItemWithTitle("Find…", NSSelectorFromString("showFind:"), "f")
    edit.addItemWithTitle("Find Next", NSSelectorFromString("findNext:"), "g")
    edit.addItemWithTitle("Find Previous", NSSelectorFromString("findPrevious:"), "G")
    menu.addItem(NSMenuItem().also { it.submenu = edit })
    return menu
}

// AppKit keeps its delegate weakly, so the app holds this one for its lifetime.
private var delegate: AppDelegate? = null

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--bench-renderer") return print(rendererBenchMarkdown())
    val appDelegate = AppDelegate(probe = args.firstOrNull() == "--latency")
    delegate = appDelegate
    val app = NSApplication.sharedApplication
    app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    app.mainMenu = mainMenu()
    app.delegate = appDelegate
    app.run()
}
