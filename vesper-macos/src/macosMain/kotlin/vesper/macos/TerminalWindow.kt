package vesper.macos

import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDelegateProtocol
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSNotification
import platform.darwin.NSObject
import vesper.config.Action
import vesper.config.Settings
import vesper.host.Cells
import vesper.host.viewSize

/** The size a new window opens at. */
private val DEFAULT_GRID = Cells(80, 24)

/** Windows of one group, so AppKit offers to merge them into tabs. */
private const val TABBING_ID = "vesper"

/**
 * One window (or native tab: AppKit makes each tab a window) and its panes. It closes when its last
 * pane does, and its panes' programs end when it closes, however that happens.
 */
internal class TerminalWindow(
    text: TextStyle,
    settings: Settings,
    program: String?,
    // The app holds its windows; this tells it to let go of one.
    private val onClosed: (TerminalWindow) -> Unit,
    // New tabs and windows are the app's.
    onWindowAction: (TerminalWindow, Action) -> Boolean,
) : NSObject(),
    NSWindowDelegateProtocol {
    val window: NSWindow
    val panes: PaneHostView

    init {
        val (width, height) = viewSize(DEFAULT_GRID, text.cell)
        window =
            NSWindow(
                contentRect = NSMakeRect(0.0, 0.0, width, height),
                styleMask =
                    NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable,
                backing = NSBackingStoreBuffered,
                defer = false,
            )
        window.title = "vesper"
        window.releasedWhenClosed = false
        window.tabbingIdentifier = TABBING_ID
        window.contentResizeIncrements = NSMakeSize(text.cell.width, text.cell.height)
        panes = PaneHostView(text, NSMakeRect(0.0, 0.0, width, height), settings)
        panes.program = program
        panes.onCellSize = { window.contentResizeIncrements = NSMakeSize(it.width, it.height) }
        panes.onTitle = { window.title = it }
        panes.onDirectory = { window.representedURL = it }
        panes.onEmpty = { window.close() }
        panes.onWindowAction = { onWindowAction(this, it) }
        window.contentView = panes
        window.delegate = this
    }

    /** Shows the window and starts its first pane's program, at the window's final size. */
    fun start() {
        window.makeKeyAndOrderFront(null)
        window.makeFirstResponder(panes.focusedView)
        panes.start()
    }

    fun apply(settings: Settings): Unit = panes.apply(settings)

    override fun windowWillClose(notification: NSNotification) {
        panes.stop()
        onClosed(this)
    }
}
