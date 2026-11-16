package app.vesper.platform

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Stock OpenJDK gives a full-content window only the standard 28pt title bar, so the traffic
 * lights sit above the centre of Vesper's taller title bar. Attaching an empty native toolbar
 * makes AppKit lay out a taller title bar with the buttons vertically centred in it.
 * Everything runs on the AppKit main thread via libdispatch. Failures are ignored: the window
 * then keeps the standard title bar.
 */
object MacTitleBar {
    /** NSWindowToolbarStyle values. */
    enum class Style(val value: Long) { Unified(3), UnifiedCompact(4) }

    private fun interface DispatchFunction : Callback {
        fun invoke(context: Pointer?)
    }

    // Held strongly: libdispatch calls it later, after install() returns.
    private var pending: DispatchFunction? = null

    fun install(windowTitle: String, style: Style) {
        runCatching {
            val system = NativeLibrary.getInstance("System")
            val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
            val callback = DispatchFunction { runCatching { attach(windowTitle, style) } }
            pending = callback
            system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, callback))
        }
    }

    private val objc by lazy { NativeLibrary.getInstance("objc") }

    private fun cls(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun sel(name: String): Pointer? = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        objc.getFunction("objc_msgSend").invokePointer(arrayOf(receiver, sel(selector), *args))
    private fun sendLong(receiver: Pointer?, selector: String): Long =
        objc.getFunction("objc_msgSend").invokeLong(arrayOf(receiver, sel(selector)))

    private fun nsString(s: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", s)
    private fun kotlinString(ns: Pointer?): String? = send(ns, "UTF8String")?.getString(0, "UTF-8")

    private fun attach(windowTitle: String, style: Style) {
        val app = send(cls("NSApplication"), "sharedApplication")
        val windows = send(app, "windows")
        val count = sendLong(windows, "count")
        for (i in 0 until count) {
            val window = send(windows, "objectAtIndex:", i)
            if (kotlinString(send(window, "title")) != windowTitle) continue
            if (send(window, "toolbar") != null) continue
            val toolbar = send(send(cls("NSToolbar"), "alloc"), "initWithIdentifier:", nsString("app.vesper.titlebar"))
            send(toolbar, "setShowsBaselineSeparator:", 0)
            send(window, "setToolbar:", toolbar)
            send(window, "setToolbarStyle:", style.value)
            send(toolbar, "release")
        }
    }
}
