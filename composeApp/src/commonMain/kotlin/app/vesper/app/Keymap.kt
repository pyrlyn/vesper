package app.vesper.app

import app.vesper.platform.HostOs
import androidx.compose.ui.input.key.Key

/**
 * An app shortcut: the platform modifier (⌘ on macOS, Ctrl elsewhere) plus [key], optionally with Shift and Alt.
 * Stored in settings as text like "shift+D".
 */
data class KeyCombo(val key: String, val shift: Boolean = false, val alt: Boolean = false) {
    override fun toString(): String = buildString {
        if (alt) append("alt+")
        if (shift) append("shift+")
        append(key)
    }

    /** Separate keycaps for display, e.g. ⌘ ⇧ D. */
    fun caps(os: HostOs): List<String> = buildList {
        add(os.mod.removeSuffix("+"))
        if (alt) add(os.alt.removeSuffix("+"))
        if (shift) add(os.shift.removeSuffix("+"))
        add(KeyLabels[key] ?: key)
    }

    fun label(os: HostOs): String = if (os == HostOs.MacOS) caps(os).joinToString("") else caps(os).joinToString("+")

    companion object {
        fun parse(text: String): KeyCombo? {
            val parts = text.split('+')
            val key = parts.lastOrNull()?.takeIf { it in KeyNames.values } ?: return null
            val mods = parts.dropLast(1)
            if (mods.any { it != "shift" && it != "alt" }) return null
            return KeyCombo(key, shift = "shift" in mods, alt = "alt" in mods)
        }
    }
}

enum class ShortcutCategory(val title: String) { General("General"), Panes("Tabs & panes"), Terminal("Terminal"), Agent("Agent") }

enum class ShortcutAction(val title: String, val description: String, val category: ShortcutCategory, val default: KeyCombo) {
    Palette("Command palette", "Search commands, hosts, workflows and history", ShortcutCategory.General, KeyCombo("K")),
    CommandsOnly("Commands only", "Open the palette filtered to commands", ShortcutCategory.General, KeyCombo("P", shift = true)),
    Settings("Settings", "Theme, fonts, shell and more", ShortcutCategory.General, KeyCombo(",")),
    Hosts("Hosts", "Manage and connect to SSH hosts", ShortcutCategory.General, KeyCombo("H", shift = true)),
    History("History", "Commands, exit codes and durations", ShortcutCategory.General, KeyCombo("Y")),
    ToggleSidebar("Toggle sidebar", "Sessions, workflows and recent history", ShortcutCategory.General, KeyCombo("B")),
    ToggleMonitor("Toggle monitor", "CPU, memory, processes and ports", ShortcutCategory.General, KeyCombo("J")),
    NewTab("New tab", "Open a terminal in the current directory", ShortcutCategory.Panes, KeyCombo("T")),
    Close("Close pane / tab", "Close the focused pane, or the tab", ShortcutCategory.Panes, KeyCombo("W")),
    PreviousTab("Previous tab", "", ShortcutCategory.Panes, KeyCombo("[")),
    NextTab("Next tab", "", ShortcutCategory.Panes, KeyCombo("]")),
    SplitRight("Split pane right", "Open a pane beside the focused one", ShortcutCategory.Panes, KeyCombo("D")),
    SplitDown("Split pane down", "Open a pane below the focused one", ShortcutCategory.Panes, KeyCombo("D", shift = true)),
    Zoom("Zoom pane", "Toggle full size for the focused pane", ShortcutCategory.Panes, KeyCombo("Enter")),
    Find("Find in output", "Search the focused pane's blocks", ShortcutCategory.Terminal, KeyCombo("F")),
    ExplainError("Explain last error", "Ask the agent why the last failed command failed", ShortcutCategory.Agent, KeyCombo("E")),
    AgentPanel("Agent panel", "Context, safety and recent questions", ShortcutCategory.Agent, KeyCombo("A", shift = true)),
}

/** The combo bound to [action]: the user's choice, else the default; null when unbound. */
fun Settings.comboFor(action: ShortcutAction): KeyCombo? {
    val custom = keybindings[action.name] ?: return action.default
    if (custom.isEmpty()) return null
    return KeyCombo.parse(custom) ?: action.default
}

fun Settings.actionFor(combo: KeyCombo): ShortcutAction? = ShortcutAction.entries.firstOrNull { comboFor(it) == combo }

/** For each action that shares its combo with another one: that other action. */
fun Settings.shortcutConflicts(): Map<ShortcutAction, ShortcutAction> {
    val byCombo = ShortcutAction.entries.mapNotNull { a -> comboFor(a)?.let { it to a } }.groupBy({ it.first }, { it.second })
    return buildMap {
        for (actions in byCombo.values) if (actions.size > 1) for (a in actions) put(a, actions.first { it != a })
    }
}

fun Settings.shortcutLabel(action: ShortcutAction, os: HostOs): String? = comboFor(action)?.label(os)

/** Combos Vesper keeps for itself or the system: tab numbers, copy/paste, quit, pane focus. */
fun reservedCombo(combo: KeyCombo, os: HostOs): String? = when {
    !combo.shift && !combo.alt && combo.key in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9") -> "Go to tab ${combo.key}"
    !combo.shift && !combo.alt && combo.key in listOf("C", "V", "A", "X", "Z") -> "Edit commands"
    os == HostOs.MacOS && !combo.shift && combo.key in listOf("Q", "H", "M") -> "macOS"
    combo.alt && combo.key in listOf("Left", "Right", "Up", "Down") -> "Move focus between panes"
    else -> null
}

/** Key names used in stored combos. */
val KeyNames: Map<Key, String> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, k -> put(k, ('A' + i).toString()) }
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        .forEachIndexed { i, k -> put(k, i.toString()) }
    put(Key.Comma, ","); put(Key.Period, "."); put(Key.Slash, "/"); put(Key.Backslash, "\\")
    put(Key.LeftBracket, "["); put(Key.RightBracket, "]"); put(Key.Semicolon, ";"); put(Key.Apostrophe, "'")
    put(Key.Minus, "-"); put(Key.Equals, "="); put(Key.Grave, "`")
    put(Key.Enter, "Enter"); put(Key.NumPadEnter, "Enter"); put(Key.Spacebar, "Space")
    put(Key.DirectionLeft, "Left"); put(Key.DirectionRight, "Right"); put(Key.DirectionUp, "Up"); put(Key.DirectionDown, "Down")
    listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
        .forEachIndexed { i, k -> put(k, "F${i + 1}") }
}

private val KeyLabels = mapOf("Enter" to "⏎", "Space" to "Space", "Left" to "←", "Right" to "→", "Up" to "↑", "Down" to "↓")
