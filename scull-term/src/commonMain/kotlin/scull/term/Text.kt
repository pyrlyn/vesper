package scull.term

import scull.grid.Attrs
import scull.grid.Cell
import scull.grid.CellFlags
import scull.grid.Grid
import scull.unicode.appendCodePoint

// Cells as plain text, for copying and search: what a reader of the text gets, in reading order, while
// the frame is cut for drawing. Shared so a match is found in exactly the text a copy would give.

/** Cells that hold no text of their own: the half of a wide character behind its head, and the padding before a wide character that wrapped. */
internal const val PADDING: Int = CellFlags.SPACER or CellFlags.LEADING_SPACER

/** Appends what [cell] reads as: its text, or a space when it is blank or concealed (SGR 8), so a typed password is neither copied nor found. */
internal fun StringBuilder.appendCell(
    grid: Grid,
    cell: Cell,
) {
    val hidden = grid.styles[cell.style]?.let { it.attrs and Attrs.HIDDEN != 0 } == true
    when {
        hidden || cell.isEmpty -> append(' ')
        cell.isCluster -> append(grid.clusters[cell.clusterId] ?: MISSING_CLUSTER)
        else -> appendCodePoint(cell.codePoint)
    }
}

/** Drops the trailing spaces of the line that begins at [start]. */
internal fun StringBuilder.trimLine(start: Int) {
    var end = length
    while (end > start && this[end - 1] == ' ') end--
    setLength(end)
}

private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION = 0x80

/** [text] cut to at most [cap] UTF-8 bytes, at a character boundary. */
internal fun capUtf8(
    text: String,
    cap: Int,
): String {
    // At most three bytes per UTF-16 unit, so a text this short cannot be over.
    if (text.length <= cap / UTF8_MAX_BYTES_PER_UNIT) return text
    val bytes = text.encodeToByteArray()
    if (bytes.size <= cap) return text
    var cut = cap
    while (cut > 0 && bytes[cut].toInt() and UTF8_CONTINUATION_MASK == UTF8_CONTINUATION) cut--
    return bytes.decodeToString(0, cut)
}

/** A UTF-16 unit takes at most three UTF-8 bytes; a surrogate pair takes four for two units. */
private const val UTF8_MAX_BYTES_PER_UNIT = 3
