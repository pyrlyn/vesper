package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.AppKit.NSBeep
import platform.AppKit.NSEvent
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString
import platform.AppKit.NSTrackingActiveInKeyWindow
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextSetTextMatrix
import platform.CoreGraphics.CGContextStrokeRect
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.getenv
import vesper.grid.Attrs
import vesper.grid.Style
import vesper.grid.Underline
import vesper.input.KeyAction
import vesper.input.MouseAction
import vesper.input.MouseButton
import vesper.input.MouseEvent
import vesper.pty.PtySize
import vesper.pty.SpawnOptions
import vesper.session.Session
import vesper.term.Event
import vesper.term.Frame
import vesper.term.TitleTarget
import kotlin.math.abs

private const val CHANNEL_MAX = 255.0

/**
 * One terminal in a window: draws the frame with CoreText, sends keys, mouse and paste to the
 * session, and follows its size. Everything here runs on the main thread; the session's wakeup
 * only posts [refresh] to the main queue.
 */
internal class TerminalView(
    private val text: TextStyle,
    frame: CValue<CGRect>,
) : NSView(frame) {
    private val palette = Palette()
    private val screen = Frame()
    private var session: Session? = null
    private var grid = Cells(0, 0)
    private var focused = false
    private var lastMotion: Cells? = null
    private var scrollLines = 0.0

    /** The window's title and directory follow the program; the app closes the window when the child exits. */
    var onTitle: (String) -> Unit = {}
    var onDirectory: (NSURL) -> Unit = {}
    var onExit: () -> Unit = {}

    /** The screen's text, row by row; what the accessibility tree will read (T16) and the tests check. */
    val screenText: String get() =
        (0 until screen.rows).joinToString("\n") {
            screen
                .row(it)
                ?.text
                .orEmpty()
                .trimEnd()
        }

    /** Starts [program] (the user's shell when null) in the home directory at the view's current size. */
    fun start(
        scale: Double,
        program: String? = null,
    ) {
        grid = currentGrid()
        val home = NSHomeDirectory()
        val environment = launchEnvironment { name -> getenv(name)?.toKString() }
        val options = SpawnOptions(program = program, cwd = home, env = environment, size = PtySize(grid.rows, grid.cols))
        val started = Session.spawn(options) { dispatch_async(dispatch_get_main_queue()) { refresh() } }
        session = started
        started.resize(grid.cols, grid.rows, (text.cell.width * scale).toInt(), (text.cell.height * scale).toInt())
        addTrackingArea(NSTrackingArea(bounds, NSTrackingMouseMoved or NSTrackingActiveInKeyWindow or NSTrackingInVisibleRect, this, null))
    }

    fun stop() {
        session?.close()
        session = null
    }

    /** Reads the new frame and the events since the last look; runs on the main queue. */
    fun refresh() {
        val current = session ?: return
        if (current.update(screen)) needsDisplay = true
        while (true) {
            when (val event = current.pollEvent() ?: break) {
                Event.Bell -> NSBeep()

                is Event.Title -> if (event.target != TitleTarget.Icon) onTitle(event.text)

                // Only a local file URL names a directory this Mac can show.
                is Event.Directory -> NSURL.URLWithString(event.uri)?.takeIf { it.fileURL }?.let(onDirectory)

                is Event.ClipboardWrite -> writeClipboard(event.data)

                else -> Unit
            }
        }
        if (current.exitStatus != null || current.failure != null) onExit()
    }

    private fun writeClipboard(data: ByteArray) {
        // The child's bytes need not be text; anything that is not UTF-8 is dropped.
        val string = runCatching { data.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return
        val pasteboard = NSPasteboard.generalPasteboard
        pasteboard.clearContents()
        pasteboard.setString(string, NSPasteboardTypeString)
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstResponder(): Boolean = true

    override fun becomeFirstResponder(): Boolean {
        focusChanged(true)
        return true
    }

    override fun resignFirstResponder(): Boolean {
        focusChanged(false)
        return true
    }

    private fun focusChanged(now: Boolean) {
        focused = now
        session?.focus(now)
        needsDisplay = true
    }

    // Size: the terminal follows the view, but only once a drag ends, with the child's output held
    // meanwhile, so a shell redraws once rather than for every step of the drag.
    override fun setFrameSize(newSize: CValue<CGSize>) {
        super.setFrameSize(newSize)
        if (!inLiveResize) applySize()
    }

    override fun viewWillStartLiveResize() {
        super.viewWillStartLiveResize()
        session?.pauseOutput()
    }

    override fun viewDidEndLiveResize() {
        super.viewDidEndLiveResize()
        applySize()
        session?.resumeOutput()
    }

    private fun currentGrid(): Cells = bounds.useContents { gridSize(size.width, size.height, text.cell) }

    private fun applySize() {
        val current = session ?: return
        val next = currentGrid()
        if (next == grid) return
        grid = next
        val scale = window?.backingScaleFactor ?: 1.0
        current.resize(next.cols, next.rows, (text.cell.width * scale).toInt(), (text.cell.height * scale).toInt())
        refresh()
    }

    // Keys. Text input methods arrive with T16; until then a key's own characters are its text.
    override fun keyDown(event: NSEvent) = key(event, if (event.ARepeat) KeyAction.Repeat else KeyAction.Press)

    override fun keyUp(event: NSEvent) = key(event, KeyAction.Release)

    private fun key(
        event: NSEvent,
        action: KeyAction,
    ) {
        val unmodified = event.charactersByApplyingModifiers(0u)
        val mapped =
            macKeyEvent(
                event.keyCode.toInt(),
                event.modifierFlags.toLong(),
                event.characters,
                event.charactersIgnoringModifiers,
                unmodified,
                action,
            )
        val current = session ?: return
        if (mapped != null) {
            current.key(mapped)
        } else if (action != KeyAction.Release) {
            event.characters?.let(current::text)
        }
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun paste(sender: NSObject?) {
        NSPasteboard.generalPasteboard.stringForType(NSPasteboardTypeString)?.let { session?.paste(it) }
    }

    // Mouse: reported to a program that asked; otherwise the wheel scrolls the history.
    override fun mouseDown(event: NSEvent) {
        mouse(event, MouseAction.Press, MouseButton.Left)
    }

    override fun mouseUp(event: NSEvent) {
        mouse(event, MouseAction.Release, MouseButton.Left)
    }

    override fun mouseDragged(event: NSEvent) {
        mouse(event, MouseAction.Motion, MouseButton.Left)
    }

    override fun rightMouseDown(event: NSEvent) {
        mouse(event, MouseAction.Press, MouseButton.Right)
    }

    override fun rightMouseUp(event: NSEvent) {
        mouse(event, MouseAction.Release, MouseButton.Right)
    }

    override fun rightMouseDragged(event: NSEvent) {
        mouse(event, MouseAction.Motion, MouseButton.Right)
    }

    override fun otherMouseDown(event: NSEvent) {
        mouse(event, MouseAction.Press, MouseButton.Middle)
    }

    override fun otherMouseUp(event: NSEvent) {
        mouse(event, MouseAction.Release, MouseButton.Middle)
    }

    override fun otherMouseDragged(event: NSEvent) {
        mouse(event, MouseAction.Motion, MouseButton.Middle)
    }

    override fun mouseMoved(event: NSEvent) {
        mouse(event, MouseAction.Motion, null)
    }

    override fun scrollWheel(event: NSEvent) {
        // A trackpad reports points; a wheel reports lines.
        scrollLines += if (event.hasPreciseScrollingDeltas) event.scrollingDeltaY / text.cell.height else event.scrollingDeltaY
        val lines = scrollLines.toInt()
        if (lines == 0) return
        scrollLines -= lines
        val button = if (lines > 0) MouseButton.WheelUp else MouseButton.WheelDown
        if (!mouse(event, MouseAction.Press, button)) {
            session?.scrollDisplay(lines)
            refresh()
            needsDisplay = true
            return
        }
        repeat(abs(lines) - 1) { mouse(event, MouseAction.Press, button) }
    }

    private fun mouse(
        event: NSEvent,
        action: MouseAction,
        button: MouseButton?,
    ): Boolean {
        val current = session ?: return false
        val (x, y) = convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        val at = cellAt(x, y, text.cell, grid)
        // Moves within one cell are dropped, as xterm does.
        if (action == MouseAction.Motion && at == lastMotion) return true
        lastMotion = at
        val scale = window?.backingScaleFactor ?: 1.0
        val px = ((x - PADDING).coerceAtLeast(0.0) * scale).toInt()
        val py = ((y - PADDING).coerceAtLeast(0.0) * scale).toInt()
        return current.mouse(MouseEvent(action, button, macModifiers(event.modifierFlags.toLong()), at.cols, at.rows, px, py))
    }

    override fun drawRect(dirtyRect: CValue<CGRect>) {
        val context = NSGraphicsContext.currentContext?.CGContext ?: return
        // The view is flipped, so text would come out upside down without this.
        CGContextSetTextMatrix(context, CGAffineTransformMakeScale(1.0, -1.0))
        fill(context, palette.background)
        bounds.useContents { CGContextFillRect(context, CGRectMake(0.0, 0.0, size.width, size.height)) }
        val cell = text.cell
        for (r in 0 until screen.rows) {
            val row = screen.row(r) ?: continue
            val top = PADDING + r * cell.height
            for (run in row.runs) {
                val style = screen.style(run.style) ?: Style.DEFAULT
                val colors = palette.colors(style)
                val left = PADDING + run.col * cell.width
                val width = run.cols * cell.width
                if (colors.bg != palette.background) {
                    fill(context, colors.bg)
                    CGContextFillRect(context, CGRectMake(left, top, width, cell.height))
                }
                if (style.attrs and Attrs.HIDDEN != 0) continue
                fill(context, colors.fg)
                val runText = row.runText(run)
                if (runText.isNotBlank()) {
                    text.draw(context, runText, left, top + text.ascent, style.attrs and Attrs.BOLD != 0, style.attrs and Attrs.ITALIC != 0)
                }
                if (style.underline != Underline.None) CGContextFillRect(context, CGRectMake(left, top + text.ascent + 1, width, 1.0))
                if (style.attrs and Attrs.STRIKE != 0) CGContextFillRect(context, CGRectMake(left, top + cell.height / 2, width, 1.0))
            }
        }
        drawCursor(context)
    }

    private fun drawCursor(context: CGContextRef) {
        val cursor = screen.cursor ?: return
        if (cursor.row !in 0 until screen.rows || cursor.col !in 0 until screen.cols) return
        val cell = text.cell
        val under = screen.cell(cursor.row, cursor.col)
        val rect =
            CGRectMake(
                PADDING + cursor.col * cell.width,
                PADDING + cursor.row * cell.height,
                under.width.coerceAtLeast(1) * cell.width,
                cell.height,
            )
        if (!focused) {
            stroke(context, palette.cursor)
            CGContextStrokeRect(context, rect)
            return
        }
        fill(context, palette.cursor)
        CGContextFillRect(context, rect)
        if (under.codePoint > ' '.code) {
            fill(context, palette.background)
            val left = rect.useContents { origin.x }
            text.draw(
                context,
                codePointText(under.codePoint),
                left,
                PADDING + cursor.row * cell.height + text.ascent,
                bold = false,
                italic = false,
            )
        }
    }

    private fun fill(
        context: CGContextRef,
        color: Int,
    ) = CGContextSetRGBFillColor(context, red(color) / CHANNEL_MAX, green(color) / CHANNEL_MAX, blue(color) / CHANNEL_MAX, 1.0)

    private fun stroke(
        context: CGContextRef,
        color: Int,
    ) = CGContextSetRGBStrokeColor(context, red(color) / CHANNEL_MAX, green(color) / CHANNEL_MAX, blue(color) / CHANNEL_MAX, 1.0)
}
