package vesper.config

import vesper.input.Key
import vesper.input.Modifiers

// `[[keybind]]` entries: a chord such as `super+shift+=` and the action it runs. Chords are in
// vesper-input's vocabulary, the same keys and modifier bits a host puts in a KeyEvent, so a host
// matches a binding with one comparison and no layout knowledge.

/** The most `[[keybind]]` entries a file may have. */
public const val MAX_KEYBINDS: Int = 256

/** The longest chord text; the longest real one, `ctrl+alt+shift+super+page-down`, is half that. */
public const val MAX_CHORD_LENGTH: Int = 64

/** The highest function key a chord may name, as for [Key.F]. */
internal const val LAST_F_KEY = 35

// Modifiers that make a character key a shortcut rather than typing.
private val SHORTCUT_MODS = Modifiers.ALT + Modifiers.CTRL + Modifiers.SUPER

/** What a binding does; [id] is the file's spelling. */
public enum class Action(
    public val id: String,
) {
    /** Removes a default binding of the same chord; never in [Settings.bindings]. */
    None("none"),
    Paste("paste"),
    FontLarger("font-larger"),
    FontSmaller("font-smaller"),

    /** Back to the configured font size. */
    FontReset("font-reset"),
    ScrollPageUp("scroll-page-up"),
    ScrollPageDown("scroll-page-down"),
    ScrollToTop("scroll-to-top"),
    ScrollToBottom("scroll-to-bottom"),

    /** The previous shell prompt that OSC 133 `A` marked. */
    PreviousPrompt("previous-prompt"),
    NextPrompt("next-prompt"),
    ;

    public companion object {
        public fun byId(id: String): Action? = entries.firstOrNull { it.id == id }
    }
}

/** A key with the modifiers held, such as Command and `=`; [mods] holds only Shift, Alt, Ctrl and Super. */
public data class Chord(
    /** The key, by its position on a US layout. */
    val key: Key,
    val mods: Modifiers,
) {
    /** The canonical spelling, which [parseChord] reads back. */
    override fun toString(): String =
        buildString {
            for ((name, bit) in MOD_NAMES) if (bit in mods) append(name).append('+')
            val named = KEY_NAMES.firstOrNull { it.second == key }?.first
            append(
                named ?: when (key) {
                    is Key.F -> "f${key.number}"

                    is Key.Character -> key.codePoint.toChar().toString()

                    // parseChord never builds another key; the data class name keeps the text readable.
                    else -> key.toString()
                },
            )
        }
}

/** A binding in force; [action] is never [Action.None]. */
public data class Binding(
    val chord: Chord,
    val action: Action,
)

private fun bind(
    c: Char,
    mods: Modifiers,
    action: Action,
) = Binding(Chord(Key.Character(c.code), mods), action)

/** The macOS defaults: shortcuts on Command, as every Mac app has them. */
public val MAC_BINDINGS: List<Binding> =
    listOf(
        bind('v', Modifiers.SUPER, Action.Paste),
        bind('=', Modifiers.SUPER, Action.FontLarger),
        // `+` is Shift and `=` on a US layout, and people press it to grow text.
        bind('=', Modifiers.SUPER + Modifiers.SHIFT, Action.FontLarger),
        bind('-', Modifiers.SUPER, Action.FontSmaller),
        bind('0', Modifiers.SUPER, Action.FontReset),
        Binding(Chord(Key.PageUp, Modifiers.SHIFT), Action.ScrollPageUp),
        Binding(Chord(Key.PageDown, Modifiers.SHIFT), Action.ScrollPageDown),
        Binding(Chord(Key.Home, Modifiers.SUPER), Action.ScrollToTop),
        Binding(Chord(Key.End, Modifiers.SUPER), Action.ScrollToBottom),
        Binding(Chord(Key.Up, Modifiers.SUPER), Action.PreviousPrompt),
        Binding(Chord(Key.Down, Modifiers.SUPER), Action.NextPrompt),
    )

/**
 * The Windows and Linux defaults. The Windows key belongs to the system there (Win+V is clipboard
 * history), and Ctrl alone is the program's, so shortcuts that would clash take Ctrl+Shift, as
 * Windows Terminal does.
 */
