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
        assertEquals(11, DEFAULT_BINDINGS.size)
        assertTrue(DEFAULT_BINDINGS.none { it.action == Action.None })
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
        for (binding in DEFAULT_BINDINGS) assertEquals(binding.chord, parseChord(binding.chord.toString()))
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
        val bindings =
            bindingsOf(
                entry("super+v", "none") + entry("ctrl+shift+v", "paste") + entry("cmd+=", "font-reset"),
            )
        assertTrue(bindings.none { it.chord == Chord(char('v'), Modifiers.SUPER) })
        assertTrue(Binding(Chord(char('v'), Modifiers.CTRL + Modifiers.SHIFT), Action.Paste) in bindings)
        assertEquals(Action.FontReset, bindings.single { it.chord == Chord(char('='), Modifiers.SUPER) }.action)
        assertEquals(DEFAULT_BINDINGS.size, bindings.size)
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
