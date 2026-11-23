package scull.term

// Horizontal tab stops (HTS, TBC, HT, CHT, CBT). Separate because the stop set is its own small
// table, sized to the screen and reset by RIS.

/** Power-on tab stops fall every eight columns (VT100 and xterm). */
private const val DEFAULT_TAB_WIDTH = 8

/** One flag per column: a tab stop is set there. */
internal class TabStops(
    cols: Int,
) {
    private var stops = BooleanArray(cols)

    init {
        reset()
    }

    /** The power-on stops: every eight columns, the first at column 8. */
    fun reset() = powerOn(0)

    /** A screen [cols] wide: the stops set so far are kept and new columns get the power-on stops. */
    fun resize(cols: Int) {
        val old = stops.size
        stops = stops.copyOf(cols)
        powerOn(old)
    }

    private fun powerOn(from: Int) {
        for (c in from until stops.size) stops[c] = c > 0 && c % DEFAULT_TAB_WIDTH == 0
    }

    /** HTS: a stop at [col]. */
    fun set(col: Int) {
        if (col in stops.indices) stops[col] = true
    }

    /** TBC 0: no stop at [col]. */
    fun clear(col: Int) {
        if (col in stops.indices) stops[col] = false
    }

    /** TBC 3: no stops at all. */
    fun clearAll() = stops.fill(false)

    /** The [n]th stop right of [col], or [limit] when fewer stops lie before it. */
    fun next(
        col: Int,
        n: Int,
        limit: Int,
    ): Int {
        var at = col
        repeat(n) {
            var stop = at + 1
            while (stop < limit && !isSet(stop)) stop++
            if (stop >= limit) return maxOf(limit, col)
            at = stop
        }
        return at
    }

    /** The [n]th stop left of [col], or column 0 when fewer stops lie there. */
    fun prev(
        col: Int,
        n: Int,
    ): Int {
        var at = col
        repeat(n) {
            var stop = at - 1
            while (stop >= 0 && !isSet(stop)) stop--
            if (stop < 0) return 0
            at = stop
        }
        return at
    }

    private fun isSet(col: Int): Boolean = col in stops.indices && stops[col]
}
