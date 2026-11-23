package scull.term

import scull.grid.Cell
import scull.grid.CellFlags
import scull.grid.Row
import scull.unicode.appendCodePoint

internal const val ESC = "\u001B"
internal const val CSI = "\u001B["

internal fun Terminal.feed(text: String) = feed(text.encodeToByteArray())

internal fun Terminal.cell(
    row: Int,
    col: Int,
): Cell = checkNotNull(grid.screenRow(row)) { "row $row" }.cell(col)

/** A row as the fixtures write it: `.` for an empty cell, spacers skipped, clusters whole. */
internal fun Terminal.rowText(row: Row): String =
    buildString {
        for (col in 0 until row.cols) {
            val cell = row.cell(col)
            when {
                cell.hasFlags(CellFlags.SPACER) -> Unit
                cell.isCluster -> append(grid.clusters[cell.clusterId] ?: "?")
                cell.isEmpty -> append('.')
                else -> appendCodePoint(cell.codePoint)
            }
        }
    }

internal fun Terminal.screenText(): List<String> = (0 until grid.screenRows).map { rowText(checkNotNull(grid.screenRow(it))) }
