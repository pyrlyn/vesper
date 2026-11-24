package vesper.macos

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style
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

class PaletteTest {
    private val palette = Palette()

    @Test
    fun indexedColoursAreXterms() {
        assertEquals(0xCD0000, palette.resolve(Color.indexed(1), 0))
        assertEquals(0x000000, palette.resolve(Color.indexed(16), 0))
        assertEquals(0x5F87AF, palette.resolve(Color.indexed(67), 0))
        assertEquals(0xFFFFFF, palette.resolve(Color.indexed(231), 0))
        assertEquals(0x080808, palette.resolve(Color.indexed(232), 0))
        assertEquals(0xEEEEEE, palette.resolve(Color.indexed(255), 0))
    }

    @Test
    fun directAndDefaultColours() {
        assertEquals(0x123456, palette.resolve(Color.rgb(0x12, 0x34, 0x56), 0))
        assertEquals(0xABCDEF, palette.resolve(Color.DEFAULT, 0xABCDEF))
    }

    @Test
    fun reverseVideoSwapsAndDimBlends() {
        assertEquals(CellColors(palette.foreground, palette.background), palette.colors(Style.DEFAULT))
        assertEquals(CellColors(palette.background, palette.foreground), palette.colors(Style(attrs = Attrs.INVERSE)))
        val dim = palette.colors(Style(fg = Color.rgb(200, 100, 0), bg = Color.rgb(0, 0, 0), attrs = Attrs.DIM))
        assertEquals(CellColors(rgb(100, 50, 0), 0), dim)
    }
}

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

class GeometryTest {
    private val cell = CellSize(8.0, 16.0)

    @Test
    fun theGridFitsWholeCellsInsideThePadding() {
        assertEquals(Cells(80, 24), gridSize(80 * 8 + 2 * PADDING + 7, 24 * 16 + 2 * PADDING + 15, cell))
        assertEquals(Cells(1, 1), gridSize(0.0, 0.0, cell))
        val (width, height) = viewSize(Cells(80, 24), cell)
        assertEquals(Cells(80, 24), gridSize(width, height, cell))
    }

    @Test
    fun pointsMapToCellsAndClampAtTheEdges() {
        val grid = Cells(80, 24)
        assertEquals(Cells(0, 0), cellAt(PADDING, PADDING, cell, grid))
        assertEquals(Cells(1, 2), cellAt(PADDING + 8, PADDING + 40, cell, grid))
        assertEquals(Cells(0, 0), cellAt(-50.0, -50.0, cell, grid))
        assertEquals(Cells(79, 23), cellAt(5000.0, 5000.0, cell, grid))
    }
}

class LaunchTest {
    @Test
    fun aMissingLocaleGetsUtf8() {
        assertEquals("en_US.UTF-8", launchEnvironment { null }["LANG"])
        assertFalse("LANG" in launchEnvironment { if (it == "LC_CTYPE") "UTF-8" else null })
        assertEquals("xterm-256color", launchEnvironment { null }["TERM"])
    }
}
