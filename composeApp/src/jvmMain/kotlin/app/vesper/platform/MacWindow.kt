package app.vesper.platform

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Native touches on Vesper's macOS window, done through the Objective-C runtime.
 *
 * AWT refuses a translucent background on a decorated frame, so for Liquid Glass the window is
 * created undecorated and transparent, and [install] gives it back the native title bar: traffic
 * lights, resizing, rounded corners and shadow, with the content drawn under a transparent
 * title bar. An empty unified toolbar makes the title bar taller so the traffic lights sit
 * centred in Vesper's 52pt title bar. [setBlur] blurs what is behind the window (the same
 * window-server call iTerm2 and Alacritty use).
 *
 * Everything runs on the AppKit main thread via libdispatch. Failures are ignored: the window
 * then stays as AWT made it.
 */
object MacWindow {
    /** NSWindowToolbarStyle values. */
    enum class Style(val value: Long) { Unified(3), UnifiedCompact(4) }

    private const val TITLED = 1L
    private const val CLOSABLE = 1L shl 1
    private const val MINIATURIZABLE = 1L shl 2
    private const val RESIZABLE = 1L shl 3
    private const val FULL_SIZE_CONTENT_VIEW = 1L shl 15
    private const val TITLE_HIDDEN = 1L

    private fun interface DispatchFunction : Callback {
        fun invoke(context: Pointer?)
    }

    // Held strongly until libdispatch has run them (it calls back after the dispatch returns).
    private val pending = java.util.Collections.synchronizedSet(HashSet<DispatchFunction>())

    private fun onMain(block: () -> Unit) {
        runCatching {
            val system = NativeLibrary.getInstance("System")
            val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
            lateinit var callback: DispatchFunction
            callback = DispatchFunction {
                try {
                    runCatching(block)
                } finally {
                    pending.remove(callback)
                }
            }
            pending.add(callback)
            system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, callback))
        }
    }

    /** Native title bar for the window titled [windowTitle]; [restoreDecorations] for an undecorated AWT window. */
    fun install(windowTitle: String, style: Style, restoreDecorations: Boolean) = onMain {
        forWindow(windowTitle) { window ->
            if (restoreDecorations) {
                val mask = TITLED or CLOSABLE or MINIATURIZABLE or RESIZABLE or FULL_SIZE_CONTENT_VIEW
                send(window, "setStyleMask:", mask)
                send(window, "setTitlebarAppearsTransparent:", 1)
                send(window, "setTitleVisibility:", TITLE_HIDDEN)
                send(window, "setBackgroundColor:", send(cls("NSColor"), "clearColor"))
                send(window, "setHasShadow:", 1)
            }
            if (send(window, "toolbar") == null) {
                val toolbar = send(send(cls("NSToolbar"), "alloc"), "initWithIdentifier:", nsString("app.vesper.titlebar"))
                send(toolbar, "setShowsBaselineSeparator:", 0)
                send(window, "setToolbar:", toolbar)
                send(toolbar, "release")
            }
            send(window, "setToolbarStyle:", style.value)
        }
    }

    /** Blurs the desktop behind the window titled [windowTitle] by [radius] points (0 turns it off). */
    fun setBlur(windowTitle: String, radius: Int) = onMain {
        forWindow(windowTitle) { window ->
            val number = sendLong(window, "windowNumber").toInt()
            val cg = NativeLibrary.getInstance("CoreGraphics")
            val connection = cg.getFunction("CGSMainConnectionID").invokeInt(emptyArray())
            cg.getFunction("CGSSetWindowBackgroundBlurRadius").invokeInt(arrayOf(connection, number, radius.coerceIn(0, 100)))
        }
    }

    /** Whether "Reduce transparency" is on in System Settings › Accessibility › Display. */
    fun systemReducesTransparency(): Boolean = runCatching {
        val workspace = send(cls("NSWorkspace"), "sharedWorkspace")
        objc.getFunction("objc_msgSend").invokeInt(arrayOf(workspace, sel("accessibilityDisplayShouldReduceTransparency"))) and 0xFF != 0
    }.getOrDefault(false)

    private val objc by lazy { NativeLibrary.getInstance("objc") }

    private fun cls(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
    private fun sel(name: String): Pointer? = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        objc.getFunction("objc_msgSend").invokePointer(arrayOf(receiver, sel(selector), *args))
    private fun sendLong(receiver: Pointer?, selector: String): Long =
        objc.getFunction("objc_msgSend").invokeLong(arrayOf(receiver, sel(selector)))

    private fun nsString(s: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", s)
    private fun kotlinString(ns: Pointer?): String? = send(ns, "UTF8String")?.getString(0, "UTF-8")

    private inline fun forWindow(windowTitle: String, action: (Pointer) -> Unit) {
        val app = send(cls("NSApplication"), "sharedApplication")
        val windows = send(app, "windows")
        val count = sendLong(windows, "count")
        for (i in 0 until count) {
            val window = send(windows, "objectAtIndex:", i) ?: continue
            if (kotlinString(send(window, "title")) == windowTitle) action(window)
        }
    }
}
