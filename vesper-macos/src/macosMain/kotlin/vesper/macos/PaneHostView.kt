package vesper.macos

import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSBeep
import platform.AppKit.NSView
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGSize
import platform.Foundation.NSMakeRect
import platform.Foundation.NSURL
import vesper.config.Action
import vesper.config.Settings
import vesper.host.CellSize
import vesper.host.PaneSet
import vesper.host.SplitAxis
import vesper.pty.PtyException

/** Points between neighbouring panes, where the window's background shows. */
private const val DIVIDER = 1

/** The title of a pane whose program has named none. */
private const val UNTITLED = "vesper"

/**
 * One tab's panes: a [TerminalView] per pane of a [PaneSet], framed by its layout. A split starts
 * one more program and leaves the others running; a pane whose child exits or whose terminal fails
 * closes alone, and the last one to go empties the tab ([onEmpty]). The window's title and
 * directory follow the focused pane.
 */
internal class PaneHostView(
    private val text: TextStyle,
    frame: CValue<CGRect>,
    private var settings: Settings,
) : NSView(frame) {
    /** What every pane runs; the user's shell when null. */
    var program: String? = null

    var onTitle: (String) -> Unit = {}
    var onDirectory: (NSURL) -> Unit = {}
    var onCellSize: (CellSize) -> Unit = {}

    /** The last pane closed: the tab goes. */
    var onEmpty: () -> Unit = {}

    /** A tab or window action; false when nothing ran it. */
    var onWindowAction: (Action) -> Boolean = { false }

    private inner class Pane(
        val id: Int,
    ) : AutoCloseable {
        val view = TerminalView(text, NSMakeRect(0.0, 0.0, 0.0, 0.0))
        var title: String? = null
        var directory: NSURL? = null

        private val focused get() = id == panes.tree.focused

        init {
            view.apply(settings)
            view.onCellSize = { if (focused) onCellSize(it) }
            view.onTitle = {
                title = it
                if (focused) onTitle(it)
            }
            view.onDirectory = {
                directory = it
                if (focused) onDirectory(it)
            }
            view.onExit = { closePane(id) }
            view.onFocused = { focusChanged(id) }
            view.onAction = ::perform
            addSubview(view)
        }

        override fun close() {
            view.stop()
            view.removeFromSuperview()
        }
    }

    private val panes = PaneSet(::Pane)

    init {
        autoresizesSubviews = false
    }

    /** The views in reading order. */
    val views: List<TerminalView> get() = panes.tree.panes.mapNotNull { panes[it]?.view }

    val focusedView: TerminalView get() = panes.focused.view

    private val scale: Double get() = window?.backingScaleFactor ?: 1.0

    /** Starts the first pane's program at this view's size. */
    fun start() {
        layoutPanes()
        focusedView.start(scale, program)
    }

    /** Ends every pane's program: the tab is closing. */
    fun stop(): Unit = panes.close()

    fun apply(next: Settings) {
        settings = next
        views.forEach { it.apply(next) }
    }

    /** Splits the focused pane and starts the new pane's program; a beep, and no new pane, when it cannot start. */
    fun split(axis: SplitAxis) {
        val before = panes.tree.focused
        val id = panes.split(axis)
        // The program starts at the grid of its frame, so the frame comes first.
        layoutPanes()
        val view = panes[id]?.view ?: return
        try {
            view.start(scale, program)
        } catch (_: PtyException) {
            panes.close(id)
            // Closing a pane focuses its neighbour, which need not be the pane that was split.
            panes.tree.focus(before)
            layoutPanes()
            NSBeep()
            return
        }
        focusView()
    }

    /** Closes [id]'s pane; the last one empties the tab instead. A child's exit repeats this until its pane is gone. */
    fun closePane(id: Int) {
        val pane = panes[id] ?: return
        if (panes.tree.panes.size == 1) {
            // Stopped now, so the repeats end even if the tab outlives this call.
            pane.view.stop()
            onEmpty()
            return
        }
        val wasFocused = id == panes.tree.focused
        panes.close(id)
        layoutPanes()
        if (wasFocused) focusView()
    }

    /** Moves the focus [step] panes along the reading order. */
    fun focusNext(step: Int) {
        panes.tree.focusNext(step)
        focusView()
    }

    private fun perform(action: Action): Boolean {
        when (action) {
            Action.SplitRight -> split(SplitAxis.Horizontal)
            Action.SplitDown -> split(SplitAxis.Vertical)
            Action.ClosePane -> closePane(panes.tree.focused)
            Action.NextPane -> focusNext(1)
            Action.PreviousPane -> focusNext(-1)
            else -> return onWindowAction(action)
        }
        return true
    }

    // The tree is told directly too: without a window (a test) no view becomes first responder.
    private fun focusView() {
        focusChanged(panes.tree.focused)
        window?.makeFirstResponder(focusedView)
    }

    private fun focusChanged(id: Int) {
        val pane = panes[id] ?: return
        panes.tree.focus(id)
        onTitle(pane.title ?: UNTITLED)
        pane.directory?.let(onDirectory)
    }

    override fun isFlipped(): Boolean = true

    override fun setFrameSize(newSize: CValue<CGSize>) {
        super.setFrameSize(newSize)
        layoutPanes()
    }

    private fun layoutPanes() {
        val (width, height) = bounds.useContents { size.width.toInt() to size.height.toInt() }
        for (v in panes.tree.layout(width, height, DIVIDER)) {
            panes[v.pane]?.view?.setFrame(NSMakeRect(v.x.toDouble(), v.y.toDouble(), v.width.toDouble(), v.height.toDouble()))
        }
    }
}
