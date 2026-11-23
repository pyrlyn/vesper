package scull.term

import scull.input.ByteSink
import scull.input.MouseAction
import scull.input.MouseButton
import scull.input.MouseEncoding
import scull.input.MouseEvent
import scull.input.MouseModes
import scull.input.MouseTracking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputTest {
    private fun term() = Terminal(10, 4, 10)

    private fun Terminal.replies(text: String): String {
        feed(text)
        return takeReplies().decodeToString()
    }

    private fun Terminal.mouse(
        action: MouseAction,
        button: MouseButton,
    ): Pair<Boolean, String> {
        val out = ByteSink()
        val taken = encodeMouse(MouseEvent(action, button, col = 2, row = 1, xPx = 20, yPx = 17), out)
        return taken to out.toByteArray().decodeToString()
    }

    private fun Terminal.paste(text: String): String {
        val out = ByteSink()
        encodePaste(text, out)
        return out.toByteArray().decodeToString()
    }

    @Test
    fun mouseModesAreKeptAndReported() {
        val t = term()
        val trackings =
            listOf(9 to MouseTracking.X10, 1000 to MouseTracking.Normal, 1002 to MouseTracking.ButtonEvent, 1003 to MouseTracking.AnyEvent)
        for ((mode, tracking) in trackings) {
            t.feed("\u001b[?${mode}h")
            assertEquals(tracking, t.mouseModes().tracking)
        }
        assertEquals("\u001b[?1003;1\$y", t.replies("\u001b[?1003\$p"))
        assertEquals("\u001b[?1000;2\$y", t.replies("\u001b[?1000\$p"))

        val encodings =
            listOf(1005 to MouseEncoding.Utf8, 1015 to MouseEncoding.Urxvt, 1016 to MouseEncoding.SgrPixels, 1006 to MouseEncoding.Sgr)
        for ((mode, encoding) in encodings) {
            t.feed("\u001b[?${mode}h")
            assertEquals(encoding, t.mouseModes().encoding)
        }
        assertEquals("\u001b[?1006;1\$y", t.replies("\u001b[?1006\$p"))
        assertEquals("\u001b[?1016;2\$y", t.replies("\u001b[?1016\$p"))

        // Resetting a mode other than the active one still turns tracking off, as in xterm.
        t.feed("\u001b[?1000l\u001b[?1015l")
        assertEquals(MouseModes(), t.mouseModes())
    }

    @Test
    fun aTrackedMouseIsEncodedInTheChosenEncoding() {
        val t = term()
        assertEquals(false to "", t.mouse(MouseAction.Press, MouseButton.Left))
        t.feed("\u001b[?1000h\u001b[?1006h")
        assertEquals(true to "\u001b[<0;3;2M", t.mouse(MouseAction.Press, MouseButton.Left))
        t.feed("\u001b[?1016h")
        assertEquals(true to "\u001b[<0;21;18m", t.mouse(MouseAction.Release, MouseButton.Left))
    }

    @Test
    fun theWheelSendsCursorKeysOnTheAlternateScreenUntil1007IsReset() {
        val t = term()
        assertEquals("\u001b[?1007;1\$y", t.replies("\u001b[?1007\$p"))
        assertFalse(t.mouse(MouseAction.Press, MouseButton.WheelUp).first, "the main screen scrolls the history")
        t.feed("\u001b[?1049h")
        assertEquals(true to "\u001b[A", t.mouse(MouseAction.Press, MouseButton.WheelUp))
        t.feed("\u001b[?1h")
        assertEquals(true to "\u001bOB", t.mouse(MouseAction.Press, MouseButton.WheelDown))
        assertFalse(t.mouse(MouseAction.Press, MouseButton.Left).first)
        assertFalse(t.mouse(MouseAction.Release, MouseButton.WheelUp).first)
        t.feed("\u001b[?1007l")
        assertFalse(t.mouse(MouseAction.Press, MouseButton.WheelUp).first)
    }

    @Test
    fun pasteIsBracketedOnlyWhileAsked() {
        val t = term()
        assertEquals("a\rb", t.paste("a\nb"))
        t.feed("\u001b[?2004h")
        assertEquals("\u001b[200~xy\u001b[201~", t.paste("x\u001b[201~y"))
    }

    @Test
    fun focusIsReportedOnlyWhileAsked() {
        val t = term()
        val out = ByteSink()
        t.encodeFocus(true, out)
        assertTrue(out.isEmpty())
        t.feed("\u001b[?1004h")
        t.encodeFocus(true, out)
        t.encodeFocus(false, out)
        assertEquals("\u001b[I\u001b[O", out.toByteArray().decodeToString())
    }

    @Test
    fun aFullResetForgetsTheMouseModes() {
        val t = term()
        t.feed("\u001b[?1003h\u001b[?1006h\u001b[?1007l")
        t.feed("\u001bc")
        assertEquals(MouseModes(), t.mouseModes())
        assertEquals("\u001b[?1007;1\$y", t.replies("\u001b[?1007\$p"))
    }
}
