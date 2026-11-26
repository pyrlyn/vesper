package vesper.windows

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.windows.CF_UNICODETEXT
import platform.windows.CloseClipboard
import platform.windows.GetClipboardData
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.HWND
import platform.windows.OpenClipboard

/** The clipboard's text, or null when it holds none or another program has it open. */
internal fun readClipboardText(owner: HWND): String? {
    if (OpenClipboard(owner) == 0) return null
    try {
        val handle = GetClipboardData(CF_UNICODETEXT.toUInt()) ?: return null
        val data = GlobalLock(handle)?.reinterpret<UShortVar>() ?: return null
        try {
            // Another program wrote this: read no further than its block, whether or not it ends in NUL.
            val units = (GlobalSize(handle) / 2u).toInt()
            val chars = CharArray(units) { data[it].toInt().toChar() }
            val end = chars.indexOf('\u0000').takeIf { it >= 0 } ?: units
            return chars.concatToString(0, end)
        } finally {
            GlobalUnlock(handle)
        }
    } finally {
        CloseClipboard()
    }
}
