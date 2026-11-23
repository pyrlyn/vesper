package scull.term

import scull.input.ByteSink
import scull.input.KittyFlagStack
import scull.input.ModifyOtherKeys
import scull.parser.Params

// The key-encoding modes a program sets with control sequences: the kitty keyboard flag stacks
// (`CSI ? u`, `CSI > flags u`, `CSI < n u`, `CSI = flags ; mode u`) and xterm's modifyOtherKeys
// (XTMODKEYS). Separate from Modes.kt because these are not DECSET modes; scull-input encodes the
// keys, this file only keeps what the program asked for.
// https://sw.kovidgoyal.net/kitty/keyboard-protocol/#progressive-enhancement,
// https://invisible-island.net/xterm/ctlseqs/ctlseqs.html (XTMODKEYS)

/** The XTMODKEYS resource that holds modifyOtherKeys. */
private const val MODIFY_OTHER_KEYS_RESOURCE = 4
private const val LEVEL_1 = 1
private const val LEVEL_2 = 2

/** Parameter [index] as sent, sub-parameters skipped, or -1 when it is missing. */
private fun raw(
    params: Params,
    index: Int,
): Int = paramAt(params, index).let { if (it < 0) -1 else params[it] }

/** The flag stack of the screen shown: each screen has its own, so an editor on the alternate screen cannot disturb the shell's. */
private val State.kittyStack: KittyFlagStack get() = modes.kitty.stack(altActive)

/** `CSI ? u`: reports the active kitty flags. */
internal fun State.kittyQuery() {
    val out = ByteSink()
    kittyStack.writeQueryReply(out)
    replies.push(out.toByteArray())
}

/** `CSI > flags u`. */
internal fun State.kittyPush(p: Params) = kittyStack.push(raw(p, 0).coerceAtLeast(0))

/** `CSI < count u`; the stack reads a missing count as 1. */
internal fun State.kittyPop(p: Params) = kittyStack.pop(raw(p, 0))

/** `CSI = flags ; mode u`; a missing mode is the default, replace. */
internal fun State.kittySet(p: Params) = kittyStack.set(raw(p, 0).coerceAtLeast(0), raw(p, 1).coerceAtLeast(0))

/**
 * XTMODKEYS, `CSI > resource ; value m`. Only modifyOtherKeys is kept; no parameters at all reset
 * every resource and an omitted value resets this one, as in xterm. Levels past 2 are not
 * supported and leave the level as it was.
 */
internal fun State.setKeyModifierOptions(p: Params) {
    val resource = raw(p, 0)
    if (resource != -1 && resource != MODIFY_OTHER_KEYS_RESOURCE) return
    when (raw(p, 1)) {
        -1, 0 -> modes.modifyOtherKeys = ModifyOtherKeys.Off
        LEVEL_1 -> modes.modifyOtherKeys = ModifyOtherKeys.Level1
        LEVEL_2 -> modes.modifyOtherKeys = ModifyOtherKeys.Level2
    }
}

/** XTMODKEYS reset, `CSI > resource n`: disables modifyOtherKeys. */
internal fun State.disableKeyModifierOptions(p: Params) {
    if (raw(p, 0) == MODIFY_OTHER_KEYS_RESOURCE) modes.modifyOtherKeys = ModifyOtherKeys.Off
}
