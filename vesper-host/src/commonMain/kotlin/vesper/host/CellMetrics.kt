package vesper.host

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Half up, as pixel snapping wants; Kotlin's own rounding goes half to even. */
public fun snap(value: Double): Double = floor(value + 0.5)

/** Cell geometry in pixels, shared by the renderers and the sprites. */
public class CellMetrics(
    /** Cell size; whole points times the scale. */
    public val width: Double,
    public val height: Double,
    /** From the cell's top to the baseline. */
    public val baseline: Double,
    /** From the cell's top to the middle of the underline. */
    public val underline: Double,
    /** A light line. */
    public val thickness: Double,
    /** From the baseline up to a strike-through. */
    public val strike: Double,
) {
    /** Each host adds a constructor from its own font type here. */
    public companion object {
        /**
         * The metrics of [cell] at [scale] pixels per point, from the font's measures in points:
         * [ascent] above the baseline, the underline's [underlinePosition] (up from the baseline,
         * so usually negative) and [underlineThickness], and the [xHeight].
         */
        public fun of(
            cell: CellSize,
            scale: Double,
            ascent: Double,
            underlinePosition: Double,
            underlineThickness: Double,
            xHeight: Double,
        ): CellMetrics {
            val height = cell.height * scale
            val baseline = snap(ascent * scale)
            val thickness = max(1.0, snap(underlineThickness * scale))
            val underline = min(baseline - underlinePosition * scale, height - thickness * 2)
            return CellMetrics(cell.width * scale, height, baseline, underline, thickness, xHeight * scale / 2)
        }
    }
}
