package vesper.windows

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import platform.windows.GetProcAddress
import platform.windows.HWND
import platform.windows.LoadLibraryW

/** The DPI at which one device-independent pixel is one physical pixel. */
const val DEFAULT_DPI = 96

// Both arrived in Windows 10 1607, after the version the platform.windows bindings are built for,
// so they are looked up; where they are missing the app runs at 96 DPI, scaled by the system.
private val user32 = LoadLibraryW("user32.dll")
private val getDpiForWindow =
    GetProcAddress(user32, "GetDpiForWindow")?.reinterpret<CFunction<(HWND?) -> UInt>>()
private val setDpiAwarenessContext =
    GetProcAddress(user32, "SetProcessDpiAwarenessContext")?.reinterpret<CFunction<(COpaquePointer?) -> Int>>()

/** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, a pseudo-handle. */
private const val PER_MONITOR_AWARE_V2 = -4L

/**
 * Makes the process per-monitor DPI aware (v2): windows are sized in physical pixels and told when
 * they move to a monitor of another DPI. Must run before the first window; false where unsupported.
 */
fun enablePerMonitorDpi(): Boolean = setDpiAwarenessContext?.invoke(PER_MONITOR_AWARE_V2.toCPointer())?.let { it != 0 } ?: false

/** The DPI of the monitor [hwnd] is on. */
internal fun dpiOf(hwnd: HWND): Int = getDpiForWindow?.invoke(hwnd)?.toInt()?.takeIf { it > 0 } ?: DEFAULT_DPI
