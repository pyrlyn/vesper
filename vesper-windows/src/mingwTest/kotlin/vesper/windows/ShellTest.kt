package vesper.windows

import kotlinx.cinterop.convert
import platform.windows.MapVirtualKeyW
import platform.windows.SendMessageW
import platform.windows.Sleep
import platform.windows.VkKeyScanW
import platform.windows.WM_CHAR
import platform.windows.WM_KEYDOWN
import platform.windows.WM_KEYUP
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MAPVK_VK_TO_VSC = 0u
private const val VK_RETURN = 0x0D
private const val RELEASED = (1L shl 30) or (1L shl 31)

/** A shell in a hidden window, typed into through the window's own key messages. */
class ShellTest {
    private fun App.waitFor(
        what: String,
        done: (String) -> Boolean,
    ) {
        val deadline = TimeSource.Monotonic.markNow() + 20.seconds
        while (!done(screenText())) {
            assertTrue(deadline.hasNotPassedNow(), "$what; the screen shows:\n${screenText()}")
            Window.pumpMessages()
            Sleep(10u)
        }
    }

    /** One key as Windows sends it: down, the character TranslateMessage makes of it, up. */
    private fun App.press(
        vk: Int,
        char: Char,
    ) {
        val hwnd = window.hwnd
        val lParam = 1L or (MapVirtualKeyW(vk.convert(), MAPVK_VK_TO_VSC).toLong() shl 16)
        SendMessageW(hwnd, WM_KEYDOWN.convert(), vk.convert(), lParam)
        SendMessageW(hwnd, WM_CHAR.convert(), char.code.convert(), lParam)
        SendMessageW(hwnd, WM_KEYUP.convert(), vk.convert(), lParam or RELEASED)
    }

    private fun App.type(text: String) {
        // The test runs on a US layout, where none of these characters needs Shift.
        for (char in text) press(VkKeyScanW(char.code.convert()).toInt() and 0xFF, char)
    }

    @Test
    fun keysTypedIntoTheWindowReachTheShellAndItsOutputReachesTheScreen() {
        Gpu.create(warp = true).use { gpu ->
            App(gpu, quitOnClose = false).use { app ->
                // No AutoRun commands from the registry, so the prompt is cmd's own.
                app.start("cmd.exe", listOf("/d"))
                app.waitFor("no prompt") { it.contains(">") }
                app.type("echo vesper")
                app.press(VK_RETURN, '\r')
                // The command line, then what it printed on a line of its own.
                app.waitFor("no echo") { screen -> screen.lines().any { it.trimEnd() == "vesper" } }
                app.paint()
            }
        }
    }
}
