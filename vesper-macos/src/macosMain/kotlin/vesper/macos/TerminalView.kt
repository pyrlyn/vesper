package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.AppKit.NSAccessibilityPostNotification
import platform.AppKit.NSAccessibilitySelectedTextChangedNotification
import platform.AppKit.NSAccessibilityTextAreaRole
import platform.AppKit.NSAccessibilityValueChangedNotification
import platform.AppKit.NSBeep
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSFont
import platform.AppKit.NSFontAttributeName
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSMarkedClauseSegmentAttributeName
import platform.AppKit.NSMenuItem
import platform.AppKit.NSMenuItemValidationProtocol
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString
import platform.AppKit.NSTextInputClientProtocol
import platform.AppKit.NSTrackingActiveInKeyWindow
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingInVisibleRect
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSUnderlineStyleAttributeName
import platform.AppKit.NSView
import platform.AppKit.NSViewLayerContentsRedrawOnSetNeedsDisplay
import platform.AppKit.NSWorkspace
import platform.AppKit.topAnchor
import platform.AppKit.trailingAnchor
import platform.AppKit.translatesAutoresizingMaskIntoConstraints
import platform.AppKit.voiceOverEnabled
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextSetTextMatrix
import platform.CoreGraphics.CGContextStrokeRect
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Foundation.NSAttributedString
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNotFound
import platform.Foundation.NSRange
import platform.Foundation.NSRangePointer
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.Foundation.create
import platform.Foundation.valueWithRange
import platform.QuartzCore.CALayer
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.getenv
import vesper.grid.Attrs
import vesper.grid.Style
import vesper.grid.Underline
import vesper.host.Bitmap
import vesper.host.CellMetrics
import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.RowSources
import vesper.host.blue
import vesper.host.cellAt
import vesper.host.clipboardText
import vesper.host.green
import vesper.host.gridSize
import vesper.host.preeditLines
import vesper.host.red
import vesper.host.windowTitle
import vesper.input.KeyAction
import vesper.input.MouseAction
import vesper.input.MouseButton
import vesper.input.MouseEvent
import vesper.pty.PtySize
import vesper.pty.SpawnOptions
import vesper.session.Session
import vesper.term.Event
import vesper.term.Frame
import vesper.term.SelectionKind
import vesper.term.TextRange
import vesper.term.ViewportText
import kotlin.math.abs

private const val CHANNEL_MAX = 255.0

// Typing waits this long before the history-wide step and count.
private const val FIND_SETTLE_SECONDS = 0.25
private const val FIND_BAR_MARGIN = 6.0

/**
 * One terminal in a window: draws the frame with CoreText, sends keys, mouse and paste to the
 * session, and follows its size. Everything here runs on the main thread; the session's wakeup
 * only posts [refresh] to the main queue.
 */
