package vesper.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CSI = "\u001b["
private const val SS3 = "\u001bO"

class LegacyTest {
    private fun encode(
        event: KeyEvent,
        modes: KeyModes = KeyModes(),
    ) = bytesOf { encodeLegacy(event, modes, it) }

    private fun enc(
        key: Key,
        mods: Modifiers,
        text: String = "",
    ) = encode(event(key, mods, text))

    @Test
    fun c0KeysMatchTheKittyLegacyTable() {
        // Columns of the specification's C0 table, in its order.
        val columns = listOf(NONE, CTRL, ALT, SHIFT, CTRL_SHIFT, ALT_SHIFT, CTRL_ALT)
        val table =
            listOf(
                Key.Enter to listOf("\r", "\r", "\u001b\r", "\r", "\r", "\u001b\r", "\u001b\r"),
                Key.Escape to listOf("\u001b", "\u001b", "\u001b\u001b", "\u001b", "\u001b", "\u001b\u001b", "\u001b\u001b"),
                Key.Backspace to listOf("\u007f", "\b", "\u001b\u007f", "\u007f", "\b", "\u001b\u007f", "\u001b\b"),
                Key.Tab to listOf("\t", "\t", "\u001b\t", "${CSI}Z", "${CSI}Z", "\u001b${CSI}Z", "\u001b\t"),
                char(' ') to listOf(" ", "\u0000", "\u001b ", " ", "\u0000", "\u001b ", "\u001b\u0000"),
            )
        for ((key, row) in table) {
            val text = if (key == char(' ')) " " else ""
            for ((mods, expected) in columns.zip(row)) assertEquals(expected, enc(key, mods, text), "$key $mods")
        }
    }

    @Test
    fun textKeysMatchTheKittyExampleEncodings() {
        // Columns: plain, shift, alt, ctrl, shift+alt, alt+ctrl, ctrl+shift.
        val columns = listOf(NONE, SHIFT, ALT, CTRL, ALT_SHIFT, CTRL_ALT, CTRL_SHIFT)
        val table =
            listOf(
                Triple('i', 'I', listOf("i", "I", "\u001bi", "\t", "\u001bI", "\u001b\t", "${CSI}105;6u")),
                Triple('3', '#', listOf("3", "#", "\u001b3", "\u001b", "\u001b#", "\u001b\u001b", "${CSI}51;6u")),
                Triple(';', ':', listOf(";", ":", "\u001b;", ";", "\u001b:", "\u001b;", "${CSI}59;6u")),
            )
        for ((key, shifted, row) in table) {
            for ((mods, expected) in columns.zip(row)) {
                val typed = if (SHIFT in mods) shifted else key
                assertEquals(expected, enc(char(key), mods, typed.toString()), "$key $mods")
            }
        }
    }

    @Test
    fun functionalKeysMatchTheKittyLegacyTable() {
        // (key, normal, application cursor mode, with shift)
        val table =
            listOf(
                listOf(Key.Insert, "[2~", "[2~", "[2;2~"),
                listOf(Key.Delete, "[3~", "[3~", "[3;2~"),
                listOf(Key.PageUp, "[5~", "[5~", "[5;2~"),
                listOf(Key.PageDown, "[6~", "[6~", "[6;2~"),
                listOf(Key.Up, "[A", "OA", "[1;2A"),
                listOf(Key.Down, "[B", "OB", "[1;2B"),
                listOf(Key.Right, "[C", "OC", "[1;2C"),
                listOf(Key.Left, "[D", "OD", "[1;2D"),
                listOf(Key.Home, "[H", "OH", "[1;2H"),
                listOf(Key.End, "[F", "OF", "[1;2F"),
                listOf(Key.F(1), "OP", "OP", "[1;2P"),
                listOf(Key.F(2), "OQ", "OQ", "[1;2Q"),
                listOf(Key.F(3), "OR", "OR", "[1;2R"),
                listOf(Key.F(4), "OS", "OS", "[1;2S"),
                listOf(Key.F(5), "[15~", "[15~", "[15;2~"),
                listOf(Key.F(6), "[17~", "[17~", "[17;2~"),
                listOf(Key.F(7), "[18~", "[18~", "[18;2~"),
                listOf(Key.F(8), "[19~", "[19~", "[19;2~"),
                listOf(Key.F(9), "[20~", "[20~", "[20;2~"),
                listOf(Key.F(10), "[21~", "[21~", "[21;2~"),
                listOf(Key.F(11), "[23~", "[23~", "[23;2~"),
                listOf(Key.F(12), "[24~", "[24~", "[24;2~"),
                listOf(Key.System(SystemKey.Menu), "[29~", "[29~", "[29;2~"),
            )
        val app = KeyModes(applicationCursor = true)
        for ((key, normal, application, shifted) in table) {
            key as Key
            assertEquals("\u001b$normal", encode(event(key)), "$key")
            assertEquals("\u001b$application", encode(event(key), app), "$key")
            assertEquals("\u001b$shifted", enc(key, SHIFT), "$key")
        }
    }

    @Test
    fun modifierParameterIsOnePlusTheBits() {
        val table = listOf(ALT to "3", CTRL to "5", CTRL_SHIFT to "6", CTRL_ALT to "7", Modifiers.SUPER to "9")
        for ((mods, param) in table) {
            assertEquals("${CSI}1;${param}A", enc(Key.Up, mods))
            assertEquals("${CSI}15;$param~", enc(Key.F(5), mods))
        }
    }

    @Test
    fun lockStatesAreNotEncoded() {
        assertEquals("${CSI}A", enc(Key.Up, Modifiers.LOCKS))
        assertEquals("A", enc(char('a'), Modifiers.CAPS_LOCK, "A"))
        assertEquals("\u0001", enc(char('a'), Modifiers.NUM_LOCK + CTRL, "a"))
    }

