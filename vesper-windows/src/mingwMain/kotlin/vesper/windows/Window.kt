package vesper.windows

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.CREATESTRUCTW
import platform.windows.CS_HREDRAW
import platform.windows.CS_VREDRAW
import platform.windows.CW_USEDEFAULT
import platform.windows.CreateWindowExW
import platform.windows.DefWindowProcW
import platform.windows.DestroyWindow
import platform.windows.DispatchMessageW
import platform.windows.GWLP_USERDATA
import platform.windows.GetClientRect
import platform.windows.GetLastError
import platform.windows.GetMessageW
import platform.windows.GetModuleHandleW
import platform.windows.GetWindowLongPtrW
import platform.windows.GetWindowRect
import platform.windows.HWND
import platform.windows.IDC_ARROW
import platform.windows.LoadCursorW
import platform.windows.MSG
import platform.windows.PM_REMOVE
import platform.windows.PeekMessageW
import platform.windows.RECT
import platform.windows.RegisterClassExW
import platform.windows.SIZE_MINIMIZED
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOMOVE
import platform.windows.SWP_NOZORDER
import platform.windows.SW_SHOW
import platform.windows.SetWindowLongPtrW
import platform.windows.SetWindowPos
import platform.windows.ShowWindow
import platform.windows.TranslateMessage
import platform.windows.ValidateRect
import platform.windows.WM_DESTROY
import platform.windows.WM_DPICHANGED
import platform.windows.WM_ERASEBKGND
import platform.windows.WM_NCCREATE
import platform.windows.WM_NCDESTROY
import platform.windows.WM_PAINT
import platform.windows.WM_SIZE
import platform.windows.WNDCLASSEXW
import platform.windows.WS_OVERLAPPEDWINDOW

/** A Win32 call that failed, with the error GetLastError gave. */
class WindowsException(
    val call: String,
    val error: Int,
) : RuntimeException("$call failed: error $error")

/** What a window tells its owner, on the thread that pumps its messages. Sizes are physical pixels. */
interface WindowListener {
    fun resized(
        width: Int,
        height: Int,
    ) {}

    fun dpiChanged(dpi: Int) {}

    fun paint() {}

    fun closed() {}

    /** Any other message (input, focus, the owner's own); true when handled, false for the default. */
    fun message(
        message: Int,
        wParam: ULong,
        lParam: Long,
    ): Boolean = false
}

