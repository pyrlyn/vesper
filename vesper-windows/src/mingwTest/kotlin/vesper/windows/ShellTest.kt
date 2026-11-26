package vesper.windows

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.toLong
import platform.windows.GetWindowRect
import platform.windows.GetWindowTextW
import platform.windows.MapVirtualKeyW
import platform.windows.RECT
import platform.windows.SendMessageW
import platform.windows.Sleep
import platform.windows.VkKeyScanW
import platform.windows.WM_CHAR
import platform.windows.WM_DPICHANGED
import platform.windows.WM_ENTERSIZEMOVE
import platform.windows.WM_EXITSIZEMOVE
import platform.windows.WM_KEYDOWN
import platform.windows.WM_KEYUP
import platform.windows.WM_SIZING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MAPVK_VK_TO_VSC = 0u
private const val VK_RETURN = 0x0D
private const val RELEASED = (1L shl 30) or (1L shl 31)
private const val WMSZ_BOTTOMRIGHT = 8
private const val TITLE_CHARS = 256

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

    private fun App.title(): String =
        memScoped {
            val buffer = allocArray<UShortVar>(TITLE_CHARS)
            GetWindowTextW(window.hwnd, buffer, TITLE_CHARS)
            buffer.toKStringFromUtf16()
        }

    private fun App.command(line: String) {
        type(line)
        press(VK_RETURN, '\r')
    }

    private fun withShell(test: (App) -> Unit) {
        Gpu.create(warp = true).use { gpu ->
            App(gpu, quitOnClose = false).use { app ->
                // No AutoRun commands from the registry, so the prompt is cmd's own.
                app.start("cmd.exe", listOf("/d"))
                app.waitFor("no prompt") { it.contains(">") }
                test(app)
            }
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
        withShell { app ->
            app.command("echo vesper")
            // The command line, then what it printed on a line of its own.
            app.waitFor("no echo") { screen -> screen.lines().any { it.trimEnd() == "vesper" } }
            app.paint()
        }
    }

    @Test
    fun theShellsTitleBecomesTheWindows() {
        withShell { app ->
            // cmd sets the console's title, which ConPTY passes on as OSC 0.
            app.command("title vesper-title")
            app.waitFor("the title is \"${app.title()}\"") { app.title().contains("vesper-title") }
        }
    }

    @Test
    fun aBorderDragResizesTheSessionOnceItEnds() {
        withShell { app ->
            val before = app.sessionSize
            memScoped {
                val rect = alloc<RECT>()
                SendMessageW(app.window.hwnd, WM_ENTERSIZEMOVE.convert(), 0u, 0)
                SendMessageW(app.window.hwnd, WM_SIZING.convert(), WMSZ_BOTTOMRIGHT.convert(), rect.ptr.toLong())
            }
            val (width, height) = app.window.clientSize
            app.window.setClientSize(width + 200, height + 120)
            Window.pumpMessages()
            assertEquals(before, app.sessionSize, "the session was resized during the drag")
            SendMessageW(app.window.hwnd, WM_EXITSIZEMOVE.convert(), 0u, 0)
            val after = app.sessionSize
            assertTrue(after.first.cols > before.first.cols && after.first.rows > before.first.rows, "$before became $after")
            assertEquals(before.second, after.second)
            // Output flows again: the shell still answers.
            app.command("echo resumed")
            app.waitFor("no echo after the drag") { screen -> screen.lines().any { it.trimEnd() == "resumed" } }
        }
    }

    @Test
    fun aDpiChangeKeepsTheGridAndScalesTheCell() {
        withShell { app ->
            val before = app.sessionSize
            val dpi = app.window.dpi * 2
            // What Windows sends on moving to a monitor of twice the DPI: the window twice as large.
            memScoped {
                val rect = alloc<RECT>()
                GetWindowRect(app.window.hwnd, rect.ptr)
                val (width, height) = app.window.clientSize
                rect.right += width
                rect.bottom += height
                SendMessageW(app.window.hwnd, WM_DPICHANGED.convert(), (dpi or (dpi shl 16)).convert(), rect.ptr.toLong())
            }
            val after = app.sessionSize
            assertEquals(before.first, after.first)
            assertNotEquals(before.second, after.second)
            assertTrue(after.second.first in before.second.first * 2 - 1..before.second.first * 2 + 1, "$before became $after")
            app.paint()
        }
    }
}
