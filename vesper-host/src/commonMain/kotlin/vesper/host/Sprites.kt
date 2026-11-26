package vesper.host

import vesper.grid.Underline
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Characters drawn from geometry instead of the font: box drawing, block elements, Powerline
// separators and the underline kinds. A font's own box glyphs rarely fill the cell exactly, so lines
// break between rows; drawn to the cell's own size they always meet. The geometry is rasterised here
// in plain Kotlin, so every host draws the same pixels without a platform graphics library.

private val SPRITE_RANGES = listOf(0x2500..0x259F, 0xE0B0..0xE0BF)

/** Samples per pixel side for shapes that are not rectangles: 65 coverage levels, enough for an 8-bit alpha. */
private const val SAMPLES = 8

/** Sides of the polygon an ellipse is drawn as; its error stays far under a pixel at any cell size. */
private const val ELLIPSE_STEPS = 96

/** Segments of a rounded corner's quarter circle. */
private const val ARC_STEPS = 24

/** Whether [codePoint] is drawn from geometry. */
public fun isSprite(codePoint: Int): Boolean = SPRITE_RANGES.any { codePoint in it }

/**
 * The sprite for [code] at [cell]'s size, or null when it is none: white coverage, premultiplied,
 * with the pen at the cell's left edge and the baseline where the cell has it.
 */
public fun spriteBitmap(
    code: Int,
    cell: CellMetrics,
): Bitmap? {
    if (!isSprite(code) && spriteUnderline(code) == null) return null
    val width = ceil(cell.width).toInt()
    val height = ceil(cell.height).toInt()
    if (width !in 1..Bitmap.MAX_SIDE || height !in 1..Bitmap.MAX_SIDE) return null
    val canvas = Canvas(width, height)
    Painter(canvas, width.toDouble(), height.toDouble(), cell).paint(code)
    return canvas.bitmap(cell.baseline.toInt())
}

/** Arms of U+2500..U+257F as up, right, down, left: 0 none, 1 light, 2 heavy, 3 double. Dashes, arcs and diagonals are drawn apart (0000). */
private val ARMS: ByteArray =
    """
    0101 0202 1010 2020 0000 0000 0000 0000 0000 0000 0000 0000 0110 0210 0120 0220
    0011 0012 0021 0022 1100 1200 2100 2200 1001 1002 2001 2002 1110 1210 2110 1120
    2120 2210 1220 2220 1011 1012 2011 1021 2021 2012 1022 2022 0111 0112 0211 0212
    0121 0122 0221 0222 1101 1102 1201 1202 2101 2102 2201 2202 1111 1112 1211 1212
    2111 1121 2121 2112 2211 1122 1221 2212 1222 2122 2221 2222 0000 0000 0000 0000
    0303 3030 0310 0130 0330 0013 0031 0033 1300 3100 3300 1003 3001 3003 1310 3130
    3330 1013 3031 3033 0313 0131 0333 1303 3101 3303 1313 3131 3333 0000 0000 0000
    0000 0000 0000 0000 0001 1000 0100 0010 0002 2000 0200 0020 0201 1020 0102 2010
    """.filter {
        it in '0'..'3'
    }.map { (it - '0').toByte() }
        .toByteArray()

/** Quadrants of U+2596..U+259F: 1 upper left, 2 upper right, 4 lower left, 8 lower right. */
private val QUADRANTS = intArrayOf(4, 8, 1, 13, 9, 7, 11, 2, 6, 14)

