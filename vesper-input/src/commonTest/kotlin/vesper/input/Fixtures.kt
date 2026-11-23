package vesper.input

// Modifier combinations by name, as the specifications' tables label their columns.
internal val NONE = Modifiers.NONE
internal val SHIFT = Modifiers.SHIFT
internal val ALT = Modifiers.ALT
internal val CTRL = Modifiers.CTRL
internal val CTRL_SHIFT = CTRL + SHIFT
internal val ALT_SHIFT = ALT + SHIFT
internal val CTRL_ALT = CTRL + ALT

/** A press of [key] with [mods] held, typing [text]. */
internal fun event(
    key: Key,
    mods: Modifiers = NONE,
    text: String = "",
) = KeyEvent(key, mods = mods, text = text)

internal fun char(c: Char): Key = Key.Character(c.code)

/** What [write] appends, decoded as UTF-8 so a failing table row reads as text. */
internal fun bytesOf(write: (ByteSink) -> Unit): String {
    val out = ByteSink()
    write(out)
    return out.toByteArray().decodeToString()
}
