package vesper.macos

import vesper.term.SelectionKind

// The pure half of mouse selection: what a press does and when a drag scrolls. The view runs the
// result against the session. Ported from the removed Swift host's `SelectionGesture`.

/** What a left press does to the selection. */
internal sealed interface SelectionPress {
    data class Start(
        val kind: SelectionKind,
    ) : SelectionPress

    data object Extend : SelectionPress
}

/**
 * Two clicks take a word, three a line; Option drags a block. Shift grows a selection that is there,
 * or starts one when there is none.
 */
internal fun selectionPress(
    clicks: Int,
    shift: Boolean,
    option: Boolean,
    hasSelection: Boolean,
): SelectionPress =
    when {
        shift && hasSelection -> SelectionPress.Extend
        clicks == 2 -> SelectionPress.Start(SelectionKind.Word)
        clicks >= 3 -> SelectionPress.Start(SelectionKind.Line)
        option -> SelectionPress.Start(SelectionKind.Block)
        else -> SelectionPress.Start(SelectionKind.Cell)
    }

/** A plain click that never moves selects nothing once released; a word or line click keeps what it took. */
internal val SelectionPress.clearsOnRelease: Boolean
    get() = this is SelectionPress.Start && (kind == SelectionKind.Cell || kind == SelectionKind.Block)

/**
 * Lines to scroll the history while a drag is past the view's top (up, positive) or bottom edge
 * ([y] in top-down view points). The selection is anchored to the history, so it grows as it scrolls.
 */
internal fun dragScroll(
    y: Double,
    height: Double,
): Int =
    when {
        y < 0 -> 1
        y >= height -> -1
        else -> 0
    }
