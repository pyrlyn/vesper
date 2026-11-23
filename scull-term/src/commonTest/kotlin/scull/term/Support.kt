package scull.term

import scull.grid.Cell
import scull.grid.CellFlags
import scull.grid.Row
import scull.unicode.appendCodePoint
import kotlin.random.Random

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

private const val MAX_PIECES = 400

/** Pieces that reach deep into the dispatch table. */
private val FRAGMENTS: List<ByteArray> =
    listOf(
        "$CSI",
        ESC,
        ";",
        ":",
        "0",
        "1",
        "2",
        "3",
        "5",
        "9",
        "65535",
        "99999",
        "@",
        "A",
        "B",
        "C",
        "D",
        "E",
        "F",
        "G",
        "H",
        "I",
        "J",
        "K",
        "L",
        "M",
        "P",
        "S",
        "T",
        "X",
        "Z",
        "a",
        "b",
        "d",
        "e",
        "g",
        "m",
        "r",
        "#8",
        "?",
        ">",
        "!p",
        "\$p",
        "h",
        "l",
        "n",
        "c",
        "s",
        "u",
        "4",
        "6",
        "7",
        "8",
        "20",
        "47",
        "69",
        "1049",
        "2027",
        "(0",
        ")0",
        "\u000E",
        "\u000F",
        "\r",
        "\n",
        "\t",
        "\b",
        "中",
        "́",
        "‍",
        "👨",
        "️",
    ).map { it.encodeToByteArray() }

/** Random PTY output that reaches deep into the dispatch table; shared by the tests that throw noise at a terminal. */
internal fun Random.stream(): ByteArray {
    var out = ByteArray(0)
    repeat(nextInt(MAX_PIECES)) {
        out += if (nextBoolean()) byteArrayOf(nextInt(256).toByte()) else FRAGMENTS[nextInt(FRAGMENTS.size)]
    }
    return out
}
