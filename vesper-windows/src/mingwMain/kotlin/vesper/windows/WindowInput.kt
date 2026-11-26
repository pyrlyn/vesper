package vesper.windows

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.GetKeyState
import platform.windows.HWND
import platform.windows.POINT
import platform.windows.ReleaseCapture
import platform.windows.SPI_GETWHEELSCROLLLINES
import platform.windows.ScreenToClient
import platform.windows.SetCapture
import platform.windows.SystemParametersInfoW
import platform.windows.UINTVar
import platform.windows.WM_CAPTURECHANGED
import platform.windows.WM_CHAR
import platform.windows.WM_KEYDOWN
import platform.windows.WM_KEYUP
import platform.windows.WM_KILLFOCUS
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_MBUTTONDOWN
import platform.windows.WM_MBUTTONUP
import platform.windows.WM_MOUSEHWHEEL
import platform.windows.WM_MOUSEMOVE
import platform.windows.WM_MOUSEWHEEL
import platform.windows.WM_RBUTTONDOWN
import platform.windows.WM_RBUTTONUP
import platform.windows.WM_SETFOCUS
import platform.windows.WM_SYSCHAR
import platform.windows.WM_SYSKEYDOWN
import platform.windows.WM_SYSKEYUP
import platform.windows.WM_XBUTTONDOWN
import platform.windows.WM_XBUTTONUP
import vesper.input.Key
import vesper.input.Modifiers
import vesper.input.MouseButton
import vesper.input.windowsKey
import vesper.session.Session
import kotlin.math.abs

private const val SCAN_SHIFT = 16
private const val EXTENDED_BIT = 24
private const val REPEAT_BIT = 30
private const val XBUTTON1 = 1
private const val DEFAULT_WHEEL_LINES = 3

/** Where a client-area point lands: its cell, and its pixel in the text area for SGR-pixel reports. */
data class Place(
    val col: Int,
    val row: Int,
    val xPx: Int,
    val yPx: Int,
)

/** What [WindowInput] needs from the terminal it types into. */
interface InputTarget {
    val session: Session?

    /** The place of client point ([x], [y]) in physical pixels. */
    fun place(
        x: Int,
        y: Int,
    ): Place

    /** The rows on screen, which a page of wheel scrolling moves. */
    val pageRows: Int

    /** The view scrolled through the history; the frame needs reading again. */
    fun scrolled()

    fun focusChanged(focused: Boolean)
}

/**
 * A window's keyboard, mouse and focus messages to the session: keys with the text `WM_CHAR` brings,
 * the paste shortcuts, buttons with the mouse captured while one is held, and the wheel, which goes
 * to a program that reports the mouse and otherwise scrolls the history.
 */
