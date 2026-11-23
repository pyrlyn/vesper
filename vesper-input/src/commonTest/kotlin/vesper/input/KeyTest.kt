package vesper.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KeyTest {
    @Test
    fun ctrlTableMatchesTheKittySpecification() {
        val table =
            listOf(
                ' ' to 0,
                '/' to 31,
                '2' to 0,
                '3' to 27,
                '4' to 28,
                '5' to 29,
                '6' to 30,
                '7' to 31,
                '8' to 127,
                '?' to 127,
                '@' to 0,
                '[' to 27,
                '\\' to 28,
                ']' to 29,
                '^' to 30,
                '_' to 31,
                'a' to 1,
                'i' to 9,
                'm' to 13,
                'z' to 26,
                '~' to 30,
            )
        for ((key, byte) in table) assertEquals(byte, ctrlByte(key.code), "ctrl+$key")
        for (untouched in listOf('0', '1', '9', ';', '\'', ',', '.', 'A', 'ж')) {
            assertEquals(-1, ctrlByte(untouched.code), "ctrl+$untouched")
        }
    }

    @Test
    fun functionalTableMatchesTheKittySpecification() {
        val table =
            listOf(
                Triple(Key.Escape, 27, 'u'),
                Triple(Key.Enter, 13, 'u'),
                Triple(Key.Tab, 9, 'u'),
                Triple(Key.Backspace, 127, 'u'),
                Triple(Key.Insert, 2, '~'),
                Triple(Key.Delete, 3, '~'),
                Triple(Key.Left, 1, 'D'),
                Triple(Key.PageDown, 6, '~'),
                Triple(Key.Home, 1, 'H'),
                Triple(Key.End, 1, 'F'),
                Triple(Key.System(SystemKey.CapsLock), 57_358, 'u'),
                Triple(Key.System(SystemKey.Menu), 57_363, 'u'),
                Triple(Key.F(1), 1, 'P'),
                Triple(Key.F(3), 13, '~'),
                Triple(Key.F(5), 15, '~'),
                Triple(Key.F(6), 17, '~'),
                Triple(Key.F(11), 23, '~'),
                Triple(Key.F(12), 24, '~'),
                Triple(Key.F(13), 57_376, 'u'),
                Triple(Key.F(35), 57_398, 'u'),
                Triple(Key.Keypad(KeypadKey.Digit0), 57_399, 'u'),
                Triple(Key.Keypad(KeypadKey.Enter), 57_414, 'u'),
                Triple(Key.Keypad(KeypadKey.Delete), 57_426, 'u'),
                Triple(Key.Keypad(KeypadKey.Begin), 1, 'E'),
                Triple(Key.Media(MediaKey.Play), 57_428, 'u'),
                Triple(Key.Media(MediaKey.MuteVolume), 57_440, 'u'),
                Triple(Key.Modifier(ModifierKey.LeftShift), 57_441, 'u'),
                Triple(Key.Modifier(ModifierKey.IsoLevel5Shift), 57_454, 'u'),
            )
        for ((key, number, final) in table) assertEquals(Form(number, final.code), functional(key), "$key")
        assertNull(functional(Key.F(0)))
        assertNull(functional(Key.F(36)))
        assertNull(functional(char('a')))
    }

    @Test
    fun keypadTextKeysTypeTheirCharacters() {
        assertEquals('0'.code, KeypadKey.Digit0.textChar())
        assertEquals('9'.code, KeypadKey.Digit9.textChar())
        assertEquals(','.code, KeypadKey.Separator.textChar())
        assertNull(KeypadKey.Left.textChar())
        assertNull(KeypadKey.Enter.textChar())
    }

    @Test
    fun consumedModifiersAreNotReportedExceptShift() {
        val event = KeyEvent(char('a'), mods = SHIFT + ALT + CTRL, consumed = SHIFT + ALT)
        assertEquals(SHIFT + CTRL, event.reportedMods)
    }

    @Test
    fun controlCharactersInTextAreIgnored() {
        assertEquals("[201~", bytesOf { event(char('a'), text = "\u001b[201~").writeText(it) })
        assertFalse(event(char('a'), text = "\u0001").hasText())
    }

    @Test
    fun undefinedModifierBitsAreDropped() {
        assertEquals(0xFF, Modifiers.of(-1).bits)
    }

    @Test
    fun committedTextLosesItsControlCharactersAndKeepsAstralCodePoints() {
        assertEquals("é[31mü😀", bytesOf { encodeText("é\u001b[31m\r\u009bü😀", it) })
        assertEquals("�", bytesOf { encodeText("\uD83D", it) })
    }
}
