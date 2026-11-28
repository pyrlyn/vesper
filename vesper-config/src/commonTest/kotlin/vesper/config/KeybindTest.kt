package vesper.config

import vesper.input.Key
import vesper.input.Modifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FILE = "/home/me/.config/vesper/config.toml"

private fun bindingsOf(text: String) = Settings.parse(text, FILE).bindings

private fun refusedChord(
    text: String,
    fragment: String,
) {
    val message = assertFailsWith<IllegalArgumentException>(text) { parseChord(text) }.message.orEmpty()
    assertTrue(fragment in message, "`$fragment` not in: $message")
}

private fun refusedFile(
    text: String,
    vararg fragments: String,
) {
    val message = assertFailsWith<ConfigException.Invalid>(text) { Settings.parse(text, FILE) }.message.orEmpty()
    for (fragment in fragments) assertTrue(fragment in message, "`$fragment` not in: $message")
}

private fun entry(
    key: String,
    action: String,
) = "[[keybind]]\nkey = \"$key\"\naction = \"$action\"\n"

private fun char(c: Char) = Key.Character(c.code)

class KeybindTest {
    @Test
    fun noKeybindTablesIsTheDefaults() {
        assertEquals(DEFAULT_BINDINGS, Settings.DEFAULT.bindings)
        assertTrue(DEFAULT_BINDINGS === MAC_BINDINGS || DEFAULT_BINDINGS === PC_BINDINGS)
        // Both sets offer every action, so a binding learnt on one platform has a twin on the other.
        val actions = Action.entries - Action.None
        for (set in listOf(MAC_BINDINGS, PC_BINDINGS)) {
            assertEquals(actions.toSet(), set.map { it.action }.toSet())
            assertEquals(set.size, set.map { it.chord }.toSet().size, "a chord is bound twice")
        }
        // The Windows key belongs to the system on Windows.
        assertTrue(PC_BINDINGS.none { Modifiers.SUPER in it.chord.mods })
    }

    @Test
    fun chordsReadModifiersAliasesAndKeyNames() {
        assertEquals(Chord(char('='), Modifiers.SUPER + Modifiers.SHIFT), parseChord("super+shift+="))
        // Case and alias do not matter: the chord is what the host sees, not how it was spelled.
        assertEquals(Chord(char('v'), Modifiers.SUPER), parseChord("Cmd+V"))
        assertEquals(Chord(char('v'), Modifiers.SUPER), parseChord("win+v"))
        assertEquals(Chord(Key.F(12), Modifiers.CTRL + Modifiers.ALT), parseChord("control+option+f12"))
        assertEquals(Chord(Key.F(35), Modifiers.ALT), parseChord("opt+f35"))
        assertEquals(Chord(char(' '), Modifiers.CTRL), parseChord("ctrl+space"))
        // Only character keys need a shortcut modifier; named keys may stand alone or with Shift.
        assertEquals(Chord(Key.PageUp, Modifiers.SHIFT), parseChord("shift+page-up"))
        assertEquals(Chord(Key.F(5), Modifiers.NONE), parseChord("f5"))
    }

    @Test
    fun aChordWritesItsCanonicalSpelling() {
        assertEquals("ctrl+shift+super+a", parseChord("super+shift+ctrl+a").toString())
        assertEquals("alt+page-down", parseChord("option+PAGE-DOWN").toString())
        for (binding in MAC_BINDINGS + PC_BINDINGS) assertEquals(binding.chord, parseChord(binding.chord.toString()))
    }

    @Test
    fun badChordsAreRefused() {
        refusedChord("shift+a", "would steal typing")
        refusedChord("a", "would steal typing")
        refusedChord("ctrl+ctrl+a", "names ctrl twice")
        refusedChord("cmd+super+a", "names super twice")
        refusedChord("hyper+a", "`hyper` is not a modifier in `hyper+a`")
        refusedChord("ctrl+", "`` is not a key name")
        refusedChord("ctrl+f36", "`f36` is not a key name")
        refusedChord("ctrl+f0", "`f0` is not a key name")
        refusedChord("ctrl+ab", "`ab` is not a key name")
        refusedChord("ctrl+\t", "is not a key name")
        refusedChord("ctrl+" + "a".repeat(MAX_CHORD_LENGTH), "at most $MAX_CHORD_LENGTH characters")
    }

    @Test
    fun entriesLayOverTheDefaults() {
        val entries =
            listOf(
                Binding(parseChord("super+v"), Action.None),
                Binding(parseChord("ctrl+alt+v"), Action.Paste),
                Binding(parseChord("cmd+="), Action.FontReset),
            )
        val bindings = resolveBindings(entries, MAC_BINDINGS)
        assertTrue(bindings.none { it.chord == Chord(char('v'), Modifiers.SUPER) })
        assertTrue(Binding(Chord(char('v'), Modifiers.CTRL + Modifiers.ALT), Action.Paste) in bindings)
        assertEquals(Action.FontReset, bindings.single { it.chord == Chord(char('='), Modifiers.SUPER) }.action)
        assertEquals(MAC_BINDINGS.size, bindings.size)
        // A file reaches the same code with this platform's defaults.
        val first = DEFAULT_BINDINGS.first()
        assertEquals(DEFAULT_BINDINGS - first, bindingsOf(entry(first.chord.toString(), "none")))
        // `none` on a chord with no default is allowed and changes nothing.
        assertEquals(DEFAULT_BINDINGS, bindingsOf(entry("ctrl+alt+x", "none")))
    }

    @Test
    fun badEntriesAreRefusedNamingTheEntry() {
        refusedFile(entry("cmd+v", "paste") + entry("super+V", "none"), "keybind `super+v` appears twice")
        refusedFile(entry("ctrl+x", "paste") + entry("shift+x", "paste"), "keybind[1].key", "would steal typing")
        refusedFile(entry("ctrl+x", "copy"), "keybind[0].action `copy` is not one of none, paste,")
        refusedFile("[[keybind]]\nkey = \"ctrl+x\"\n", "action")
        refusedFile(entry("ctrl+x", "paste") + "when = \"always\"\n", "when")
        refusedFile(entry("ctrl+x", "paste") + "action = \"none\"\n", "`action` is set twice")
        val tooMany = (0..MAX_KEYBINDS).joinToString("") { entry("ctrl+f${it % LAST_F_KEY + 1}", "none") }
        refusedFile(tooMany, "at most $MAX_KEYBINDS keybind entries, not ${MAX_KEYBINDS + 1}")
    }
}