/** Coverage from 0 to 1 per pixel, top row first; paint lands over what is there at [opacity]. */
private class Canvas(
    val width: Int,
    val height: Int,
) {
    private val alpha = FloatArray(width * height)
    var opacity = 1.0

    private fun cover(
        x: Int,
        y: Int,
        coverage: Double,
    ) {
        if (coverage <= 0) return
        val at = y * width + x
        val paint = min(coverage, 1.0) * opacity
        alpha[at] = (alpha[at] + paint * (1 - alpha[at])).toFloat()
    }

    /** A rectangle, either corner first, each pixel covered by the share of it the rectangle takes. */
    fun rect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        val x0 = min(x, x + w)
        val x1 = max(x, x + w)
        val y0 = min(y, y + h)
        val y1 = max(y, y + h)
        for (py in max(0, floor(y0).toInt()) until min(height, ceil(y1).toInt())) {
            val down = min(y1, py + 1.0) - max(y0, py.toDouble())
            for (px in max(0, floor(x0).toInt()) until min(width, ceil(x1).toInt())) {
                cover(px, py, down * (min(x1, px + 1.0) - max(x0, px.toDouble())))
            }
        }
    }

    /** The pixels between ([x0], [y0]) and ([x1], [y1]), each covered by its share of samples [inside] takes. */
    fun sample(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        inside: (Double, Double) -> Boolean,
    ) {
        for (py in max(0, floor(y0).toInt()) until min(height, ceil(y1).toInt())) {
            for (px in max(0, floor(x0).toInt()) until min(width, ceil(x1).toInt())) {
                var hits = 0
                for (j in 0 until SAMPLES) {
                    for (i in 0 until SAMPLES) {
                        if (inside(px + (i + 0.5) / SAMPLES, py + (j + 0.5) / SAMPLES)) hits++
                    }
                }
                cover(px, py, hits.toDouble() / (SAMPLES * SAMPLES))
            }
        }
    }

    /** White at each pixel's coverage, premultiplied, as the atlas takes glyphs. */
    fun bitmap(top: Int): Bitmap {
        val pixels = ByteArray(width * height * 4)
        for (i in alpha.indices) {
            val value = (alpha[i] * 255 + 0.5).toInt().coerceIn(0, 255).toByte()
            pixels.fill(value, i * 4, i * 4 + 4)
        }
        return Bitmap(width, height, 0, top, false, pixels)
    }
}

/** Polylines as flat x, y pairs; a closed one ends on its first point. */
private class Path {
    val lines = ArrayList<DoubleArray>()
    private var current: ArrayList<Double>? = null

    fun moveTo(
        x: Double,
        y: Double,
    ) {
        finish()
        current = arrayListOf(x, y)
    }

    fun lineTo(
        x: Double,
        y: Double,
    ) {
        current?.apply {
            add(x)
            add(y)
        }
    }

    fun close() {
        current?.let { lineTo(it[0], it[1]) }
        finish()
    }

    fun ellipse(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        val rx = w / 2
        val ry = h / 2
        moveTo(x + w, y + ry)
        for (i in 1 until ELLIPSE_STEPS) {
            val a = 2 * PI * i / ELLIPSE_STEPS
            lineTo(x + rx + rx * cos(a), y + ry + ry * sin(a))
        }
        close()
    }

    fun finish() {
        current?.takeIf { it.size >= 4 }?.let { lines.add(it.toDoubleArray()) }
        current = null
    }

