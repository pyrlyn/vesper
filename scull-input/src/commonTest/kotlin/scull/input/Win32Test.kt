package scull.input

import kotlin.test.Test
import kotlin.test.assertEquals

class Win32Test {
    private fun enc(event: KeyEvent) = bytesOf { encodeWin32(event, it) }

    private fun released(event: KeyEvent) = event.copy(action = KeyAction.Release)

    @Test
    fun recordsMatchTheConptySpecificationExamples() {
        // The spec's examples, with the fields it lets an encoder omit written out (Uc 0, Kd 0,
        // Cs 0, Rc 1).
        val table =
            listOf(
                event(char('a'), NONE, "a") to "65;30;97;1;0;1",
                released(event(char('a'), NONE, "a")) to "65;30;97;0;0;1",
                event(char('a'), SHIFT, "A") to "65;30;65;1;16;1",
                event(Key.Modifier(ModifierKey.LeftShift), SHIFT) to "16;42;0;1;16;1",
                event(Key.Modifier(ModifierKey.LeftControl), CTRL) to "17;29;0;1;8;1",
                event(Key.F(1), CTRL) to "112;59;0;1;8;1",
                released(event(Key.F(1), CTRL)) to "112;59;0;0;8;1",
                event(Key.Modifier(ModifierKey.LeftAlt), CTRL_ALT) to "18;56;0;1;10;1",
                event(char('a'), CTRL_ALT, "a") to "65;30;0;1;10;1",
            )
        for ((e, fields) in table) assertEquals("\u001b[${fields}_", enc(e), "$e")
    }

    @Test
    fun ctrlPutsTheControlCharacterInUnicodeChar() {
        assertEquals("\u001b[65;30;1;1;8;1_", enc(event(char('a'), CTRL, "a")))
        assertEquals("\u001b[219;26;27;1;8;1_", enc(event(char('['), CTRL, "[")))
        assertEquals("\u001b[49;2;0;1;8;1_", enc(event(char('1'), CTRL, "1")))
    }

    @Test
    fun c0KeysCarryTheirByte() {
        val table =
            listOf(
                Key.Enter to "13;28;13",
                Key.Escape to "27;1;27",
                Key.Tab to "9;15;9",
                Key.Backspace to "8;14;8",
                Key.Keypad(KeypadKey.Enter) to "13;28;13",
            )
        for ((key, fields) in table) {
            val enhanced = if (key == Key.Keypad(KeypadKey.Enter)) 256 else 0
            assertEquals("\u001b[$fields;1;$enhanced;1_", enc(event(key)), "$key")
        }
    }

    @Test
    fun navigationKeysAreEnhancedAndKeypadTwinsAreNot() {
        assertEquals("\u001b[38;72;0;1;256;1_", enc(event(Key.Up)))
        assertEquals("\u001b[38;72;0;1;0;1_", enc(event(Key.Keypad(KeypadKey.Up))))
        assertEquals("\u001b[46;83;0;1;272;1_", enc(event(Key.Delete, SHIFT)))
    }

    @Test
    fun virtualKeysAndScanCodesFollowTheUsLayout() {
        val table =
            listOf(
                Triple(char('1'), 0x31, 0x02),
                Triple(char('='), 0xBB, 0x0D),
                Triple(char('q'), 0x51, 0x10),
                Triple(char(']'), 0xDD, 0x1B),
                Triple(char('`'), 0xC0, 0x29),
                Triple(char('\\'), 0xDC, 0x2B),
                Triple(char('/'), 0xBF, 0x35),
                Triple(char(' '), 0x20, 0x39),
                Triple(char('ж'), 0, 0),
                Triple(Key.F(10), 0x79, 0x44),
                Triple(Key.F(12), 0x7B, 0x58),
                Triple(Key.F(13), 0x7C, 0x64),
                Triple(Key.F(24), 0x87, 0x76),
                Triple(Key.F(25), 0, 0),
                Triple(Key.Keypad(KeypadKey.Digit7), 0x67, 0x47),
                Triple(Key.Keypad(KeypadKey.Digit0), 0x60, 0x52),
                Triple(Key.System(SystemKey.CapsLock), 0x14, 0x3A),
                Triple(Key.Media(MediaKey.MuteVolume), 0xAD, 0x20),
            )
        for ((key, vk, scan) in table) {
            val got = win32Key(key)
            assertEquals(vk to scan, got.vk to got.scan, "$key")
        }
    }

    @Test
    fun lockStatesSetTheirControlKeyStateBits() {
        assertEquals("\u001b[65;30;65;1;160;1_", enc(event(char('a'), Modifiers.LOCKS, "A")))
    }

    @Test
    fun textOutsideTheBmpGoesAsSurrogateRecords() {
        assertEquals("\u001b[65;30;55357;1;0;1_\u001b[65;30;56832;1;0;1_", enc(event(char('a'), NONE, "😀")))
    }

    @Test
    fun altKeepsTheTypedCharacter() {
        assertEquals("\u001b[65;30;97;1;2;1_", enc(event(char('a'), ALT, "a")))
    }
}
