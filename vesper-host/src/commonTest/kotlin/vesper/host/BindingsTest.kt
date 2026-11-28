package vesper.host

import vesper.config.Action
import vesper.config.Binding
import vesper.config.Chord
import vesper.config.MAC_BINDINGS
import vesper.config.MAX_FONT_SIZE
import vesper.config.MIN_FONT_SIZE
import vesper.config.PC_BINDINGS
import vesper.config.Rgb
import vesper.config.Scheme
import vesper.grid.Color
import vesper.input.Key
import vesper.input.KeyAction
import vesper.input.KeyEvent
import vesper.input.Modifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BindingsTest {
    private val bindings = KeyBindings(MAC_BINDINGS)
    private val equals = Key.Character('='.code)

    @Test
    fun aBoundChordRunsItsActionWhateverTheLockKeys() {
        assertEquals(Action.FontLarger, bindings.action(KeyEvent(equals, mods = Modifiers.SUPER)))
        assertEquals(Action.FontLarger, bindings.action(KeyEvent(equals, mods = Modifiers.SUPER + Modifiers.SHIFT + Modifiers.LOCKS)))
        assertEquals(Action.ScrollPageUp, bindings.action(KeyEvent(Key.PageUp, KeyAction.Repeat, Modifiers.SHIFT)))
    }

    @Test
    fun anythingElseGoesToTheProgram() {
        assertNull(bindings.action(KeyEvent(equals)), "typing")
        assertNull(bindings.action(KeyEvent(equals, KeyAction.Release, Modifiers.SUPER)), "a release")
        assertNull(bindings.action(KeyEvent(equals, mods = Modifiers.SUPER + Modifiers.CTRL)), "another chord")
        val rebound = KeyBindings(listOf(Binding(Chord(Key.F(5), Modifiers.NONE), Action.Paste)))
        assertNull(rebound.action(KeyEvent(Key.Character('v'.code), mods = Modifiers.SUPER)))
        assertEquals(Action.Paste, rebound.action(KeyEvent(Key.F(5))))
    }

    @Test
    fun zoomStepsStayInTheConfigsRange() {
        assertEquals(14.0, zoomedSize(Action.FontLarger, 13.0, 13.0))
        assertEquals(MAX_FONT_SIZE, zoomedSize(Action.FontLarger, MAX_FONT_SIZE, 13.0))
        assertEquals(MIN_FONT_SIZE, zoomedSize(Action.FontSmaller, MIN_FONT_SIZE, 13.0))
        assertEquals(13.0, zoomedSize(Action.FontReset, 20.0, 13.0))
        assertNull(zoomedSize(Action.Paste, 13.0, 13.0))
    }

    @Test
    fun pagesKeepOneRowAndTheEndsGoAllTheWay() {
        assertEquals(23, scrollDelta(Action.ScrollPageUp, 24))
        assertEquals(-23, scrollDelta(Action.ScrollPageDown, 24))
        assertEquals(1, scrollDelta(Action.ScrollPageUp, 1))
        assertEquals(Int.MAX_VALUE, scrollDelta(Action.ScrollToTop, 24))
        assertEquals(-Int.MAX_VALUE, scrollDelta(Action.ScrollToBottom, 24))
        assertNull(scrollDelta(Action.PreviousPrompt, 24))
    }

    @Test
    fun eachPlatformsPromptChordsWalkThePromptsBothWays() {
        val pc = KeyBindings(PC_BINDINGS)
        val chords =
            listOf(
                Triple(bindings, Modifiers.SUPER, "Cmd"),
                Triple(pc, Modifiers.CTRL + Modifiers.SHIFT, "Ctrl+Shift"),
            )
        for ((keys, mods, name) in chords) {
            assertEquals(false, keys.action(KeyEvent(Key.Up, mods = mods))?.let(::promptDirection), "$name+Up")
            assertEquals(true, keys.action(KeyEvent(Key.Down, mods = mods + Modifiers.NUM_LOCK))?.let(::promptDirection), "$name+Down")
            assertNull(keys.action(KeyEvent(Key.Up)), "a plain arrow is the program's")
        }
        assertNull(promptDirection(Action.ScrollPageUp))
    }

    @Test
    fun thePaletteTakesTheThemesColours() {
        val theme = Scheme.SolarizedDark.theme
        val palette = Palette(theme.copy(ansi = theme.ansi.toMutableList().also { it[1] = Rgb.of(0x123456) }))
        assertEquals(0x002B36, palette.background)
        assertEquals(0x123456, palette.resolve(Color.indexed(1), 0))
        // The cube and the grey ramp stay xterm's.
        assertEquals(0x000000, palette.resolve(Color.indexed(16), -1))
        assertEquals(0xEEEEEE, palette.resolve(Color.indexed(255), -1))
    }

    @Test
    fun aLightSchemeGetsHighlightsItsTextReadsOn() {
        val light = Palette(Scheme.VesperLight.theme)
        val dark = Palette()
        assertNotEquals(dark.selection, light.selection)
        assertEquals(0xE5E5E5, dark.foreground)
    }
}
