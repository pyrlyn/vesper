package vesper.host

import vesper.term.Event
import vesper.term.TitleTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostEventsTest {
    @Test
    fun aWindowShowsEveryTitleButAnIconName() {
        assertEquals("vim", Event.Title(TitleTarget.Both, "vim").windowTitle)
        assertEquals("", Event.Title(TitleTarget.Window, "").windowTitle)
        assertNull(Event.Title(TitleTarget.Icon, "vim").windowTitle)
    }

    @Test
    fun onlyUtf8ReachesTheClipboard() {
        assertEquals("héllo ✓", Event.ClipboardWrite("c", "héllo ✓".encodeToByteArray()).clipboardText)
        assertNull(Event.ClipboardWrite("c", byteArrayOf(0x68, 0xFF.toByte())).clipboardText)
        // A lone surrogate's encoding is not UTF-8 either.
        assertNull(Event.ClipboardWrite("c", byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())).clipboardText)
    }
}
