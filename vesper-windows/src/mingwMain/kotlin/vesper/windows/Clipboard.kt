package vesper.windows

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.windows.CF_UNICODETEXT
import platform.windows.CloseClipboard
import platform.windows.EmptyClipboard
import platform.windows.GMEM_MOVEABLE
import platform.windows.GetClipboardData
import platform.windows.GlobalAlloc
import platform.windows.GlobalFree
import platform.windows.GlobalLock
import platform.windows.GlobalSize
import platform.windows.GlobalUnlock
import platform.windows.HWND
import platform.windows.OpenClipboard
import platform.windows.SetClipboardData

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

/** Puts [text] on the clipboard; false when another program has it open or memory ran out. */
internal fun writeClipboardText(
    owner: HWND,
    text: String,
): Boolean {
    // UTF-16 and the NUL that ends it, as CF_UNICODETEXT is.
    val handle = GlobalAlloc(GMEM_MOVEABLE.convert(), ((text.length + 1) * 2).convert()) ?: return false
    val data = GlobalLock(handle)?.reinterpret<UShortVar>()
    if (data == null) {
        GlobalFree(handle)
        return false
    }
    text.forEachIndexed { i, char -> data[i] = char.code.toUShort() }
    data[text.length] = 0u
    GlobalUnlock(handle)
    if (OpenClipboard(owner) == 0) {
        GlobalFree(handle)
        return false
    }
    try {
        // The clipboard owns the block once SetClipboardData takes it, and only then.
        if (EmptyClipboard() == 0 || SetClipboardData(CF_UNICODETEXT.toUInt(), handle) == null) {
            GlobalFree(handle)
            return false
        }
        return true
    } finally {
        CloseClipboard()
    }
}
