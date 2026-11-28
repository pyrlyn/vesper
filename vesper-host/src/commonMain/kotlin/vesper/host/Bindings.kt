package vesper.host

import vesper.config.Action
import vesper.config.Binding
import vesper.config.Chord
import vesper.config.DEFAULT_BINDINGS
import vesper.config.MAX_FONT_SIZE
import vesper.config.MIN_FONT_SIZE
import vesper.input.KeyAction
import vesper.input.KeyEvent
import vesper.input.Modifiers

// What both hosts do with the config's key bindings and font size, so a chord means the same on each.

// The modifiers a chord can name; lock keys and the rare Hyper and Meta never stop a match.
private val CHORD_MODS = Modifiers.SHIFT + Modifiers.ALT + Modifiers.CTRL + Modifiers.SUPER

/** How far one font-larger or font-smaller moves the size, in points. */
public const val FONT_STEP: Double = 1.0

/** The config's bindings, looked up once per key press. */
public class KeyBindings(
    bindings: List<Binding> = DEFAULT_BINDINGS,
) {
    private val byChord = bindings.associate { it.chord to it.action }

    /** The action [event] runs instead of reaching the program, or null. A release never runs one. */
    public fun action(event: KeyEvent): Action? {
        if (event.action == KeyAction.Release) return null
        return byChord[Chord(event.key, Modifiers.of(event.mods.bits and CHORD_MODS.bits))]
    }
}

/** The font size after [action] from [size]; [configured] is where font-reset goes. Null for an action that is no zoom. */
public fun zoomedSize(
    action: Action,
    size: Double,
    configured: Double,
): Double? =
    when (action) {
        Action.FontLarger -> (size + FONT_STEP).coerceAtMost(MAX_FONT_SIZE)
        Action.FontSmaller -> (size - FONT_STEP).coerceAtLeast(MIN_FONT_SIZE)
        Action.FontReset -> configured
        else -> null
    }

/** How far [action] moves the viewport back into history on a screen of [rows] rows, or null for an action that is no scroll. */
public fun scrollDelta(
    action: Action,
    rows: Int,
): Int? =
    when (action) {
        // One row stays in view, as a pager's page keeps the line read last.
        Action.ScrollPageUp -> (rows - 1).coerceAtLeast(1)

        Action.ScrollPageDown -> -(rows - 1).coerceAtLeast(1)

        Action.ScrollToTop -> Int.MAX_VALUE

        Action.ScrollToBottom -> -Int.MAX_VALUE

        else -> null
    }

/** Whether [action] walks to the next shell prompt (true) or the previous one (false); null for an action that is no prompt jump. */
public fun promptDirection(action: Action): Boolean? =
    when (action) {
        Action.PreviousPrompt -> false
        Action.NextPrompt -> true
        else -> null
    }
