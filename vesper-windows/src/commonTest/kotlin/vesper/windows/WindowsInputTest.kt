package vesper.windows

import vesper.input.Key
import vesper.input.KeyAction
import vesper.input.KeyEvent
import vesper.input.ModifierKey
import vesper.input.Modifiers
import vesper.input.MouseAction
import vesper.input.MouseButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VK_A = 0x41
private const val SCAN_A = 0x1E
private const val VK_RETURN = 0x0D
private const val SCAN_RETURN = 0x1C
private val CTRL_ALT = Modifiers.CTRL + Modifiers.ALT

class WindowsInputTest {
    private val keys = ArrayList<KeyEvent>()
    private val texts = ArrayList<String>()
    private val pairing = KeyPairing(keys::add, texts::add)

    @Test
    fun aTextKeyWaitsForItsCharacter() {
        assertFalse(pairing.keyDown(VK_A, SCAN_A, false, false, Modifiers.SHIFT))
        assertEquals(0, keys.size)
        pairing.character('A')
        assertEquals(listOf(KeyEvent(Key.Character('a'.code), KeyAction.Press, Modifiers.SHIFT, text = "A")), keys)
        assertTrue(pairing.keyUp(VK_A, SCAN_A, false, Modifiers.NONE))
        assertEquals(KeyAction.Release, keys.last().action)
    }

    @Test
    fun ctrlAndKeysWithoutTextGoAtOnceAndTheirCharactersAreDropped() {
        assertTrue(pairing.keyDown(VK_A, SCAN_A, false, false, Modifiers.CTRL))
        pairing.character('\u0001')
        assertTrue(pairing.keyDown(VK_RETURN, SCAN_RETURN, false, true, Modifiers.NONE))
        pairing.character('\r')
        assertEquals(listOf(Key.Character('a'.code), Key.Enter), keys.map { it.key })
        assertEquals(KeyAction.Repeat, keys[1].action)
        assertEquals(emptyList(), texts)
    }

    @Test
    fun altGrTypesTextAndCountsCtrlAltAsUsedUp() {
        // AltGr+Q types @ on a German layout.
        pairing.keyDown(0x51, 0x10, false, false, CTRL_ALT)
        pairing.character('@')
        val event = keys.single()
        assertEquals("@", event.text)
        assertEquals(CTRL_ALT, event.consumed)
        // Ctrl+Alt with no character is a chord, sent when the next key arrives.
        pairing.keyDown(VK_A, SCAN_A, false, false, CTRL_ALT)
        pairing.keyUp(VK_A, SCAN_A, false, CTRL_ALT)
        assertEquals(listOf("", ""), keys.drop(1).map { it.text })
        assertEquals(listOf(KeyAction.Press, KeyAction.Release), keys.drop(1).map { it.action })
    }

    @Test
    fun aDeadKeyWaitsAndCharactersWithoutAKeyAreText() {
        // A dead key's own press sees no character; the composed one comes with the next key.
        pairing.keyDown(0xDE, 0x28, false, false, Modifiers.NONE)
        pairing.keyDown(0x45, 0x12, false, false, Modifiers.NONE)
        pairing.character('é')
        assertEquals(listOf(Key.Character('e'.code)), keys.map { it.key })
        assertEquals("é", keys.single().text)
        // An input method's key names nothing, so its character goes out as text; a pair arrives in two halves.
        assertFalse(pairing.keyDown(0xE5, SCAN_A, false, false, Modifiers.NONE))
        pairing.character('\uD83D')
        pairing.character('\uDE00')
        assertEquals(listOf("😀"), texts)
    }

    @Test
    fun aModifierKeyHoldsItsOwnModifier() {
        pairing.keyDown(0x10, 0x36, false, false, Modifiers.NONE)
        assertEquals(KeyEvent(Key.Modifier(ModifierKey.RightShift), KeyAction.Press, Modifiers.SHIFT), keys.single())
    }

    @Test
    fun heldModifiersAndTheSettingsShortcut() {
        val state = mapOf(0x10 to 0x8000, 0x11 to 0xFF80, 0x14 to 1, 0x90 to 0)
        assertEquals(Modifiers.SHIFT + Modifiers.CTRL + Modifiers.CAPS_LOCK, heldModifiers { state[it] ?: 0 })
        val comma = Key.Character(','.code)
        assertTrue(isSettings(comma, Modifiers.CTRL + Modifiers.NUM_LOCK))
        assertFalse(isSettings(comma, Modifiers.CTRL + Modifiers.SHIFT))
        assertFalse(isSettings(comma, Modifiers.NONE))
        assertFalse(isSettings(Key.Character('.'.code), Modifiers.CTRL))
    }

    @Test
    fun aSwallowedPressSwallowsItsRelease() {
        val swallowed = SwallowedKeys()
        swallowed.press(0x56, swallowed = true)
        assertTrue(swallowed.release(0x56))
        assertFalse(swallowed.release(0x56))
        swallowed.press(0x56, swallowed = true)
        swallowed.press(0x56, swallowed = false)
        assertFalse(swallowed.release(0x56))
    }

    @Test
    fun wheelFractionsCarry() {
        val wheel = Wheel()
        assertEquals(listOf(0, 1, 0, -1), listOf(60, 60, -60, -120).map { wheel.steps(it) })
        assertEquals(3, wheel.rows(120, 3, 40))
        // A third of a notch at three lines a notch is one row.
        assertEquals(1, wheel.rows(40, 3, 40))
        assertEquals(40, wheel.rows(120, PAGE_SCROLL, 40))
        assertEquals(0, wheel.rows(120, 0, 40))
    }

    @Test
    fun movesWithinACellAreDroppedAndALostCaptureReleasesWhatIsHeld() {
        val pointer = Pointer()
        val press = pointer.button(MouseButton.Left, true, Modifiers.NONE, 2, 3, 20, 30)
        assertEquals(MouseAction.Press, press.action)
        assertNull(pointer.move(Modifiers.NONE, 2, 3, 22, 31))
        val drag = pointer.move(Modifiers.NONE, 3, 3, 30, 31)!!
        assertEquals(MouseAction.Motion to MouseButton.Left, drag.action to drag.button)
        assertTrue(pointer.anyHeld)
        val releases = pointer.lose(Modifiers.SHIFT)
        assertEquals(listOf(Triple(MouseAction.Release, MouseButton.Left, 3)), releases.map { Triple(it.action, it.button, it.col) })
        assertFalse(pointer.anyHeld)
        assertEquals(emptyList(), pointer.lose(Modifiers.NONE))
        assertEquals(MouseButton.WheelLeft, pointer.wheel(-1, true, Modifiers.NONE, 0, 0, 0, 0).button)
        assertEquals(MouseButton.WheelUp, pointer.wheel(2, false, Modifiers.NONE, 0, 0, 0, 0).button)
    }
}