internal class TerminalView(
    private val text: TextStyle,
    frame: CValue<CGRect>,
) : NSView(frame),
    NSTextInputClientProtocol,
    NSMenuItemValidationProtocol {
    private val palette = Palette()
    private val screen = Frame()
    private var session: Session? = null
    private var grid = Cells(0, 0)
    private var focused = false
    private var lastMotion: Cells? = null
    private var scrollLines = 0.0

    /** Null without a Metal device (a VM, a CI runner): the view then draws with CoreText. */
    internal val renderer = MetalRenderer.create(text, palette)
    private val sources = RowSources()
    private var probe: ((Boolean, Double) -> Unit)? = null

    init {
        if (renderer != null) {
            wantsLayer = true
            layerContentsRedrawPolicy = NSViewLayerContentsRedrawOnSetNeedsDisplay
        }
    }

    override fun makeBackingLayer(): CALayer = renderer?.layer ?: super.makeBackingLayer()

    // AppKit never asks a CAMetalLayer to display, so updateLayer does not come by itself: the view
    // schedules its own draws, at most one per turn of the main queue. Reporting that it updates its
    // layer still keeps AppKit from drawing CoreText into the Metal layer.
    override fun wantsUpdateLayer(): Boolean = renderer != null

    override fun updateLayer() = drawMetal()

    private var drawQueued = false

    private fun redraw() {
        if (renderer == null) {
            needsDisplay = true
            return
        }
        if (drawQueued) return
        drawQueued = true
        dispatch_async(dispatch_get_main_queue()) {
            drawQueued = false
            drawMetal()
        }
    }

    private fun drawMetal() {
        val metal = renderer ?: return
        if (window == null) return
        bounds.useContents { metal.draw(screen, sources, size.width, size.height, window?.backingScaleFactor ?: 1.0, focused) }
    }

    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        redraw()
    }

    override fun viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        redraw()
    }

    /** Hands [handler] the marks of the first frame drawn after the next update (see [MetalRenderer.onNextFrame]). */
    fun probeNextFrame(handler: (presented: Boolean, time: Double) -> Unit) {
        probe = handler
    }

    /** Renders the current frame offscreen at [scale] and waits for it; null without Metal. */
    fun snapshot(scale: Double): Bitmap? =
        renderer?.let { metal -> bounds.useContents { metal.render(screen, sources, size.width, size.height, scale, focused) } }

    /** The window's title and directory follow the program; the app closes the window when the child exits. */
    var onTitle: (String) -> Unit = {}
    var onDirectory: (NSURL) -> Unit = {}
    var onExit: () -> Unit = {}

    /** Where Copy, Paste and the program's OSC 52 writes go; a test swaps in a private one. */
    internal var pasteboard: NSPasteboard = NSPasteboard.generalPasteboard

    /** The frame's text, row by row, as drawn (a preedit included); what the tests check. */
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
        if (current.update(screen)) {
            sources.absorb(screen.damage, screen.rows)
            probe?.let { renderer?.onNextFrame = it }
            probe = null
            redraw()
            screenChanged()
        }
        while (true) {
            when (val event = current.pollEvent() ?: break) {
                Event.Bell -> NSBeep()

                is Event.Title -> event.windowTitle?.let(onTitle)

                // Only a local file URL names a directory this Mac can show.
                is Event.Directory -> NSURL.URLWithString(event.uri)?.takeIf { it.fileURL }?.let(onDirectory)

                is Event.ClipboardWrite -> event.clipboardText?.let(::writeClipboard)

                else -> Unit
            }
        }
        if (current.exitStatus != null || current.failure != null) onExit()
    }

    private fun writeClipboard(string: String) {
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
        redraw()
    }

    // Size: the terminal follows the view, but only once a drag ends, with the child's output held
    // meanwhile, so a shell redraws once rather than for every step of the drag.
    override fun setFrameSize(newSize: CValue<CGSize>) {
        super.setFrameSize(newSize)
        redraw()
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

    // Keys go through the input method first: it may compose (CJK, dead keys, the accent menu), in
    // which case the key never reaches the core and the composed text goes to the child as text.
    private val input = TextInput()

    // Keys the input method took; their release is not the program's either.
    private val composedKeys = HashSet<UShort>()

    override fun keyDown(event: NSEvent) {
        input.beginKey()
        interpretKeyEvents(listOf(event))
        when (val outcome = input.endKey()) {
            KeyOutcome.Key -> {
                key(event, if (event.ARepeat) KeyAction.Repeat else KeyAction.Press)
            }

            is KeyOutcome.Text -> {
                composedKeys += event.keyCode
                commit(outcome.text)
            }

            KeyOutcome.Consumed -> {
                composedKeys += event.keyCode
            }
        }
    }

    override fun keyUp(event: NSEvent) {
        if (!composedKeys.remove(event.keyCode)) key(event, KeyAction.Release)
    }

    // A key the input method maps to a command (Return, arrows, Delete) is the core's to encode, not
    // an editing command: doing nothing here keeps AppKit from beeping for want of a handler.
    override fun doCommandBySelector(selector: COpaquePointer?) = Unit

    private fun commit(text: String) {
        session?.text(text)
        showPreedit()
    }

    /** Hands the composition to the frame, which lays it over the cursor row, and brings the view back to the cursor. */
    private fun showPreedit() {
        screen.setPreedit(input.marked, input.caret, input.clauses)
        if (input.hasMarkedText) session?.scrollDisplay(-Int.MAX_VALUE)
        refresh()
    }

    override fun insertText(
        string: Any,
        replacementRange: CValue<NSRange>,
    ) {
        val text = input.insert(plainText(string)) ?: return showPreedit()
        commit(text)
    }

    override fun setMarkedText(
        string: Any,
        selectedRange: CValue<NSRange>,
        replacementRange: CValue<NSRange>,
    ) {
        val selected = selectedRange.toTextRange()
        input.setMarked(plainText(string), selected?.start, selected?.length ?: 0, markedRuns(string))
        showPreedit()
    }

    // AppKit asks the view to accept the marked text as typed (focus lost, a click).
    override fun unmarkText() {
        if (input.hasMarkedText) insertText(input.marked, NO_RANGE)
    }

    override fun selectedRange(): CValue<NSRange> = input.selectedRange.toNSRange()

    override fun markedRange(): CValue<NSRange> = input.markedRange?.toNSRange() ?: NO_RANGE

    override fun hasMarkedText(): Boolean = input.hasMarkedText

    @OptIn(BetaInteropApi::class)
    override fun attributedSubstringForProposedRange(
        range: CValue<NSRange>,
        actualRange: NSRangePointer?,
    ): NSAttributedString? {
        val part = range.toTextRange()?.let(input::substring) ?: return null
        actualRange?.pointed?.apply {
            location = part.start.toULong()
            length = part.length.toULong()
        }
        return NSAttributedString.create(string = input.marked.substring(part.start, part.end))
    }

    // An input method only tags its clauses when the client says it reads the tags.
    override fun validAttributesForMarkedText(): List<*> = listOfNotNull(NSUnderlineStyleAttributeName, NSMarkedClauseSegmentAttributeName)

    /** The cursor cell, which the frame keeps at the composition's caret, on the screen: the candidate window opens beside it. */
    override fun firstRectForCharacterRange(
        range: CValue<NSRange>,
        actualRange: NSRangePointer?,
    ): CValue<CGRect> {
        range.toTextRange()?.let { asked ->
            actualRange?.pointed?.apply {
                location = asked.start.toULong()
                length = asked.length.toULong()
            }
        }
        val cursor = screen.cursor ?: return CGRectMake(0.0, 0.0, 0.0, 0.0)
        return screenRect(cursor.row, cursor.col, 1)
    }

    override fun characterIndexForPoint(point: CValue<CGPoint>): ULong = NSNotFound.toULong()

    /** Cells in screen coordinates, as input methods and accessibility take them. */
    internal fun screenRect(
        row: Int,
        col: Int,
        cols: Int,
        rows: Int = 1,
    ): CValue<CGRect> {
        val cell = text.cell
        val rect = CGRectMake(PADDING + col * cell.width, PADDING + row * cell.height, cols * cell.width, rows * cell.height)
        val window = window ?: return CGRectMake(0.0, 0.0, 0.0, 0.0)
        return window.convertRectToScreen(convertRect(rect, toView = null))
    }

    // Accessibility: the view is a text area over the viewport's text (see ScreenReading), so
    // VoiceOver reads the screen by character, word and line and selects through the core.

    private val viewportText get() = session?.viewportText() ?: ViewportText.EMPTY
    private var postedSelection: List<TextRange> = emptyList()

    /** Tells VoiceOver the screen changed. Skipped without it: a reader re-reads the whole text on each one. */
    private fun screenChanged() {
        if (!NSWorkspace.sharedWorkspace.voiceOverEnabled) return
        NSAccessibilityPostNotification(this, NSAccessibilityValueChangedNotification)
        // Output that rewrites the selected rows drops the selection.
        postSelectionChange()
    }

    /** Tells VoiceOver the selection changed, when it differs from the one last posted. */
    private fun postSelectionChange() {
        if (!NSWorkspace.sharedWorkspace.voiceOverEnabled) return
        val now = viewportText.selected
        if (now == postedSelection) return
        postedSelection = now
        NSAccessibilityPostNotification(this, NSAccessibilitySelectedTextChangedNotification)
    }

    override fun isAccessibilityElement(): Boolean = true

    override fun accessibilityRole(): String? = NSAccessibilityTextAreaRole

    override fun accessibilityLabel(): String? = "Terminal"

    override fun accessibilityValue(): Any? = viewportText.text

    override fun accessibilityNumberOfCharacters(): Long = viewportText.length.toLong()

    override fun accessibilityVisibleCharacterRange(): CValue<NSRange> = TextRange(0, viewportText.length).toNSRange()

    override fun accessibilitySelectedText(): String? = viewportText.selectedString

    override fun accessibilitySelectedTextRange(): CValue<NSRange> = viewportText.selectedSpan(screen.cursor).toNSRange()

    override fun accessibilitySelectedTextRanges(): List<*>? =
        viewportText.selectedRanges(screen.cursor).map {
            NSValue.valueWithRange(it.toNSRange())
        }

    /** VoiceOver selecting text: the cells of the range's characters. An empty range only moves the insertion point, so it clears the selection. */
    override fun setAccessibilitySelectedTextRange(accessibilitySelectedTextRange: CValue<NSRange>) {
        val current = session ?: return
        val cells = accessibilitySelectedTextRange.toTextRange()?.let(viewportText::cellsFor)
        if (cells == null) {
            current.selectClear()
        } else {
            val (first, last) = cells
            current.select(SelectionKind.Cell, first.row, first.col)
            current.selectExtend(last.row, last.col + last.cols - 1)
        }
        refresh()
        postSelectionChange()
    }

    override fun accessibilityInsertionPointLineNumber(): Long = (screen.cursor?.row ?: 0).coerceIn(0, viewportText.lineCount - 1).toLong()

    override fun accessibilityLineForIndex(index: Long): Long =
        viewportText.lineOf(index.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()).toLong()

    override fun accessibilityRangeForLine(line: Long): CValue<NSRange> =
        line.takeIf { it in 0..Int.MAX_VALUE }?.let { viewportText.lineRange(it.toInt()) }?.toNSRange() ?: NO_RANGE

    override fun accessibilityStringForRange(range: CValue<NSRange>): String? = range.toTextRange()?.let(viewportText::substring)

    @OptIn(BetaInteropApi::class)
    override fun accessibilityAttributedStringForRange(range: CValue<NSRange>): NSAttributedString? {
        val part = range.toTextRange()?.let(viewportText::substring) ?: return null
        val font = NSFont.userFixedPitchFontOfSize(0.0) ?: return NSAttributedString.create(string = part)
        return NSAttributedString.create(string = part, attributes = mapOf<Any?, Any?>(NSFontAttributeName to font))
    }

    override fun accessibilityFrameForRange(range: CValue<NSRange>): CValue<CGRect> {
        val box = range.toTextRange()?.let { viewportText.boxOf(it, screen.cols) } ?: return CGRectMake(0.0, 0.0, 0.0, 0.0)
        return screenRect(box.row, box.col, box.cols, box.rows)
    }

    /** The character under a point on the screen. */
    override fun accessibilityRangeForPosition(point: CValue<CGPoint>): CValue<NSRange> {
        val window = window ?: return NO_RANGE
        if (grid.cols <= 0 || grid.rows <= 0) return NO_RANGE
        val local = convertPoint(window.convertPointFromScreen(point), fromView = null)
        val at = local.useContents { cellAt(x, y, text.cell, grid) }
        return viewportText.rangeAt(at.rows, at.cols).toNSRange()
    }

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
        pasteboard.stringForType(NSPasteboardTypeString)?.let { session?.paste(it) }
    }

    /** Puts the selected text on the [pasteboard]. */
    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun copy(sender: NSObject?) {
        val selected = session?.selectionText()?.takeIf { it.isNotEmpty() } ?: return
        pasteboard.clearContents()
        pasteboard.setString(selected, NSPasteboardTypeString)
    }

    override fun validateMenuItem(menuItem: NSMenuItem): Boolean =
        when (menuItem.action) {
            NSSelectorFromString("copy:") -> session?.hasSelection == true
            NSSelectorFromString("findNext:"), NSSelectorFromString("findPrevious:") -> find.canStep
            else -> true
        }

    // Find in this pane's history. The menu's Find commands arrive through the responder chain, also
    // from the bar's own search field, which sits inside the view.
    internal val find = FindState()
    internal var findBar: FindBar? = null
        private set
    private var findSettle: NSTimer? = null

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun showFind(sender: NSObject?) {
        val bar = findBar ?: makeFindBar()
        findChanged(find.open())
        window?.makeFirstResponder(bar.searchField)
        bar.searchField.selectText(null)
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun findNext(sender: NSObject?) = findStep(forward = true)

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun findPrevious(sender: NSObject?) = findStep(forward = false)

    private fun findStep(forward: Boolean) {
        findSettle?.invalidate()
        apply(find.step(forward))
    }

    internal fun findEdited(text: String) = findChanged(find.edit(text))

    /** Runs a changed pattern's effects at once, so visible matches light up while typing, and the history-wide step and count after a pause. */
    private fun findChanged(effects: List<FindEffect>) {
        apply(effects)
        findSettle?.invalidate()
        findSettle = null
        if (!find.unsettled) return
        findSettle = NSTimer.scheduledTimerWithTimeInterval(FIND_SETTLE_SECONDS, repeats = false) { _ -> settleFind() }
    }

    internal fun settleFind() {
        findSettle?.invalidate()
        findSettle = null
        apply(find.settle())
    }

    internal fun closeFind() {
        findSettle?.invalidate()
        findSettle = null
        apply(find.close())
        findBar?.removeFromSuperview()
        findBar = null
        window?.makeFirstResponder(this)
    }

    private fun apply(effects: List<FindEffect>) {
        val current = session ?: return
        for (effect in effects) {
            when (effect) {
                is FindEffect.Search -> current.search(effect.pattern, effect.matchCase)
                is FindEffect.Step -> current.searchStep(effect.forward)
                FindEffect.Count -> find.count = current.searchCount()
            }
        }
        findBar?.show(find)
        refresh()
    }

    private fun makeFindBar(): FindBar {
        val bar = FindBar()
        bar.pattern = find.pattern
        bar.onEdit = ::findEdited
        bar.onStep = ::findStep
        bar.onToggleCase = { findChanged(find.toggleCase()) }
        bar.onClose = ::closeFind
        bar.translatesAutoresizingMaskIntoConstraints = false
        addSubview(bar)
        bar.topAnchor.constraintEqualToAnchor(topAnchor, FIND_BAR_MARGIN).active = true
        bar.trailingAnchor.constraintEqualToAnchor(trailingAnchor, -FIND_BAR_MARGIN).active = true
        findBar = bar
        return bar
    }

    // Mouse: reported to a program that asked; otherwise the left button selects and the wheel
    // scrolls the history. Shift selects even while a program tracks the mouse, as in xterm.
    private var selectionDrag: SelectionDrag? = null

    override fun mouseDown(event: NSEvent) {
        if (event.modifierFlags and NSEventModifierFlagShift == 0uL && mouse(event, MouseAction.Press, MouseButton.Left)) return
        selectionPress(event)
    }

    override fun mouseUp(event: NSEvent) {
        if (selectionDrag == null) {
            mouse(event, MouseAction.Release, MouseButton.Left)
            return
        }
        if (selectionDrag?.clearsOnRelease == true) session?.selectClear()
        selectionDrag = null
        selectionChanged()
    }

    override fun mouseDragged(event: NSEvent) {
        if (selectionDrag == null) {
            mouse(event, MouseAction.Motion, MouseButton.Left)
            return
        }
        selectionDragged(event)
    }

    private fun selectionPress(event: NSEvent) {
        val current = session ?: return
        val at = cellUnder(event) ?: return
        val flags = event.modifierFlags
        val press =
            selectionPress(
                event.clickCount.toInt(),
                shift = flags and NSEventModifierFlagShift != 0uL,
                option = flags and NSEventModifierFlagOption != 0uL,
                hasSelection = current.hasSelection,
            )
        when (press) {
            SelectionPress.Extend -> current.selectExtend(at.rows, at.cols)
            is SelectionPress.Start -> current.select(press.kind, at.rows, at.cols)
        }
        selectionDrag = SelectionDrag(at, press.clearsOnRelease)
        selectionChanged()
    }

    private fun selectionDragged(event: NSEvent) {
        val current = session ?: return
        val drag = selectionDrag ?: return
        val y = convertPoint(event.locationInWindow, fromView = null).useContents { y }
        val scroll = bounds.useContents { dragScroll(y, size.height) }
        if (scroll != 0) current.scrollDisplay(scroll)
        val at = cellUnder(event) ?: return
        if (scroll == 0 && at == drag.cell) return
        current.selectExtend(at.rows, at.cols)
        selectionDrag = SelectionDrag(at, clearsOnRelease = false)
        selectionChanged()
    }

    private fun selectionChanged() {
        refresh()
        postSelectionChange()
    }

    /** The viewport cell under [event], clamped to the grid; null before the terminal has one. */
    private fun cellUnder(event: NSEvent): Cells? {
        if (grid.cols <= 0 || grid.rows <= 0) return null
        return convertPoint(event.locationInWindow, fromView = null).useContents { cellAt(x, y, text.cell, grid) }
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
            redraw()
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
        drawCells(NSGraphicsContext.currentContext?.CGContext ?: return)
    }

    // Sprites drawn straight into the view's context, in points.
    private val spriteCell by lazy { CellMetrics.of(text.faces[0], text.cell, 1.0) }

    /** The CoreText fallback's drawing into [context], top-down in points as the flipped view's is. */
    internal fun drawCells(context: CGContextRef) {
        // The view is flipped, so text would come out upside down without this.
        CGContextSetTextMatrix(context, CGAffineTransformMakeScale(1.0, -1.0))
        fill(context, palette.background)
        bounds.useContents { CGContextFillRect(context, CGRectMake(0.0, 0.0, size.width, size.height)) }
        val cell = text.cell
        for (r in 0 until screen.rows) {
            val row = screen.row(r) ?: continue
            val top = PADDING + r * cell.height
            for (run in row.runs) {
                val bg = palette.colors(screen.style(run.style) ?: Style.DEFAULT).bg
                if (bg == palette.background) continue
                fill(context, bg)
                CGContextFillRect(context, CGRectMake(PADDING + run.col * cell.width, top, run.cols * cell.width, cell.height))
            }
            // Highlights go over the run backgrounds and cover blank cells too, which belong to no run.
            for (c in 0 until screen.cols) {
                val highlight = palette.highlight(screen.cell(r, c).flags) ?: continue
                fill(context, highlight)
                CGContextFillRect(context, CGRectMake(PADDING + c * cell.width, top, cell.width, cell.height))
            }
            for (run in row.runs) {
                val style = screen.style(run.style) ?: Style.DEFAULT
                val colors = palette.colors(style)
                val left = PADDING + run.col * cell.width
                val width = run.cols * cell.width
                if (style.attrs and Attrs.HIDDEN != 0) continue
                fill(context, colors.fg)
                stroke(context, colors.fg)
                // Box drawing and Powerline separators come from the cell's geometry, as in the Metal
                // renderer; a font's glyphs leave seams. Spaces hold their columns in the text.
                val runText = row.runText(run).map { if (isSprite(it.code)) ' ' else it }.joinToString("")
                if (runText.isNotBlank()) {
                    text.draw(context, runText, left, top + text.ascent, style.attrs and Attrs.BOLD != 0, style.attrs and Attrs.ITALIC != 0)
                }
                for (c in run.col until run.col + run.cols) {
                    drawSprite(context, screen.cell(r, c).codePoint, spriteCell, PADDING + c * cell.width, top)
                }
                if (style.underline != Underline.None) CGContextFillRect(context, CGRectMake(left, top + text.ascent + 1, width, 1.0))
                if (style.attrs and Attrs.STRIKE != 0) CGContextFillRect(context, CGRectMake(left, top + cell.height / 2, width, 1.0))
            }
        }
        drawPreedit(context)
        drawCursor(context)
    }

    private fun drawPreedit(context: CGContextRef) {
        val preedit = screen.preedit ?: return
        val cell = text.cell
        fill(context, palette.foreground)
        for (line in preeditLines(preedit)) {
            val thickness = if (line.thick) 2.0 else 1.0
            val top = PADDING + (preedit.row + 1) * cell.height - thickness
            CGContextFillRect(context, CGRectMake(PADDING + line.col * cell.width + 1, top, line.cols * cell.width - 2, thickness))
        }
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
            stroke(context, palette.background)
            val left = rect.useContents { origin.x }
            if (drawSprite(context, under.codePoint, spriteCell, left, PADDING + cursor.row * cell.height)) return
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
