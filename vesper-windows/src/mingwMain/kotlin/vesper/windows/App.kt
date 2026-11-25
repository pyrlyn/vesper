package vesper.windows

import platform.windows.PostQuitMessage

// The palette's default background (vesper-macos `Palette`); it moves to the shared host module in
// T17's third slice, and the renderer then draws the terminal over it.
private const val BACKGROUND = 0x141414

/** One terminal window. So far it keeps a swap chain the size of its client area and clears it. */
class App(
    private val gpu: Gpu,
    private val quitOnClose: Boolean = true,
) : WindowListener,
    AutoCloseable {
    // Declared before the window: WM_SIZE arrives inside CreateWindowExW, before there is a chain.
    private var chain: SwapChain? = null
    val window = Window("Vesper", 960, 600, this)
    internal val swapChain: SwapChain get() = chain!!

    init {
        val (width, height) = window.clientSize
        chain = SwapChain.create(gpu, window.hwnd, width, height)
    }

    override fun resized(
        width: Int,
        height: Int,
    ) {
        chain?.resize(width, height)
    }

    override fun paint() {
        val chain = chain ?: return
        chain.clear(BACKGROUND)
        chain.present()
    }

    override fun closed() {
        if (quitOnClose) PostQuitMessage(0)
    }

    override fun close() {
        chain?.close()
        chain = null
        window.close()
    }
}

/** Shows the window and runs its messages until it closes; the process exits with their code. */
fun run(): Int {
    enablePerMonitorDpi()
    return Gpu.best().use { gpu ->
        App(gpu).use { app ->
            app.window.show()
            Window.runMessageLoop()
        }
    }
}

fun main() {
    kotlin.system.exitProcess(run())
}
