package scull.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyCodeTest {
    @Test
    fun namedKeysTakeKittysFunctionalCodes() {
        assertEquals(Key.Escape, Key.ofCode(57_344))
        assertEquals(Key.Tab, Key.ofCode(57_346))
        assertEquals(Key.End, Key.ofCode(57_357))
        assertEquals(Key.System(SystemKey.CapsLock), Key.ofCode(57_358))
        assertEquals(Key.System(SystemKey.Menu), Key.ofCode(57_363))
        assertEquals(Key.F(1), Key.ofCode(57_364))
        assertEquals(Key.F(35), Key.ofCode(57_398))
        assertEquals(Key.Keypad(KeypadKey.Digit0), Key.ofCode(57_399))
        assertEquals(Key.Keypad(KeypadKey.Begin), Key.ofCode(57_427))
        assertEquals(Key.Media(MediaKey.Play), Key.ofCode(57_428))
        assertEquals(Key.Modifier(ModifierKey.IsoLevel5Shift), Key.ofCode(57_454))
    }

    @Test
    fun everyCodedKeyRoundTrips() {
        for (k in KeypadKey.entries) assertEquals(Key.Keypad(k), Key.ofCode(k.code))
        for (k in MediaKey.entries) assertEquals(Key.Media(k), Key.ofCode(k.code))
        for (k in ModifierKey.entries) assertEquals(Key.Modifier(k), Key.ofCode(k.code))
        for (k in SystemKey.entries) assertEquals(Key.System(k), Key.ofCode(k.code))
    }

    @Test
    fun otherCodesAreTheCodePointsTheyType() {
        assertEquals(Key.Character('a'.code), Key.ofCode('a'.code))
        assertEquals(Key.Character(' '.code), Key.ofCode(' '.code))
        assertEquals(Key.Character(0x1F600), Key.ofCode(0x1F600))
        // Past kitty's last functional code the private use area is text again.
        assertEquals(Key.Character(57_455), Key.ofCode(57_455))
    }

    @Test
    fun controlsSurrogatesAndNonCodePointsNameNoKey() {
        for (code in listOf(0, 0x1B, 0x7F, 0x85, 0xD800, 0xDFFF, 0x11_0000, -1)) assertNull(Key.ofCode(code), "$code")
    }
}
