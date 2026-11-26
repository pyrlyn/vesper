package vesper.host

import kotlin.math.floor

/** Space between the window's edge and the text, in points. */
public const val PADDING: Double = 4.0

/** One cell in points. */
public data class CellSize(
    public val width: Double,
    public val height: Double,
) {
    init {
        require(width > 0 && height > 0) { "a cell of $width by $height" }
    }
}

/** A size or position in whole cells. */
public data class Cells(
    public val cols: Int,
    public val rows: Int,
)

/** How many whole cells fit in a view of [width] by [height] points; at least one each way. */
public fun gridSize(
    width: Double,
    height: Double,
    cell: CellSize,
): Cells =
    Cells(
        floor((width - 2 * PADDING) / cell.width).toInt().coerceAtLeast(1),
        floor((height - 2 * PADDING) / cell.height).toInt().coerceAtLeast(1),
    )

/** The view size, in points, that holds [cells] exactly. */
public fun viewSize(
    cells: Cells,
    cell: CellSize,
): Pair<Double, Double> = cells.cols * cell.width + 2 * PADDING to cells.rows * cell.height + 2 * PADDING

/** The cell under ([x], [y]) in a top-down view, clamped to the [grid] so a drag past the edge still lands on it. */
public fun cellAt(
    x: Double,
    y: Double,
    cell: CellSize,
    grid: Cells,
): Cells =
    Cells(
        floor((x - PADDING) / cell.width).toInt().coerceIn(0, grid.cols - 1),
        floor((y - PADDING) / cell.height).toInt().coerceIn(0, grid.rows - 1),
    )