    /** Even-odd, with every polyline taken as closed. */
    fun contains(
        x: Double,
        y: Double,
    ): Boolean {
        var inside = false
        for (line in lines) {
            val n = line.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = line[i * 2]
                val yi = line[i * 2 + 1]
                val xj = line[j * 2]
                val yj = line[j * 2 + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Whether ([x], [y]) is within [reach] of a segment. */
    fun near(
        x: Double,
        y: Double,
        reach: Double,
    ): Boolean {
        val limit = reach * reach
        for (line in lines) {
            for (i in 0 until line.size / 2 - 1) {
                val ax = line[i * 2]
                val ay = line[i * 2 + 1]
                val dx = line[i * 2 + 2] - ax
                val dy = line[i * 2 + 3] - ay
                val length = dx * dx + dy * dy
                val along = if (length == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0.0, 1.0)
                val ex = ax + along * dx - x
                val ey = ay + along * dy - y
                if (ex * ex + ey * ey <= limit) return true
            }
        }
        return false
    }

    /** The bounds of every point, as left, top, right, bottom. */
    fun bounds(): DoubleArray {
        val box = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (line in lines) {
            for (i in 0 until line.size / 2) {
                box[0] = min(box[0], line[i * 2])
                box[1] = min(box[1], line[i * 2 + 1])
                box[2] = max(box[2], line[i * 2])
                box[3] = max(box[3], line[i * 2 + 1])
            }
        }
        return box
    }
}

private class Painter(
    val canvas: Canvas,
    val w: Double,
    val h: Double,
    val cell: CellMetrics,
) {
    val t = cell.thickness
    val cx = floor(w / 2)
    val cy = floor(h / 2)

    fun paint(code: Int) {
        when (code) {
            in 0x2504..0x250B -> {
                dashes(code - 0x2504, if (code < 0x2508) 3 else 4)
            }

            in 0x254C..0x254F -> {
                dashes(code - 0x254C, 2)
            }

            in 0x256D..0x2570 -> {
                arc(code)
            }

            in 0x2571..0x2573 -> {
                diagonals(code)
            }

            in 0x2500..0x257F -> {
                lines(IntArray(4) { ARMS[(code - 0x2500) * 4 + it].toInt() })
            }

            0x2580 -> {
                fill(0.0, 0.0, 1.0, 0.5)
            }

            in 0x2581..0x2588 -> {
                fill(0.0, 1 - (code - 0x2580) / 8.0, 1.0, 1.0)
            }

            in 0x2589..0x258F -> {
                fill(0.0, 0.0, (0x2590 - code) / 8.0, 1.0)
            }

            0x2590 -> {
                fill(0.5, 0.0, 1.0, 1.0)
            }

            in 0x2591..0x2593 -> {
                // Shades as even coverage, not a dither that beats against the pixel grid.
                canvas.opacity = (code - 0x2590) / 4.0
                fill(0.0, 0.0, 1.0, 1.0)
                canvas.opacity = 1.0
            }

            0x2594 -> {
                fill(0.0, 0.0, 1.0, 1 / 8.0)
            }

            0x2595 -> {
                fill(7 / 8.0, 0.0, 1.0, 1.0)
            }

            in 0x2596..0x259F -> {
                val mask = QUADRANTS[code - 0x2596]
                if (mask and 1 != 0) fill(0.0, 0.0, 0.5, 0.5)
                if (mask and 2 != 0) fill(0.5, 0.0, 1.0, 0.5)
                if (mask and 4 != 0) fill(0.0, 0.5, 0.5, 1.0)
                if (mask and 8 != 0) fill(0.5, 0.5, 1.0, 1.0)
            }

            in 0xE0B0..0xE0BF -> {
                powerline(code)
            }

            else -> {
                spriteUnderline(code)?.let(::underline)
            }
        }
    }

    private fun rect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) = canvas.rect(x, y, width, height)

    private fun fill(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        val left = snap(x0 * w)
        val top = snap(y0 * h)
        rect(left, top, snap(x1 * w) - left, snap(y1 * h) - top)
    }

    /**
     * A pixel-aligned bar of thickness [th] centred [offset] off the cell's middle, from [start] past the
     * centre (negative: short of it) to the edge in [direction] (0 up, 1 right, 2 down, 3 left).
     */
    private fun bar(
        direction: Int,
        start: Double,
        offset: Double,
        th: Double,
    ) {
        val horizontal = direction % 2 == 1
        val across = floor((if (horizontal) cy else cx) + offset - th / 2)
        val middle = if (horizontal) cx else cy
        val forward = direction == 1 || direction == 2
        val from = if (forward) middle + start else 0.0
        val to = if (forward) (if (horizontal) w else h) else middle - start
        if (horizontal) rect(from, across, to - from, th) else rect(across, from, th, to - from)
    }

    private fun lines(arm: IntArray) {
        val d = t
        val weight = { dir: Int -> if (arm[dir] == 2) t * 2 else t }
        val widest = (0 until 4).maxOf { if (arm[it] == 3) d * 2 + t else weight(it) }
        for (dir in 0 until 4) {
            if (arm[dir] == 0) continue
            // The perpendicular arms, on the side of a negative offset first.
            val opposite = arm[(dir + 2) % 4]
            val sides = if (dir % 2 == 1) intArrayOf(arm[0], arm[2]) else intArrayOf(arm[3], arm[1])
            if (arm[dir] == 3) {
                // Each of the two lines stops at a double line crossing it, runs on into a continuing
                // arm, meets a single line at the centre, and otherwise turns the outer corner.
                for (i in 0..1) {
                    val side = sides[i]
                    val other = sides[1 - i]
                    val start =
                        when {
                            side == 3 -> d
                            opposite != 0 -> -d - t / 2
                            side == 1 || side == 2 || other == 1 || other == 2 -> -t / 2
                            else -> -d - t / 2
                        }
                    bar(dir, start, if (i == 0) -d else d, t)
                }
            } else {
                // A stem off a double line stops at its near line, or at the far one where the double
                // line turns a corner.
                val start =
                    if (opposite == 0 && 3 in sides) {
                        if (sides.all { it != 0 }) d else -d
                    } else {
                        -widest / 2
                    }
                bar(dir, start, 0.0, weight(dir))
            }
        }
    }

    private fun dashes(
        index: Int,
        segments: Int,
    ) {
        val vertical = index % 4 >= 2
        val th = if (index % 2 == 1) t * 2 else t
        val slot = (if (vertical) h else w) / segments
        val gap = max(1.0, snap(slot / 3))
        for (i in 0 until segments) {
            val from = snap(i * slot + gap / 2)
            val to = snap((i + 1) * slot - gap / 2)
            if (vertical) rect(floor(cx - th / 2), from, th, to - from) else rect(from, floor(cy - th / 2), to - from, th)
        }
    }

    private fun arc(code: Int) {
        // The stroke's centre line sits where a light bar's centre does.
        val px = floor(cx - t / 2) + t / 2
        val py = floor(cy - t / 2) + t / 2
        val down = code == 0x256D || code == 0x256E
        val right = code == 0x256D || code == 0x2570
        val edge = if (right) w else 0.0
        val radius = max(1.0, min(w, h) / 2 - t)
        // Towards the edge the corner turns to, and towards the edge it comes from.
        val sx = if (right) 1.0 else -1.0
        val sy = if (down) 1.0 else -1.0
        val ox = px + sx * radius
        val oy = py + sy * radius
        path(fill = false) { path ->
            path.moveTo(px, if (down) h else 0.0)
            for (i in 0..ARC_STEPS) {
                val a = PI / 2 * i / ARC_STEPS
                path.lineTo(ox - sx * radius * cos(a), oy - sy * radius * sin(a))
            }
            path.lineTo(edge, py)
        }
    }

    private fun diagonals(code: Int) =
        path(fill = false) { path ->
            if (code != 0x2572) line(path, w, 0.0, 0.0, h)
            if (code != 0x2571) line(path, 0.0, 0.0, w, h)
        }

    /**
     * Powerline separators: even codes are solid, odd ones their outline. The solid shapes reach the cell
     * edges, so they meet the background of the next segment without a seam.
     */
    private fun powerline(code: Int) {
        val solid = code % 2 == 0
        path(fill = solid) { path ->
            val points = { coords: DoubleArray ->
                path.moveTo(coords[0] * w, coords[1] * h)
                for (i in 2 until coords.size step 2) path.lineTo(coords[i] * w, coords[i + 1] * h)
            }
            when (code) {
                0xE0B0, 0xE0B1 -> {
                    points(doubleArrayOf(0.0, 0.0, 1.0, 0.5, 0.0, 1.0))
                }

                0xE0B2, 0xE0B3 -> {
                    points(doubleArrayOf(1.0, 0.0, 0.0, 0.5, 1.0, 1.0))
                }

                in 0xE0B4..0xE0B7 -> {
                    // Half an ellipse bulging away from the edge it stands on; the outline is inset so
                    // the whole stroke stays in the cell.
                    val inset = if (solid) 0.0 else t / 2
                    val x = if (code < 0xE0B6) -w else inset
                    path.ellipse(x, inset, w * 2 - inset, h - inset * 2)
                }

                0xE0B8 -> {
                    points(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 0.0, 1.0))
                }

                0xE0BA -> {
                    points(doubleArrayOf(1.0, 0.0, 1.0, 1.0, 0.0, 1.0))
                }

                0xE0BC -> {
                    points(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0))
                }

                0xE0BE -> {
                    points(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0))
                }

                // The outlines of the corner triangles are their hypotenuses.
                0xE0B9, 0xE0BF -> {
                    points(doubleArrayOf(0.0, 0.0, 1.0, 1.0))
                }

                else -> {
                    points(doubleArrayOf(1.0, 0.0, 0.0, 1.0))
                }
            }
            if (solid) path.close()
        }
    }

    private fun line(
        path: Path,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
    }

    /** Fills [build]'s polylines, or strokes them a light line wide. */
    private fun path(
        fill: Boolean,
        build: (Path) -> Unit,
    ) {
        val path = Path()
        build(path)
        path.finish()
        if (path.lines.isEmpty()) return
        val reach = if (fill) 0.0 else t / 2
        val (left, top, right, bottom) = path.bounds()
        canvas.sample(left - reach, top - reach, right + reach, bottom + reach) { x, y ->
            if (fill) path.contains(x, y) else path.near(x, y, reach)
        }
    }

    private fun underline(kind: Underline) {
        val y = cell.underline
        when (kind) {
            Underline.Double -> {
                rect(0.0, snap(y - t * 1.5), w, t)
                rect(0.0, snap(y + t / 2), w, t)
            }

            Underline.Curly -> {
                path(fill = false) { path ->
                    // One period per cell, so neighbouring cells join.
                    val amplitude = max(1.0, t)
                    path.moveTo(0.0, y)
                    var x = 1.0
                    while (x <= w) {
                        path.lineTo(x, y - amplitude * sin(2 * PI * x / w))
                        x += 1.0
                    }
                }
            }

            Underline.Dotted -> {
                var x = 0.0
                while (x < w) {
                    rect(x, snap(y - t / 2), t, t)
                    x += t * 2
                }
            }

            Underline.Dashed -> {
                rect(snap(w * 0.15), snap(y - t / 2), snap(w * 0.7), t)
            }

            else -> {
                rect(0.0, snap(y - t / 2), w, t)
            }
        }
    }
}