/** A top-level Win32 window of [width] by [height] device-independent pixels of client area. */
class Window(
    title: String,
    width: Int,
    height: Int,
    private val listener: WindowListener,
) : AutoCloseable {
    private val self = StableRef.create(this)
    private var open = true
    val hwnd: HWND

    init {
        hwnd = CreateWindowExW(
            0u,
            windowClass,
            title,
            WS_OVERLAPPEDWINDOW.convert(),
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            null,
            null,
            GetModuleHandleW(null),
            self.asCPointer(),
        ) ?: run {
            val error = GetLastError().toInt()
            self.dispose()
            throw WindowsException("CreateWindowExW", error)
        }
        // Only now is the monitor, and so the DPI, known.
        setClientSize(width * dpi / DEFAULT_DPI, height * dpi / DEFAULT_DPI)
    }

    /** The DPI of the monitor the window is on. */
    val dpi: Int get() = dpiOf(hwnd)

    /** The client area's width and height in physical pixels. */
    val clientSize: Pair<Int, Int>
        get() =
            memScoped {
                val rect = alloc<RECT>()
                GetClientRect(hwnd, rect.ptr)
                rect.right - rect.left to rect.bottom - rect.top
            }

    /** Resizes the window so its client area is [width] by [height] physical pixels. */
    fun setClientSize(
        width: Int,
        height: Int,
    ) {
        memScoped {
            val outer = alloc<RECT>()
            GetWindowRect(hwnd, outer.ptr)
            val (clientWidth, clientHeight) = clientSize
            // The frame is whatever the window has beyond its client area, at this DPI.
            val frameWidth = outer.right - outer.left - clientWidth
            val frameHeight = outer.bottom - outer.top - clientHeight
            SetWindowPos(
                hwnd,
                null,
                0,
                0,
                width + frameWidth,
                height + frameHeight,
                (SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE).convert(),
            )
        }
    }

    fun show() {
        ShowWindow(hwnd, SW_SHOW)
    }

    override fun close() {
        if (open) DestroyWindow(hwnd)
    }

    internal fun handle(
        hwnd: HWND,
        message: Int,
        wParam: ULong,
        lParam: Long,
    ): Long? =
        when (message) {
            WM_SIZE -> {
                // A minimised window reports 0 by 0, which no swap chain can take.
                if (wParam != SIZE_MINIMIZED.toULong()) listener.resized(loWord(lParam), hiWord(lParam))
                0
            }

            WM_DPICHANGED -> {
                listener.dpiChanged(loWord(wParam.toLong()))
                // Windows suggests the rectangle that keeps the window's size in DIPs.
                val rect = lParam.toCPointer<RECT>()!!.pointed
                SetWindowPos(
                    hwnd,
                    null,
                    rect.left,
                    rect.top,
                    rect.right - rect.left,
                    rect.bottom - rect.top,
                    (SWP_NOZORDER or SWP_NOACTIVATE).convert(),
                )
                0
            }

            WM_PAINT -> {
                listener.paint()
                ValidateRect(hwnd, null)
                0
            }

            // The swap chain covers the whole client area; erasing first would flicker.
            WM_ERASEBKGND -> {
                1
            }

            WM_DESTROY -> {
                open = false
                listener.closed()
                0
            }

            WM_NCDESTROY -> {
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0)
                self.dispose()
                null
            }

            else -> {
                if (listener.message(message, wParam, lParam)) 0 else null
            }
        }

    companion object {
        /** Pumps messages until WM_QUIT and returns its exit code. */
        fun runMessageLoop(): Int =
            memScoped {
                val message = alloc<MSG>()
                while (GetMessageW(message.ptr, null, 0u, 0u) > 0) {
                    TranslateMessage(message.ptr)
                    DispatchMessageW(message.ptr)
                }
                message.wParam.toInt()
            }

        /** Handles the messages already queued, without waiting for more. */
        fun pumpMessages() {
            memScoped {
                val message = alloc<MSG>()
                while (PeekMessageW(message.ptr, null, 0u, 0u, PM_REMOVE.convert()) != 0) {
                    TranslateMessage(message.ptr)
                    DispatchMessageW(message.ptr)
                }
            }
        }
    }
}

internal fun loWord(value: Long) = (value and 0xFFFF).toInt()

internal fun hiWord(value: Long) = ((value shr 16) and 0xFFFF).toInt()

private const val CLASS_NAME = "VesperWindow"

private val windowClass: String by lazy {
    memScoped {
        val windowClass =
            alloc<WNDCLASSEXW> {
                cbSize = sizeOf<WNDCLASSEXW>().convert()
                // Redraw the whole client area on resize: the swap chain is drawn as one.
                style = (CS_HREDRAW or CS_VREDRAW).convert()
                lpfnWndProc = staticCFunction(::windowProc)
                hInstance = GetModuleHandleW(null)
                hCursor = LoadCursorW(null, IDC_ARROW)
                lpszClassName = CLASS_NAME.wcstr.ptr
            }
        if (RegisterClassExW(windowClass.ptr).toInt() == 0) throw WindowsException("RegisterClassExW", GetLastError().toInt())
    }
    CLASS_NAME
}

private fun windowProc(
    hwnd: HWND?,
    message: UInt,
    wParam: ULong,
    lParam: Long,
): Long {
    // The window's Kotlin object rides in through CreateWindowExW and is kept in the user data.
    if (message.toInt() == WM_NCCREATE) {
        val create = lParam.toCPointer<CREATESTRUCTW>()!!.pointed
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, create.lpCreateParams.toLong())
    }
    val window =
        GetWindowLongPtrW(hwnd, GWLP_USERDATA)
            .toCPointer<CPointed>()
            ?.asStableRef<Window>()
            ?.get()
    return window?.handle(hwnd!!, message.toInt(), wParam, lParam) ?: DefWindowProcW(hwnd, message, wParam, lParam)
}