public val PC_BINDINGS: List<Binding> =
    listOf(
        bind('v', Modifiers.CTRL + Modifiers.SHIFT, Action.Paste),
        Binding(Chord(Key.Insert, Modifiers.SHIFT), Action.Paste),
        // Zoom keeps Ctrl alone, as Windows Terminal and browsers have it; a file can hand Ctrl+- (^_,
        // readline's undo) back to the program with `none`.
        bind('=', Modifiers.CTRL, Action.FontLarger),
        bind('=', Modifiers.CTRL + Modifiers.SHIFT, Action.FontLarger),
        bind('-', Modifiers.CTRL, Action.FontSmaller),
        bind('0', Modifiers.CTRL, Action.FontReset),
        Binding(Chord(Key.PageUp, Modifiers.SHIFT), Action.ScrollPageUp),
        Binding(Chord(Key.PageDown, Modifiers.SHIFT), Action.ScrollPageDown),
        Binding(Chord(Key.Home, Modifiers.CTRL + Modifiers.SHIFT), Action.ScrollToTop),
        Binding(Chord(Key.End, Modifiers.CTRL + Modifiers.SHIFT), Action.ScrollToBottom),
        Binding(Chord(Key.Up, Modifiers.CTRL + Modifiers.SHIFT), Action.PreviousPrompt),
        Binding(Chord(Key.Down, Modifiers.CTRL + Modifiers.SHIFT), Action.NextPrompt),
    )

/** Whether this build runs on macOS, which picks [DEFAULT_BINDINGS]. */
internal expect val isMacOs: Boolean

/** What a file with no `[[keybind]]` runs with on this platform. */
public val DEFAULT_BINDINGS: List<Binding> = if (isMacOs) MAC_BINDINGS else PC_BINDINGS

internal val KEY_NAMES: List<Pair<String, Key>> =
    listOf(
        "space" to Key.Character(' '.code),
        "escape" to Key.Escape,
        "enter" to Key.Enter,
        "tab" to Key.Tab,
        "backspace" to Key.Backspace,
        "insert" to Key.Insert,
        "delete" to Key.Delete,
        "left" to Key.Left,
        "right" to Key.Right,
        "up" to Key.Up,
        "down" to Key.Down,
        "page-up" to Key.PageUp,
        "page-down" to Key.PageDown,
        "home" to Key.Home,
        "end" to Key.End,
    )

// Also the order Chord.toString writes them in.
internal val MOD_NAMES: List<Pair<String, Modifiers>> =
    listOf("ctrl" to Modifiers.CTRL, "alt" to Modifiers.ALT, "shift" to Modifiers.SHIFT, "super" to Modifiers.SUPER)

private val MOD_ALIASES: List<Pair<String, Modifiers>> =
    listOf(
        "control" to Modifiers.CTRL,
        "option" to Modifiers.ALT,
        "opt" to Modifiers.ALT,
        "cmd" to Modifiers.SUPER,
        "win" to Modifiers.SUPER,
    )

private fun key(token: String): Key? {
    KEY_NAMES.firstOrNull { it.first == token }?.let { return it.second }
    token
        .removePrefix("f")
        .takeIf { it != token }
        ?.toIntOrNull()
        ?.takeIf { it in 1..LAST_F_KEY }
        ?.let { return Key.F(it) }
    val c = token.singleOrNull() ?: return null
    return if (c.isISOControl() || c.isWhitespace() || c.isSurrogate()) null else Key.Character(c.code)
}

/** Reads `ctrl+shift+a`; throws [IllegalArgumentException] with a message that quotes [text]. */
internal fun parseChord(text: String): Chord {
    require(text.length <= MAX_CHORD_LENGTH) { "a chord is at most $MAX_CHORD_LENGTH characters" }
    val parts = text.lowercase().split('+')
    var mods = Modifiers.NONE
    for (token in parts.dropLast(1)) {
        val bit =
            (MOD_NAMES + MOD_ALIASES).firstOrNull { it.first == token }?.second
                ?: throw IllegalArgumentException("`$token` is not a modifier in `$text`")
        require(!mods.intersects(bit)) { "`$text` names $token twice" }
        mods += bit
    }
    val key = key(parts.last()) ?: throw IllegalArgumentException("`${parts.last()}` is not a key name in `$text`")
    // Plain typing must keep reaching the program.
    require(key !is Key.Character || mods.intersects(SHORTCUT_MODS)) {
        "`$text` would steal typing: a character key needs ctrl, alt or super"
    }
    return Chord(key, mods)
}

/** [defaults] with [entries] laid over them in order: the same chord replaces a default, `none` removes it. */
internal fun resolveBindings(
    entries: List<Binding>,
    defaults: List<Binding> = DEFAULT_BINDINGS,
): List<Binding> {
    val bindings = defaults.toMutableList()
    for (entry in entries) {
        bindings.removeAll { it.chord == entry.chord }
        if (entry.action != Action.None) bindings += entry
    }
    return bindings
}
