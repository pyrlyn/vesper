package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSApplication
import platform.AppKit.NSBezelStyleAccessoryBarAction
import platform.AppKit.NSButton
import platform.AppKit.NSButtonTypePushOnPushOff
import platform.AppKit.NSColor
import platform.AppKit.NSControl
import platform.AppKit.NSControlStateValueOff
import platform.AppKit.NSControlStateValueOn
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSFont
import platform.AppKit.NSImage
import platform.AppKit.NSSearchField
import platform.AppKit.NSSearchFieldDelegateProtocol
import platform.AppKit.NSStackView
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.AppKit.NSVisualEffectBlendingMode
import platform.AppKit.NSVisualEffectMaterialPopover
import platform.AppKit.NSVisualEffectState
import platform.AppKit.NSVisualEffectView
import platform.AppKit.bottomAnchor
import platform.AppKit.currentEvent
import platform.AppKit.labelWithString
import platform.AppKit.leadingAnchor
import platform.AppKit.topAnchor
import platform.AppKit.trailingAnchor
import platform.AppKit.translatesAutoresizingMaskIntoConstraints
import platform.AppKit.widthAnchor
import platform.Foundation.NSEdgeInsetsMake
import platform.Foundation.NSNotification
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject
import vesper.host.FindState

private const val FIELD_WIDTH = 180.0
private const val CORNER_RADIUS = 8.0
private const val SPACING = 6.0
private const val INSET_V = 6.0
private const val INSET_H = 8.0

/**
 * The find bar over the terminal's top-right corner. It reports what the user did; the terminal view
 * owns the [FindState] and shows it back through [show]. Ported from the removed Swift host's `FindBar`.
 */
internal class FindBar :
    NSVisualEffectView(platform.Foundation.NSMakeRect(0.0, 0.0, 0.0, 0.0)),
    NSSearchFieldDelegateProtocol {
    var onEdit: (String) -> Unit = {}
    var onStep: (forward: Boolean) -> Unit = {}
    var onToggleCase: () -> Unit = {}
    var onClose: () -> Unit = {}

    val searchField = NSSearchField()
    private val status = NSTextField.labelWithString("")
    private val caseButton = NSButton()

    init {
        material = NSVisualEffectMaterialPopover
        blendingMode = NSVisualEffectBlendingMode.NSVisualEffectBlendingModeWithinWindow
        state = NSVisualEffectState.NSVisualEffectStateActive
        wantsLayer = true
        layer?.cornerRadius = CORNER_RADIUS
        searchField.placeholderString = "Find"
        searchField.delegate = this
        searchField.widthAnchor.constraintEqualToConstant(FIELD_WIDTH).active = true
        status.font = NSFont.monospacedDigitSystemFontOfSize(NSFont.smallSystemFontSize, 0.0)
        status.textColor = NSColor.secondaryLabelColor
        caseButton.title = "Aa"
        caseButton.setButtonType(NSButtonTypePushOnPushOff)
        caseButton.bezelStyle = NSBezelStyleAccessoryBarAction
        caseButton.toolTip = "Match Case"
        caseButton.target = this
        caseButton.action = NSSelectorFromString("toggleCase:")
        val previous = button("chevron.up", "Previous", "previous:")
        val next = button("chevron.down", "Next", "next:")
        val done = button("xmark.circle.fill", "Close", "close:")
        val stack = NSStackView.stackViewWithViews(listOf(searchField, caseButton, status, previous, next, done))
        stack.spacing = SPACING
        stack.edgeInsets = NSEdgeInsetsMake(INSET_V, INSET_H, INSET_V, INSET_H)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        stack.leadingAnchor.constraintEqualToAnchor(leadingAnchor).active = true
        stack.trailingAnchor.constraintEqualToAnchor(trailingAnchor).active = true
        stack.topAnchor.constraintEqualToAnchor(topAnchor).active = true
        stack.bottomAnchor.constraintEqualToAnchor(bottomAnchor).active = true
    }

    private fun button(
        symbol: String,
        label: String,
        action: String,
    ): NSButton {
        val image = NSImage.imageWithSystemSymbolName(symbol, label) ?: NSImage()
        return NSButton.buttonWithImage(image, this, NSSelectorFromString(action)).also {
            it.bordered = false
            it.toolTip = label
        }
    }

    var pattern: String
        get() = searchField.stringValue
        set(value) {
            searchField.stringValue = value
        }

    /** The match count and the match-case toggle as [find] has them. */
    fun show(find: FindState) {
        status.stringValue = find.label
        caseButton.state = if (find.matchCase) NSControlStateValueOn else NSControlStateValueOff
    }

    /** The count label, for tests. */
    val statusText: String get() = status.stringValue

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun toggleCase(sender: NSObject?) = onToggleCase()

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun previous(sender: NSObject?) = onStep(false)

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun next(sender: NSObject?) = onStep(true)

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun close(sender: NSObject?) = onClose()

    override fun controlTextDidChange(obj: NSNotification) = onEdit(searchField.stringValue)

    override fun control(
        control: NSControl,
        textView: NSTextView,
        doCommandBySelector: COpaquePointer?,
    ): Boolean {
        when (doCommandBySelector) {
            NSSelectorFromString("insertNewline:") -> {
                val shift = (NSApplication.sharedApplication.currentEvent?.modifierFlags ?: 0uL) and NSEventModifierFlagShift != 0uL
                onStep(!shift)
            }

            // Escape ends the search outright; the field would only clear itself.
            NSSelectorFromString("cancelOperation:") -> {
                onClose()
            }

            else -> {
                return false
            }
        }
        return true
    }
}