internal class WindowInput(
    private val hwnd: HWND,
    private val target: InputTarget,
) {
    private val pairing = KeyPairing({ target.session?.key(it) }, { target.session?.text(it) })
    private val swallowed = SwallowedKeys()
    private val pointer = Pointer()
    private val vertical = Wheel()
    private val horizontal = Wheel()

    // Whether the last whole wheel step went to the program, for the fractions in between.
    private var wheelTaken = false

    /** Handles [message] when it is input; false for any other. */
    fun handle(
        message: Int,
        wParam: ULong,
        lParam: Long,
    ): Boolean {
        val session = target.session ?: return false
        when (message) {
            WM_KEYDOWN, WM_SYSKEYDOWN -> {
                return keyDown(session, wParam.toInt(), lParam)
            }

            WM_KEYUP, WM_SYSKEYUP -> {
                keyUp(wParam.toInt(), lParam)
            }

            // Handled, so the default handler neither beeps at Alt+key nor opens the system menu.
            WM_CHAR, WM_SYSCHAR -> {
                pairing.character(wParam.toInt().toChar())
            }

            WM_SETFOCUS, WM_KILLFOCUS -> {
                focus(session, message == WM_SETFOCUS)
            }

            WM_LBUTTONDOWN, WM_LBUTTONUP -> {
                button(session, MouseButton.Left, message == WM_LBUTTONDOWN, lParam)
            }

            WM_RBUTTONDOWN, WM_RBUTTONUP -> {
                button(session, MouseButton.Right, message == WM_RBUTTONDOWN, lParam)
            }

            WM_MBUTTONDOWN, WM_MBUTTONUP -> {
                button(session, MouseButton.Middle, message == WM_MBUTTONDOWN, lParam)
            }

            WM_XBUTTONDOWN, WM_XBUTTONUP -> {
                val button = if (hiWord(wParam.toLong()) == XBUTTON1) MouseButton.Back else MouseButton.Forward
                button(session, button, message == WM_XBUTTONDOWN, lParam)
            }

            WM_MOUSEMOVE -> {
                val at = place(lParam)
                pointer.move(held(), at.col, at.row, at.xPx, at.yPx)?.let(session::mouse)
            }

            WM_MOUSEWHEEL, WM_MOUSEHWHEEL -> {
                wheel(session, message == WM_MOUSEHWHEEL, wParam, lParam)
            }

            // Released elsewhere or taken by another window: the buttons still held never come up here.
            WM_CAPTURECHANGED -> {
                pointer.lose(held()).forEach { session.mouse(it) }
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun keyDown(
        session: Session,
        vk: Int,
        lParam: Long,
    ): Boolean {
        val scan = ((lParam shr SCAN_SHIFT) and 0xFF).toInt()
        val extended = lParam and (1L shl EXTENDED_BIT) != 0L
        val held = held()
        val key = windowsKey(vk, scan, extended)
        // Alt+F4 closes the window, as everywhere on Windows: the default handler turns it into SC_CLOSE.
        if (key == Key.F(4) && Modifiers.ALT in held && !held.intersects(Modifiers.CTRL + Modifiers.SHIFT)) return false
        val paste = isPaste(key, held)
        swallowed.press(vk, paste)
        if (paste) {
            readClipboardText(hwnd)?.let(session::paste)
        } else {
            pairing.keyDown(vk, scan, extended, lParam and (1L shl REPEAT_BIT) != 0L, held)
        }
        return true
    }

    private fun keyUp(
        vk: Int,
        lParam: Long,
    ) {
        if (swallowed.release(vk)) return
        val scan = ((lParam shr SCAN_SHIFT) and 0xFF).toInt()
        pairing.keyUp(vk, scan, lParam and (1L shl EXTENDED_BIT) != 0L, held())
    }

    private fun focus(
        session: Session,
        focused: Boolean,
    ) {
        session.focus(focused)
        target.focusChanged(focused)
    }

    private fun button(
        session: Session,
        button: MouseButton,
        pressed: Boolean,
        lParam: Long,
    ) {
        val at = place(lParam)
        session.mouse(pointer.button(button, pressed, held(), at.col, at.row, at.xPx, at.yPx))
        // Captured while a button is down, so a drag past the window's edge still reaches the program.
        if (pressed) {
            SetCapture(hwnd)
        } else if (!pointer.anyHeld) {
            ReleaseCapture()
        }
    }

    private fun wheel(
        session: Session,
        sideways: Boolean,
        wParam: ULong,
        lParam: Long,
    ) {
        val delta = hiWord(wParam.toLong()).toShort().toInt()
        val wheel = if (sideways) horizontal else vertical
        val steps = wheel.steps(delta)
        val at = screenPlace(lParam)
        if (steps != 0) {
            val event = { pointer.wheel(steps, sideways, held(), at.col, at.row, at.xPx, at.yPx) }
            wheelTaken = session.mouse(event())
            if (wheelTaken) repeat(abs(steps) - 1) { session.mouse(event()) }
        }
        if (wheelTaken || sideways) return
        val rows = wheel.rows(delta, wheelLines(), target.pageRows)
        if (rows == 0) return
        session.scrollDisplay(rows)
        target.scrolled()
    }

    private fun place(lParam: Long): Place = target.place(loWord(lParam).toShort().toInt(), hiWord(lParam).toShort().toInt())

    // Wheel messages carry the pointer in screen coordinates.
    private fun screenPlace(lParam: Long): Place =
        memScoped {
            val point = alloc<POINT>()
            point.x = loWord(lParam).toShort().toInt()
            point.y = hiWord(lParam).toShort().toInt()
            ScreenToClient(hwnd, point.ptr)
            target.place(point.x, point.y)
        }

    private fun held(): Modifiers = heldModifiers { GetKeyState(it).toInt() and 0xFFFF }
}

/** The user's lines per wheel notch, [PAGE_SCROLL] for a screenful. */
private fun wheelLines(): Int =
    memScoped {
        val lines = alloc<UINTVar>()
        if (SystemParametersInfoW(SPI_GETWHEELSCROLLLINES.toUInt(), 0u, lines.ptr, 0u) == 0) DEFAULT_WHEEL_LINES else lines.value.toInt()
    }