    @Test
    fun releasesAndKeysWithoutALegacySpellingSendNothing() {
        assertEquals("", encode(KeyEvent(char('a'), KeyAction.Release, text = "a")))
        val keys =
            listOf(
                Key.F(13),
                Key.F(0),
                Key.Media(MediaKey.Play),
                Key.Modifier(ModifierKey.LeftShift),
                Key.System(SystemKey.CapsLock),
            )
        for (key in keys) assertEquals("", enc(key, NONE), "$key")
    }

    @Test
    fun repeatEncodesLikePress() {
        assertEquals("${CSI}A", encode(KeyEvent(Key.Up, KeyAction.Repeat)))
    }

    @Test
    fun keypadFollowsNumericAndApplicationModes() {
        val app = KeyModes(applicationKeypad = true)
        val table =
            listOf(
                Triple(KeypadKey.Digit0, "0", "p"),
                Triple(KeypadKey.Digit1, "1", "q"),
                Triple(KeypadKey.Digit9, "9", "y"),
                Triple(KeypadKey.Decimal, ".", "n"),
                Triple(KeypadKey.Divide, "/", "o"),
                Triple(KeypadKey.Multiply, "*", "j"),
                Triple(KeypadKey.Subtract, "-", "m"),
                Triple(KeypadKey.Add, "+", "k"),
                Triple(KeypadKey.Equal, "=", "X"),
                Triple(KeypadKey.Separator, ",", "l"),
            )
        for ((k, text, final) in table) {
            val plain = event(Key.Keypad(k), NONE, text)
            assertEquals(text, encode(plain), "$k")
            assertEquals("$SS3$final", encode(plain, app), "$k")
            // Modified keypad keys fall back to what the key types.
            assertTrue(encode(event(Key.Keypad(k), CTRL_SHIFT, text), app).endsWith("u"), "$k")
        }
        val enter = event(Key.Keypad(KeypadKey.Enter))
        assertEquals("\r", encode(enter))
        assertEquals("${SS3}M", encode(enter, app))
    }

    @Test
    fun keypadNavigationReportsAsMainBlockKeys() {
        assertEquals("${CSI}D", enc(Key.Keypad(KeypadKey.Left), NONE))
        assertEquals("${CSI}3~", enc(Key.Keypad(KeypadKey.Delete), NONE))
        assertEquals("${CSI}E", enc(Key.Keypad(KeypadKey.Begin), NONE))
        assertEquals("${SS3}D", encode(event(Key.Keypad(KeypadKey.Left)), KeyModes(applicationCursor = true)))
    }

    @Test
    fun unencodableCombinationsFallBackToCsiU() {
        assertEquals("${CSI}97;9u", enc(char('a'), Modifiers.SUPER, "a"))
        assertEquals("${CSI}13;9u", enc(Key.Enter, Modifiers.SUPER))
        assertEquals("${CSI}127;8u", enc(Key.Backspace, CTRL_ALT + SHIFT))
    }

    @Test
    fun modifyOtherKeysLevelTwoReportsEveryModifiedKey() {
        val level2 = KeyModes(modifyOtherKeys = ModifyOtherKeys.Level2)
        val table =
            listOf(
                event(char('a'), CTRL, "a") to "27;5;97~",
                event(char('a'), ALT, "a") to "27;3;97~",
                event(char('a'), CTRL_SHIFT, "A") to "27;6;65~",
                event(char('1'), CTRL, "1") to "27;5;49~",
                event(Key.Enter, CTRL) to "27;5;13~",
                event(Key.Escape, ALT) to "27;3;27~",
                event(Key.Tab, CTRL) to "27;5;9~",
                event(Key.Backspace, CTRL) to "27;5;127~",
            )
        for ((e, expected) in table) assertEquals("$CSI$expected", encode(e, level2), "$e")
        // Shift alone still types text, and Shift+Tab stays back-tab.
        assertEquals("A", encode(event(char('a'), SHIFT, "A"), level2))
        assertEquals("${CSI}Z", encode(event(Key.Tab, SHIFT), level2))
        // Function keys keep their own modifier parameter.
        assertEquals("${CSI}1;5A", encode(event(Key.Up, CTRL), level2))
    }

    @Test
    fun modifyOtherKeysLevelOneReportsOnlyWhatLegacyCannot() {
        val level1 = KeyModes(modifyOtherKeys = ModifyOtherKeys.Level1)
        assertEquals("\u0001", encode(event(char('a'), CTRL, "a"), level1))
        assertEquals("\u001ba", encode(event(char('a'), ALT, "a"), level1))
        assertEquals("${CSI}27;6;65~", encode(event(char('a'), CTRL_SHIFT, "A"), level1))
    }

    @Test
    fun ctrlActsOnTheUsKeyOnOtherLayouts() {
        val e = KeyEvent(char('c'), mods = CTRL, text = "с", unshifted = 'с'.code)
        assertEquals("\u0003", encode(e))
        assertEquals("с", encode(e.copy(mods = NONE)))
        assertEquals("\u001bс", encode(e.copy(mods = ALT)))
    }

    @Test
    fun modifiersTheLayoutConsumedAreNotEncoded() {
        assertEquals("å", encode(KeyEvent(char('a'), mods = ALT, consumed = ALT, text = "å")))
    }

    @Test
    fun controlCharactersInTextNeverReachTheProgram() {
        assertEquals("[201~a", enc(char('a'), NONE, "\u001b[201~a"))
        assertEquals("\u001ba", enc(char('a'), ALT, "\u009ba"))
    }
}
