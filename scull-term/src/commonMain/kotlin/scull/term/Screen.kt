package scull.term

import scull.grid.Cell
import scull.grid.Grid
import scull.grid.Style

// The two screens and what survives switching between them: the alternate screen, the saved cursor
// (DECSC, DECRC), and the resets (RIS, DECSTR). Separate because these replace whole parts of the
// state at once rather than editing cells.
//
// Follows xterm's ctlseqs and the esctest2 cases in tests/README.md: each screen has its own saved
// cursor; the alternate screen has no scrollback; DECSC saves the position (with the pending
// wrap), the pen, the character sets and origin mode, but not DECAWM or IRM; DECRC with nothing
// saved homes the cursor and resets those. DECSTR keeps DECAWM and the cursor position, as xterm
// does; RIS keeps the scrollback.

/** What DECSC remembers. */
internal class SavedCursor(
    val row: Int,
    val col: Int,
    val pendingWrap: Boolean,
    val style: Style,
    val origin: Boolean,
    val charsets: Charsets,
)

internal const val MAIN_SLOT = 0
internal const val ALT_SLOT = 1

private fun clearScreen(grid: Grid) {
    for (r in 0 until grid.screenRows) grid.screenRow(r)?.clear(Cell.EMPTY)
}

private fun State.savedSlot(): Int = if (altActive) ALT_SLOT else MAIN_SLOT

/** DECSC, SCOSC and `?1048` set. */
internal fun State.saveCursor() {
    saved[savedSlot()] = SavedCursor(row, col, pendingWrap, pen.style, modes.origin, charsets.copy())
}

/** DECRC, SCORC and `?1048` reset. */
internal fun State.restoreCursor() {
    val from = saved[savedSlot()]
    if (from == null) {
        goto(0, 0)
        pen.set(Style.DEFAULT)
        modes.origin = false
        charsets.reset()
        return
    }
    goto(from.row, from.col)
    // DECAWM is not saved, so a wrap saved under it does not come back once it is off.
    pendingWrap = from.pendingWrap && modes.autowrap
    pen.set(from.style)
    modes.origin = from.origin
    charsets.assign(from.charsets)
}

/** Swaps the shown and the hidden grid with their images. Style ids belong to one grid, so the pen must intern again on the other. */
private fun State.swapScreens() {
    val shown = grid
    grid = alt
    alt = shown
    val shownImages = images
    images = hiddenImages
    hiddenImages = shownImages
    altActive = !altActive
    pen.forget()
}

/** Enters the alternate screen; [clear] blanks it first (`?1049`). */
internal fun State.enterAltScreen(clear: Boolean) {
    if (!altActive) swapScreens()
    if (clear) {
        clearScreen(grid)
        images = ScreenImages()
    }
}

/** Leaves the alternate screen; [clear] blanks it on the way (`?1047`). Its images go either way, as in foot. */
internal fun State.leaveAltScreen(clear: Boolean) {
    if (!altActive) return
    if (clear) clearScreen(grid)
    images = ScreenImages()
    swapScreens()
}

/** RIS: back to the power-on state, keeping the scrollback and any replies and events not yet drained. */
internal fun State.fullReset() {
    leaveAltScreen(clear = true)
    clearScreen(grid)
    images = ScreenImages()
    sixel = null
    goto(0, 0)
    pen.set(Style.DEFAULT)
    modes.reset()
    resetMargins()
    tabs.reset()
    charsets.reset()
    saved.fill(null)
    lastChar = NO_CHAR
    links.open = null
}

/**
 * DECSTR: the soft reset of DEC STD 070 as xterm applies it. Modes it does not list (DECAWM, LNM,
 * paste, focus, 2026, 2027) are kept, and so is the cursor position.
 */
internal fun State.softReset() {
    modes.insert = false
    modes.origin = false
    modes.cursorKeys = false
    modes.keypadApp = false
    modes.cursorVisible = true
    modes.leftRightMargins = false
    resetMargins()
    pen.set(Style.DEFAULT)
    charsets.reset()
    saved[savedSlot()] = null
}
