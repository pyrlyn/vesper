package vesper.term

import vesper.grid.Cell
import vesper.grid.Grid
import vesper.grid.GridException
import vesper.grid.Style
import vesper.grid.StyleId

// The cursor's pen: the SGR style new cells are written with, and the grid ids it was interned as.
// Separate because those ids live outside the grid: a sweep may reclaim them, so each is tagged
// with the grid's intern epoch and interned again when the epoch moves on.

/** No epoch a grid reports, so the cached id is looked up again. */
private const val STALE = -1L

/** The current SGR style and its cached ids. */
internal class Pen {
    var style: Style = Style.DEFAULT
        private set

    private var text = StyleId.DEFAULT
    private var textEpoch = STALE
    private var erase = StyleId.DEFAULT
    private var eraseEpoch = STALE

    /** Changes the style; the cached ids no longer describe it. */
    fun set(style: Style) {
        if (style != this.style) {
            this.style = style
            forget()
        }
    }

    /** Drops the cached ids, e.g. when the other grid becomes active: ids belong to one grid. */
    fun forget() {
        textEpoch = STALE
        eraseEpoch = STALE
    }

    /** The id printed cells get. */
    fun textId(grid: Grid): StyleId {
        if (textEpoch != grid.internEpoch) {
            text = intern(grid, style)
            textEpoch = grid.internEpoch
        }
        return text
    }

    /**
     * What erasing leaves behind: blank cells with the pen's background only (background colour
     * erase, as xterm and the VT220 do).
     */
    fun blank(grid: Grid): Cell {
        val bg = style.bg
        if (bg.isDefault) return Cell.EMPTY
        if (eraseEpoch != grid.internEpoch) {
            erase = intern(grid, Style(bg = bg))
            eraseEpoch = grid.internEpoch
        }
        return Cell.blank(erase)
    }
}

/** A full style table degrades to the default style rather than refusing the text. */
private fun intern(
    grid: Grid,
    style: Style,
): StyleId =
    try {
        grid.internStyle(style)
    } catch (_: GridException.StyleTableFull) {
        StyleId.DEFAULT
    }
