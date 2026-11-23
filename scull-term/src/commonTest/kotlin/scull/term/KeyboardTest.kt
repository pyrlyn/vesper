package scull.term

import scull.input.ByteSink
import scull.input.KITTY_FLAG_STACK_DEPTH
import scull.input.Key
import scull.input.KeyEvent
import scull.input.KittyFlags
import scull.input.Modifiers
import scull.input.ModifyOtherKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardTest {
    private fun term() = Terminal(10, 4, 10)

    private fun Terminal.key(
        key: Key,
        mods: Modifiers = Modifiers.NONE,
        text: String = "",
    ): String {
        val out = ByteSink()
        encodeKey(KeyEvent(key, mods = mods, text = text), out)
        return out.toByteArray().decodeToString()
    }

    private fun Terminal.replies(text: String): String {
        feed(text)
        return takeReplies().decodeToString()
    }

    @Test
    fun cursorKeysFollowDecckm() {
        val t = term()
        assertEquals("\u001b[A", t.key(Key.Up))
        t.feed("\u001b[?1h")
        assertEquals("\u001bOA", t.key(Key.Up))
    }

    @Test
    fun theKeypadFollowsDeckpam() {
        val t = term()
        t.feed("\u001b=")
        assertTrue(t.keyModes().applicationKeypad)
        t.feed("\u001b>")
        assertFalse(t.keyModes().applicationKeypad)
    }

    @Test
    fun kittyFlagsArePushedSetQueriedAndPopped() {
        val t = term()
        assertEquals("\u001b[?0u", t.replies("\u001b[?u"))
        t.feed("\u001b[>1u")
        assertEquals(KittyFlags.DISAMBIGUATE, t.keyModes().kitty)
        assertEquals("\u001b[27u", t.key(Key.Escape))
        t.feed("\u001b[=8;2u")
        assertEquals("\u001b[?9u", t.replies("\u001b[?u"))
        t.feed("\u001b[=1;3u")
        assertEquals(KittyFlags.REPORT_ALL_KEYS, t.keyModes().kitty)
        t.feed("\u001b[<u")
        assertTrue(t.keyModes().kitty.isLegacy())
        assertEquals("\u001b", t.key(Key.Escape))
    }

    @Test
    fun eachScreenHasItsOwnKittyStackAndAFullResetClearsBoth() {
        val t = term()
        t.feed("\u001b[>1u\u001b[?1049h")
        assertTrue(t.keyModes().kitty.isLegacy(), "the alternate screen starts empty")
        t.feed("\u001b[>31u")
        assertEquals(KittyFlags.ALL, t.keyModes().kitty)
        t.feed("\u001b[?1049l")
        assertEquals(KittyFlags.DISAMBIGUATE, t.keyModes().kitty)
        t.feed("\u001b[?1049h\u001bc")
        assertTrue(t.keyModes().kitty.isLegacy())
        t.feed("\u001b[?1049h")
        assertTrue(t.keyModes().kitty.isLegacy(), "the alternate stack was cleared too")
    }

    @Test
    fun aFloodOfPushesStaysWithinTheStackDepth() {
        val t = term()
        t.feed("\u001b[>1u".repeat(1000))
        t.feed("\u001b[<${KITTY_FLAG_STACK_DEPTH - 1}u")
        assertEquals(KittyFlags.DISAMBIGUATE, t.keyModes().kitty)
        t.feed("\u001b[<u")
        assertTrue(t.keyModes().kitty.isLegacy())
    }

    @Test
    fun restoreCursorStillOwnsAPlainCsiU() {
        val t = term()
        t.feed("\u001b[2;3H\u001b[s\u001b[H\u001b[u")
        assertEquals(Cursor(1, 2, false), t.cursor)
        assertTrue(t.keyModes().kitty.isLegacy())
    }

    @Test
    fun xtmodkeysSetsAndResetsModifyOtherKeys() {
        val t = term()
        val ctrlA = { t.key(Key.Character('a'.code), Modifiers.CTRL, "a") }
        assertEquals("\u0001", ctrlA())
        t.feed("\u001b[>4;2m")
        assertEquals(ModifyOtherKeys.Level2, t.modes.modifyOtherKeys)
        assertEquals("\u001b[27;5;97~", ctrlA())
        t.feed("\u001b[>4;7m")
        assertEquals(ModifyOtherKeys.Level2, t.modes.modifyOtherKeys, "an unsupported level is ignored")
        t.feed("\u001b[>1;0m")
        assertEquals(ModifyOtherKeys.Level2, t.modes.modifyOtherKeys, "other resources are not this one")
        t.feed("\u001b[>4;1m")
        assertEquals(ModifyOtherKeys.Level1, t.modes.modifyOtherKeys)
        t.feed("\u001b[>4n")
        assertEquals(ModifyOtherKeys.Off, t.modes.modifyOtherKeys)
        t.feed("\u001b[>4;2m\u001b[>m")
        assertEquals(ModifyOtherKeys.Off, t.modes.modifyOtherKeys)
        t.feed("\u001b[>4;2m\u001bc")
        assertEquals(ModifyOtherKeys.Off, t.modes.modifyOtherKeys)
    }

    @Test
    fun win32InputModeIsKeptAndReported() {
        val t = term()
        assertEquals("\u001b[?9001;2\$y", t.replies("\u001b[?9001\$p"))
        t.feed("\u001b[?9001h")
        assertTrue(t.keyModes().win32Input)
        assertEquals("\u001b[?9001;1\$y", t.replies("\u001b[?9001\$p"))
        assertEquals("\u001b[38;72;0;1;256;1_", t.key(Key.Up))
        t.feed("\u001b[?9001l")
        assertFalse(t.modes.win32Input)
    }

    @Test
    fun committedTextLosesItsControlCharacters() {
        val out = ByteSink()
        term().encodeText("é\u001b[31m\r\u009bü", out)
        assertEquals("é[31mü", out.toByteArray().decodeToString())
    }
}
