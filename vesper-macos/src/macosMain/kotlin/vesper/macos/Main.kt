package vesper.macos

import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSApplicationDelegateProtocol
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSFloatingWindowLevel
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.Foundation.NSActivityLatencyCritical
import platform.Foundation.NSActivityUserInitiated
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSNotification
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSelectorFromString
import platform.Foundation.beginActivityWithOptions
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.posix.fflush
import platform.posix.stdout
import vesper.host.Cells
import vesper.host.viewSize

/** The size a new window opens at. */
private val DEFAULT_GRID = Cells(80, 24)

/** The app: one window running the user's shell, closed when the shell exits. */
private class AppDelegate(
    // Measures key-to-screen latency in a window running cat, prints it and quits.
    private val probe: Boolean,
) : NSObject(),
    NSApplicationDelegateProtocol {
    private var view: TerminalView? = null

    override fun applicationDidFinishLaunching(notification: NSNotification) {
        val text = TextStyle()
        val (width, height) = viewSize(DEFAULT_GRID, text.cell)
        val window =
            NSWindow(
                contentRect = NSMakeRect(0.0, 0.0, width, height),
                styleMask =
                    NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable,
                backing = NSBackingStoreBuffered,
                defer = false,
            )
        window.title = "vesper"
        window.releasedWhenClosed = false
        window.contentResizeIncrements = NSMakeSize(text.cell.width, text.cell.height)
        val terminal = TerminalView(text, NSMakeRect(0.0, 0.0, width, height))
        terminal.onTitle = { window.title = it }
        terminal.onDirectory = { window.representedURL = it }
        terminal.onExit = { window.close() }
        window.contentView = terminal
        window.center()
        window.makeKeyAndOrderFront(null)
        window.makeFirstResponder(terminal)
        terminal.start(window.backingScaleFactor, program = if (probe) "/bin/cat" else null)
        if (probe) startProbe(terminal)
        view = terminal
        NSApplication.sharedApplication.activate()
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
        view?.stop()
    }
}

private fun mainMenu(): NSMenu {
    val menu = NSMenu()
    val app = NSMenu()
    app.addItemWithTitle("Quit vesper", NSSelectorFromString("terminate:"), "q")
    menu.addItem(NSMenuItem().also { it.submenu = app })
    val edit = NSMenu(title = "Edit")
    edit.addItemWithTitle("Paste", NSSelectorFromString("paste:"), "v")
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
