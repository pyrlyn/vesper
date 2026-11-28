package vesper.windows

import vesper.input.Key
import vesper.input.KeyAction
import vesper.input.KeyEvent
import vesper.input.ModifierKey
import vesper.input.Modifiers
import vesper.input.MouseAction
import vesper.input.MouseButton
import vesper.input.MouseEvent
import vesper.input.typesText
import vesper.input.windowsKey

// Windows input in the core's vocabulary, from the plain numbers window messages carry, so it is
// tested on every runner. The policy is the C# app's (eaeb20f `windows/Scull.Core/WindowsInput.cs`).

private const val VK_SHIFT = 0x10
private const val VK_CONTROL = 0x11
private const val VK_MENU = 0x12
private const val VK_LWIN = 0x5B
private const val VK_RWIN = 0x5C
private const val VK_CAPITAL = 0x14
private const val VK_NUMLOCK = 0x90

// GetKeyState: the high bit while the key is down, the low bit while its lock is on.
private const val DOWN = 0x8000
private const val LOCKED = 1

private val HELD_KEYS =
    listOf(
        Triple(VK_SHIFT, DOWN, Modifiers.SHIFT),
        Triple(VK_CONTROL, DOWN, Modifiers.CTRL),
        Triple(VK_MENU, DOWN, Modifiers.ALT),
        Triple(VK_LWIN, DOWN, Modifiers.SUPER),
        Triple(VK_RWIN, DOWN, Modifiers.SUPER),
        Triple(VK_CAPITAL, LOCKED, Modifiers.CAPS_LOCK),
        Triple(VK_NUMLOCK, LOCKED, Modifiers.NUM_LOCK),
    )

private val SHORTCUT = Modifiers.SHIFT + Modifiers.ALT + Modifiers.CTRL + Modifiers.SUPER
private val ALT_GR = Modifiers.CTRL + Modifiers.ALT
private val CHORD = ALT_GR + Modifiers.SUPER

/** The modifiers held, given each virtual key's `GetKeyState`. AltGr shows as Ctrl+Alt, which [KeyPairing] relies on. */
fun heldModifiers(state: (Int) -> Int): Modifiers =
    HELD_KEYS.fold(Modifiers.NONE) { held, (vk, bit, modifier) -> if (state(vk) and bit != 0) held + modifier else held }

/** Whether [key] with [held] opens the settings window: Ctrl+comma, as in Windows Terminal. */
fun isSettings(
    key: Key?,
    held: Modifiers,
): Boolean = key == Key.Character(','.code) && Modifiers.of(held.bits and SHORTCUT.bits) == Modifiers.CTRL

/** The modifier a modifier key holds, which the core counts as held during its own event. */
private fun modifierOf(key: Key): Modifiers =
    when ((key as? Key.Modifier)?.key) {
        ModifierKey.LeftShift, ModifierKey.RightShift -> Modifiers.SHIFT
        ModifierKey.LeftControl, ModifierKey.RightControl -> Modifiers.CTRL
        ModifierKey.LeftAlt, ModifierKey.RightAlt -> Modifiers.ALT
        ModifierKey.LeftSuper, ModifierKey.RightSuper -> Modifiers.SUPER
        else -> Modifiers.NONE
    }

/**
 * Pairs `WM_KEYDOWN` with the `WM_CHAR` that follows it, because a key event carries the text the key
 * typed and Windows reports the two apart. A text key waits for its character. Control, Alt, Super
 * and keys that type nothing go out at once; the characters Windows makes of them (Ctrl+A's 0x01,
 * Enter's CR) are the core's to make, so they are dropped. Ctrl+Alt is AltGr when a character
 * follows, which then counts both as used up.
 */
class KeyPairing(
    private val sendKey: (KeyEvent) -> Unit,
    private val sendText: (String) -> Unit,
) {
    private var pending: KeyEvent? = null
    private var dropping = false
    private var high: Char? = null

    /** A key went down. True when it was sent; false while it waits for its character or names no key. */
    fun keyDown(
        vk: Int,
        scan: Int,
        extended: Boolean,
        repeat: Boolean,
        held: Modifiers,
    ): Boolean {
        flush()
        dropping = false
        // A key with no name here types, if anything, a character that goes out on its own as text.
        val key = windowsKey(vk, scan, extended) ?: return false
        val event = KeyEvent(key, if (repeat) KeyAction.Repeat else KeyAction.Press, held + modifierOf(key))
        val chord = Modifiers.of(event.mods.bits and CHORD.bits)
        if (key.typesText && (chord.isEmpty() || chord == ALT_GR)) {
            pending = event
            return false
        }
        sendKey(event)
        dropping = true
        return true
    }

    /** A key went up; true when a release was sent. */
    fun keyUp(
        vk: Int,
        scan: Int,
        extended: Boolean,
        held: Modifiers,
    ): Boolean {
        flush()
        val key = windowsKey(vk, scan, extended) ?: return false
        sendKey(KeyEvent(key, KeyAction.Release, held + modifierOf(key)))
        return true
    }

    /** One UTF-16 unit of `WM_CHAR`; a character outside the BMP arrives as two. */
    fun character(unit: Char) {
        if (unit.isHighSurrogate()) {
            high = unit
            return
        }
        val text =
            if (unit.isLowSurrogate()) {
                val first = high ?: return
                "$first$unit"
            } else {
                unit.toString()
            }
        high = null
        if (dropping || unit.isISOControl()) return
        val key = pending
        if (key != null) {
            pending = null
            sendKey(key.copy(text = text, consumed = Modifiers.of(key.mods.bits and ALT_GR.bits)))
        } else {
            sendText(text)
        }
    }

    // A text key that saw no character is a dead key waiting for the next one, whose character
    // comes with that key, unless Ctrl+Alt made it a chord.
    private fun flush() {
        val key = pending
        if (key != null && ALT_GR in key.mods) sendKey(key)
        pending = null
        high = null
    }
}

