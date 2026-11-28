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
import vesper.config.LiveConfig
import vesper.config.Scheme
import vesper.config.Snapshot
import vesper.host.SettingField

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
                    listOf(label(SettingField.FontFamily), family),
                    listOf(label(SettingField.FontSize), size),
                    listOf(label(SettingField.ColorScheme), scheme),
                    listOf(label(SettingField.Scrollback), scrollback),
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
        family.showUnlessEditing(SettingField.FontFamily.shown(settings))
        size.showUnlessEditing(SettingField.FontSize.shown(settings))
        scheme.selectItemWithTitle(SettingField.ColorScheme.shown(settings))
        scrollback.showUnlessEditing(SettingField.Scrollback.shown(settings))
        status.stringValue = snapshot.error.orEmpty()
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun commit(sender: NSObject?) {
        val (field, text) =
            when (sender) {
                family -> SettingField.FontFamily to family.stringValue
                size -> SettingField.FontSize to size.stringValue
                scheme -> SettingField.ColorScheme to scheme.titleOfSelectedItem.orEmpty()
                scrollback -> SettingField.Scrollback to scrollback.stringValue
                else -> return
            }
        val refused = field.commit(text, live.snapshot().settings, live::set)
        if (refused == null) show(live.snapshot()) else status.stringValue = refused
    }
}

private fun field(placeholder: String): NSTextField =
    NSTextField.textFieldWithString("").also {
        it.placeholderString = placeholder
        it.widthAnchor.constraintEqualToConstant(FIELD_WIDTH).active = true
        it.cell?.sendsActionOnEndEditing = true
    }

private fun label(field: SettingField): NSTextField = NSTextField.labelWithString(field.label)

private fun NSTextField.showUnlessEditing(text: String) {
    if (currentEditor() == null) stringValue = text
}
