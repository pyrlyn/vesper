package vesper.macos

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style
import vesper.host.CellColors
import vesper.host.CellSize
import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.cellAt
import vesper.host.gridSize
import vesper.host.rgb
import vesper.host.viewSize
import vesper.input.Key
import vesper.input.KeyAction
import vesper.input.KeypadKey
import vesper.input.Modifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val SHIFT = 1L shl 17
private const val CONTROL = 1L shl 18
private const val OPTION = 1L shl 19
private const val COMMAND = 1L shl 20

class MacKeysTest {
    @Test
    fun keysAreNamedByPosition() {
        assertEquals(Key.Character('a'.code), macKey(0x00))
        assertEquals(Key.Character('/'.code), macKey(0x2C))
        assertEquals(Key.Character(' '.code), macKey(0x31))
        assertEquals(Key.Enter, macKey(0x24))
        assertEquals(Key.Backspace, macKey(0x33))
        assertEquals(Key.Up, macKey(0x7E))
        assertEquals(Key.F(1), macKey(0x7A))
        assertEquals(Key.F(20), macKey(0x5A))
        assertEquals(Key.Keypad(KeypadKey.Digit9), macKey(0x5C))
        assertEquals(Key.Keypad(KeypadKey.Equal), macKey(0x51))
        assertNull(macKey(0x3F))
    }

    @Test
    fun modifiers() {
        assertEquals(
            Modifiers.SHIFT + Modifiers.ALT + Modifiers.CTRL + Modifiers.SUPER,
            macModifiers(SHIFT or CONTROL or OPTION or COMMAND),
        )
        assertEquals(Modifiers.NONE, macModifiers(0))
    }

    @Test
    fun ctrlLeavesTheTextWithoutIt() {
        val event = macKeyEvent(0x08, CONTROL, "\u0003", "c", "c", KeyAction.Press)!!
        assertEquals(Key.Character('c'.code), event.key)
        assertEquals("c", event.text)
        assertEquals(Modifiers.CTRL, event.mods)
    }

    @Test
    fun optionTypingItsOwnCharacterIsConsumed() {
        val typed = macKeyEvent(0x00, OPTION, "å", "a", "a", KeyAction.Press)!!
        assertEquals(Modifiers.ALT, typed.consumed)
        assertEquals("å", typed.text)
        val dead = macKeyEvent(0x00, OPTION, "a", "a", "a", KeyAction.Press)!!
        assertEquals(Modifiers.NONE, dead.consumed)
    }

    @Test
    fun anotherLayoutKeepsThePositionAndReportsItsOwnBase() {
        val event = macKeyEvent(0x08, 0, "с", "с", "с", KeyAction.Repeat)!!
        assertEquals(Key.Character('c'.code), event.key)
        assertEquals('с'.code, event.unshifted)
        assertEquals(KeyAction.Repeat, event.action)
        assertNull(macKeyEvent(0x08, 0, "c", "c", "c", KeyAction.Press)!!.unshifted)
    }

    @Test
    fun functionKeysCarryNoText() {
        val event = macKeyEvent(0x7E, SHIFT, "", "", "", KeyAction.Press)!!
        assertEquals("", event.text)
        assertEquals(Modifiers.SHIFT, event.mods)
    }
}