/**
 * Which key presses the host ran as its own command (paste) instead of sending them, so the release
 * that follows is not sent either: a program must not see the release of a key it never saw go down.
 */
class SwallowedKeys {
    private val keys = HashSet<Int>()

    /** Virtual key [vk] went down; [swallowed] when the host ran it. A press that is sent forgets a release owed from before. */
    fun press(
        vk: Int,
        swallowed: Boolean,
    ) {
        if (swallowed) keys.add(vk) else keys.remove(vk)
    }

    /** Virtual key [vk] went up; true when its press was swallowed, so the release is too. */
    fun release(vk: Int): Boolean = keys.remove(vk)
}

/** Windows' wheel notch, `WHEEL_DELTA`. */
const val NOTCH = 120

/** `WHEEL_PAGESCROLL`: the system's lines per notch when the user chose a screenful. */
const val PAGE_SCROLL = -1

/**
 * Wheel deltas to whole steps and to rows of history. A notch is 120 and a precision touchpad
 * reports a fraction of one per message, so what is left over carries to the next.
 */
class Wheel {
    private var steps = 0
    private var rows = 0L

    /** Wheel button presses for [delta]: positive away from the user (up, or right when horizontal). */
    fun steps(delta: Int): Int {
        val total = steps.toLong() + delta
        steps = (total % NOTCH).toInt()
        return (total / NOTCH).toInt()
    }

    /**
     * The rows (positive: back into history) [delta] scrolls at [linesPerNotch] lines a notch, or
     * [pageRows] for [PAGE_SCROLL]. Zero lines is the user turning wheel scrolling off.
     */
    fun rows(
        delta: Int,
        linesPerNotch: Int,
        pageRows: Int,
    ): Int {
        val perNotch = if (linesPerNotch == PAGE_SCROLL) pageRows.coerceAtLeast(0) else linesPerNotch.coerceAtLeast(0)
        if (perNotch == 0) {
            rows = 0
            return 0
        }
        val total = rows + delta.toLong() * perNotch
        rows = total % NOTCH
        return (total / NOTCH).coerceIn(-Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * Mouse buttons and moves to the core's events. Moves within one cell are dropped, as programs
 * only see cells; a lost capture owes a release for every button still held.
 */
class Pointer {
    private var last: Pair<Int, Int>? = null
    private var lastAt = MouseEvent(MouseAction.Motion, null, col = 0, row = 0)
    private val held = LinkedHashSet<MouseButton>()

    /** [button] went down or up at cell ([col], [row]) and pixel ([xPx], [yPx]) of the text area. */
    fun button(
        button: MouseButton,
        pressed: Boolean,
        mods: Modifiers,
        col: Int,
        row: Int,
        xPx: Int,
        yPx: Int,
    ): MouseEvent {
        if (pressed) held.add(button) else held.remove(button)
        return at(MouseEvent(if (pressed) MouseAction.Press else MouseAction.Release, button, mods, col, row, xPx, yPx))
    }

    /** The pointer moved; null while it stays in the cell of the last event. */
    fun move(
        mods: Modifiers,
        col: Int,
        row: Int,
        xPx: Int,
        yPx: Int,
    ): MouseEvent? {
        if (last == (col to row)) return null
        // Motion names the lowest button held, as xterm reports a drag.
        val button = listOf(MouseButton.Left, MouseButton.Middle, MouseButton.Right).firstOrNull { it in held }
        return at(MouseEvent(MouseAction.Motion, button, mods, col, row, xPx, yPx))
    }

    /** One wheel step in the direction of [steps]' sign: a press of a wheel button. */
    fun wheel(
        steps: Int,
        horizontal: Boolean,
        mods: Modifiers,
        col: Int,
        row: Int,
        xPx: Int,
        yPx: Int,
    ): MouseEvent {
        val button =
            when {
                horizontal && steps > 0 -> MouseButton.WheelRight
                horizontal -> MouseButton.WheelLeft
                steps > 0 -> MouseButton.WheelUp
                else -> MouseButton.WheelDown
            }
        return at(MouseEvent(MouseAction.Press, button, mods, col, row, xPx, yPx))
    }

    /** Whether any button is down, so the window keeps the mouse captured. */
    val anyHeld: Boolean get() = held.isNotEmpty()

    /** The capture was lost: one release for each button still held, at the last position, so none sticks. */
    fun lose(mods: Modifiers): List<MouseEvent> {
        val releases = held.map { lastAt.copy(action = MouseAction.Release, button = it, mods = mods) }
        held.clear()
        return releases
    }

    private fun at(event: MouseEvent): MouseEvent {
        last = event.col to event.row
        lastAt = event
        return event
    }
}
