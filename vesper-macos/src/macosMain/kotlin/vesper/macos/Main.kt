package vesper.macos

import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSApplicationDelegateProtocol
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSMenu
import platform.AppKit.NSMenuItem
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSNotification
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject

/** The size a new window opens at. */
private val DEFAULT_GRID = Cells(80, 24)

/** The app: one window running the user's shell, closed when the shell exits. */
private class AppDelegate :
    NSObject(),
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
        terminal.start(window.backingScaleFactor)
        view = terminal
        NSApplication.sharedApplication.activate()
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
private val delegate = AppDelegate()

fun main() {
    val app = NSApplication.sharedApplication
    app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    app.mainMenu = mainMenu()
    app.delegate = delegate
    app.run()
}
