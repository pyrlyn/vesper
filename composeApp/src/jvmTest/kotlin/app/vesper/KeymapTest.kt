package app.vesper

import app.vesper.app.KeyCombo
import app.vesper.app.Settings
import app.vesper.app.ShortcutAction
import app.vesper.app.actionFor
import app.vesper.app.comboFor
import app.vesper.app.reservedCombo
import app.vesper.app.shortcutConflicts
import app.vesper.platform.HostOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeymapTest {
    @Test
    fun parsesAndPrintsCombos() {
        assertEquals(KeyCombo("D", shift = true), KeyCombo.parse("shift+D"))
        assertEquals(KeyCombo("Enter", shift = true, alt = true), KeyCombo.parse("alt+shift+Enter"))
        assertEquals("alt+shift+D", KeyCombo("D", shift = true, alt = true).toString())
        assertNull(KeyCombo.parse("ctrl+D"))
        assertNull(KeyCombo.parse("shift+Nope"))
        assertEquals("⌘⇧D", KeyCombo("D", shift = true).label(HostOs.MacOS))
    }

    @Test
    fun overridesAndUnbinding() {
        val s = Settings(keybindings = mapOf("Find" to "shift+F", "History" to ""))
        assertEquals(KeyCombo("F", shift = true), s.comboFor(ShortcutAction.Find))
        assertNull(s.comboFor(ShortcutAction.History))
        assertNull(s.actionFor(KeyCombo("Y")))
        assertEquals(ShortcutAction.Find, s.actionFor(KeyCombo("F", shift = true)))
        // A broken stored value falls back to the default.
        assertEquals(KeyCombo("K"), Settings(keybindings = mapOf("Palette" to "bad+")).comboFor(ShortcutAction.Palette))
    }

    @Test
    fun conflictsAndReserved() {
        assertTrue(Settings().shortcutConflicts().isEmpty())
        val c = Settings(keybindings = mapOf("Find" to "K")).shortcutConflicts()
        assertEquals(ShortcutAction.Palette, c[ShortcutAction.Find])
        assertEquals(ShortcutAction.Find, c[ShortcutAction.Palette])
        assertNotNull(reservedCombo(KeyCombo("3"), HostOs.Linux))
        assertNotNull(reservedCombo(KeyCombo("Q"), HostOs.MacOS))
        assertNull(reservedCombo(KeyCombo("Q"), HostOs.Linux))
        assertNull(reservedCombo(KeyCombo("Q", shift = true), HostOs.MacOS))
    }
}
