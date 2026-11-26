package vesper.windows

import platform.windows.IsWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Recorder : WindowListener {
    val sizes = mutableListOf<Pair<Int, Int>>()
    var closed = 0

    override fun resized(
        width: Int,
        height: Int,
    ) {
        sizes += width to height
    }

    override fun closed() {
        closed++
    }
}

class WindowTest {
    /** The client area is sized in physical pixels, and each resize reaches the listener. */
    @Test
    fun aResizeReachesTheListenerInPhysicalPixels() {
        val recorder = Recorder()
        val window = Window("Vesper test", 320, 200, recorder)
        try {
            assertTrue(window.dpi >= DEFAULT_DPI)
            assertEquals(320 * window.dpi / DEFAULT_DPI to 200 * window.dpi / DEFAULT_DPI, window.clientSize)
            window.setClientSize(410, 230)
            Window.pumpMessages()
            assertEquals(410 to 230, window.clientSize)
            assertEquals(410 to 230, recorder.sizes.last())
        } finally {
            window.close()
        }
        assertEquals(1, recorder.closed)
        assertEquals(0, IsWindow(window.hwnd))
        // A second close is a no-op once the window is gone.
        window.close()
    }

    @Test
    fun textOnTheClipboardReadsBackTheSame() {
        val window = Window("Vesper test", 100, 100, Recorder())
        try {
            // Outside the BMP too: a surrogate pair is two UTF-16 units on the clipboard.
            val text = "vesper ✓ \uD834\uDD1E"
            assertTrue(writeClipboardText(window.hwnd, text))
            assertEquals(text, readClipboardText(window.hwnd))
            assertTrue(writeClipboardText(window.hwnd, ""))
            assertEquals("", readClipboardText(window.hwnd))
        } finally {
            window.close()
        }
    }
}

class SwapChainTest {
    /** The swap chain follows the window: drawn into, presented and resized on WARP. */
    @Test
    fun aHiddenWindowsSwapChainClearsPresentsAndResizes() {
        Gpu.create(warp = true).use { gpu ->
            App(gpu, quitOnClose = false).use { app ->
                val chain = app.swapChain
                assertEquals(app.window.clientSize, chain.width to chain.height)
                chain.clear(0x285AA0)
                assertTrue(chain.readBackBuffer().all { it == 0xFF285AA0.toInt() })
                // A window that was never shown is occluded, which is still a successful present.
                chain.present()
                app.window.setClientSize(200, 120)
                Window.pumpMessages()
                assertEquals(200 to 120, chain.width to chain.height)
                chain.clear(0x3C3C3C)
                val pixels = chain.readBackBuffer()
                assertEquals(200 * 120, pixels.size)
                assertTrue(pixels.all { it == 0xFF3C3C3C.toInt() })
                app.paint()
            }
        }
    }
}
