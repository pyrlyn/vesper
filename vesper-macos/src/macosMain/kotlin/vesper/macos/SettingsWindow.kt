package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSColor
import platform.AppKit.NSControl
import platform.AppKit.NSGridView
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSStackView
import platform.AppKit.NSTextField
import platform.AppKit.NSUserInterfaceLayoutOrientationVertical
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.cell
import platform.AppKit.currentEditor
import platform.AppKit.labelWithString
import platform.AppKit.textFieldWithString
import platform.AppKit.widthAnchor
import platform.AppKit.wrappingLabelWithString
import platform.Foundation.NSEdgeInsetsMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject
import vesper.config.ConfigException
import vesper.config.LiveConfig
import vesper.config.Scheme
import vesper.config.Snapshot

private const val MARGIN = 20.0
private const val FIELD_WIDTH = 220.0

/**
 * The settings a person changes most, each written to the config file the moment it is committed
 * (Return, or leaving the field). The file stays the truth: a refused value shows why and changes
 * nothing, and an edit made in the file meanwhile shows up here.
 */
internal class SettingsWindow(
    private val live: LiveConfig,
) : NSObject() {
    internal val family = field("System monospace")
    internal val size = field("13")
    internal val scheme = NSPopUpButton(NSMakeRect(0.0, 0.0, FIELD_WIDTH, 0.0), pullsDown = false)
    internal val scrollback = field("10000")

    /** Why the file or the last edit was refused; empty when all is well. */
    internal val status = NSTextField.wrappingLabelWithString("")

    val window =
        NSWindow(
            NSMakeRect(0.0, 0.0, FIELD_WIDTH * 2, 0.0),
            NSWindowStyleMaskTitled or NSWindowStyleMaskClosable,
            NSBackingStoreBuffered,
            false,
        )

    init {
        scheme.addItemsWithTitles(Scheme.entries.map { it.id })
        for (control in listOf<NSControl>(family, size, scheme, scrollback)) {
            control.target = this
            control.action = NSSelectorFromString("commit:")
        }
        status.textColor = NSColor.systemRedColor
        val grid =
            NSGridView.gridViewWithViews(
                listOf(
                    listOf(label("Font family:"), family),
                    listOf(label("Font size:"), size),
                    listOf(label("Colour scheme:"), scheme),
                    listOf(label("Scrollback lines:"), scrollback),
                ),
            )
        val stack = NSStackView.stackViewWithViews(listOf(grid, status))
        stack.orientation = NSUserInterfaceLayoutOrientationVertical
        stack.edgeInsets = NSEdgeInsetsMake(MARGIN, MARGIN, MARGIN, MARGIN)
        window.title = "Settings"
        window.releasedWhenClosed = false
        window.contentView = stack
        show(live.snapshot())
    }

    /** Brings the window up with what the file holds now. */
    fun present() {
        show(live.snapshot())
        if (!window.visible) window.center()
        window.makeKeyAndOrderFront(null)
    }

    /** Shows [snapshot], leaving alone a field being typed in. */
    fun show(snapshot: Snapshot) {
        val settings = snapshot.settings
        family.showUnlessEditing(settings.font.family)
        size.showUnlessEditing(settings.font.size.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() })
        scheme.selectItemWithTitle(settings.scheme.id)
        scrollback.showUnlessEditing(settings.scrollback.toString())
        status.stringValue = snapshot.error.orEmpty()
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun commit(sender: NSObject?) {
        val (key, value) =
            when (sender) {
                // An empty family is the system's monospace font, which the file says by leaving it out.
                family -> "font.family" to family.stringValue.trim().ifEmpty { null }

                size -> "font.size" to size.stringValue

                scheme -> "colors.scheme" to scheme.titleOfSelectedItem

                scrollback -> "scrollback" to scrollback.stringValue

                else -> return
            }
        try {
            live.set(key, value)
            show(live.snapshot())
        } catch (e: ConfigException) {
            status.stringValue = e.message.orEmpty()
        }
    }
}

private fun field(placeholder: String): NSTextField =
    NSTextField.textFieldWithString("").also {
        it.placeholderString = placeholder
        it.widthAnchor.constraintEqualToConstant(FIELD_WIDTH).active = true
        it.cell?.sendsActionOnEndEditing = true
    }

private fun label(text: String): NSTextField = NSTextField.labelWithString(text)

private fun NSTextField.showUnlessEditing(text: String) {
    if (currentEditor() == null) stringValue = text
}
