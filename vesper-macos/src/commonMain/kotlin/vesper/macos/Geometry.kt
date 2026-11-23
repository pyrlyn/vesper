package vesper.macos

import kotlin.math.floor

/** Space between the window's edge and the text, in points. */
internal const val PADDING = 4.0

/** One cell in points. */
internal data class CellSize(
    val width: Double,
    val height: Double,
) {
    init {
        require(width > 0 && height > 0) { "a cell of $width by $height" }
    }
}

/** A size or position in whole cells. */
internal data class Cells(
    val cols: Int,
    val rows: Int,
)

/** How many whole cells fit in a view of [width] by [height] points; at least one each way. */
internal fun gridSize(
    width: Double,
    height: Double,
    cell: CellSize,
): Cells =
    Cells(
        floor((width - 2 * PADDING) / cell.width).toInt().coerceAtLeast(1),
        floor((height - 2 * PADDING) / cell.height).toInt().coerceAtLeast(1),
    )

/** The view size, in points, that holds [cells] exactly. */
internal fun viewSize(
    cells: Cells,
    cell: CellSize,
): Pair<Double, Double> = cells.cols * cell.width + 2 * PADDING to cells.rows * cell.height + 2 * PADDING

/** The cell under ([x], [y]) in a top-down view, clamped to the [grid] so a drag past the edge still lands on it. */
internal fun cellAt(
    x: Double,
    y: Double,
    cell: CellSize,
    grid: Cells,
): Cells =
    Cells(
        floor((x - PADDING) / cell.width).toInt().coerceIn(0, grid.cols - 1),
        floor((y - PADDING) / cell.height).toInt().coerceIn(0, grid.rows - 1),
    )
