package scull.grid

// The ring of rows shared by the screen and its scrollback, and the owner of the style and cluster
// tables those rows refer to. Separate from the row so the offset arithmetic and the sweep policy
// are tested on their own.
//
// Layout follows foot (docs/research/foot-contour.md §1.4-1.5) and Alacritty's Storage
// (docs/research/wezterm-alacritty.md §1.5): one power-of-two list of rows, physical slot
// (zero + logical) & mask. Logical row 0 is the oldest history row; the screen is the last
// screenRows. Slots are added only when first needed, so configured scrollback costs nothing until
// output fills it. Scrolling the full screen moves zero (or grows history) and recycles one row; no
// row is copied.
//
// The history limit is exactly what was asked for (up to MAX_RING_ROWS), not rounded up to the
// ring: when the ring is larger than screen plus history, the evicted oldest slot is emptied and
// left behind with no cells, so the extra slots cost one row object each at most.

/**
 * Screen plus scrollback rows one grid may hold. Scrollback is configured, not sent by the PTY, but
 * a typo must not reserve gigabytes: at this cap a ring of blank rows is a few MiB of row objects.
 */
public const val MAX_RING_ROWS: Int = 1 shl 17

// A sweep walks every row, so it runs only once it can pay for itself: after cap / SWEEP_DIVISOR
// inserts into the full table, or after rows / SWEEP_DIVISOR rows were recycled since the last one.
// Either way its cost is a constant factor of work already done.
private const val SWEEP_DIVISOR = 8
private const val NO_CLUSTER = -1

/** Index of the cursor among a resize's spots. */
private const val CURSOR = 0

/** The screen and its scrollback in one ring: [cols] by [screenRows], keeping up to [historyLimit] history rows. */
public class Grid(
    cols: Int,
    screenRows: Int,
    scrollback: Int,
    /** Resolves cell styles; a smaller cap is for tests. */
    public val styles: StyleTable = StyleTable(),
    /** Resolves cell text; a smaller cap is for tests. */
    public val clusters: ClusterTable = ClusterTable(),
) {
    init {
        require(cols in 1..MAX_COLS && screenRows in 1..MAX_RING_ROWS) { "a grid of $cols x $screenRows" }
        require(scrollback >= 0) { "scrollback $scrollback" }
    }

    public var cols: Int = cols
        private set
    public var screenRows: Int = screenRows
        private set

    /** The scrollback asked for, so a resize can clamp the history limit again. */
    private val scrollback = scrollback

    /** History rows kept at most: the scrollback asked for, clamped so screen plus history fit [MAX_RING_ROWS]. */
    public var historyLimit: Int = scrollback.coerceAtMost(MAX_RING_ROWS - screenRows)
        private set

    internal var mask: Int = ceilPowerOfTwo(screenRows + historyLimit) - 1
        private set
    internal val rows = ArrayList<Row>(screenRows)
    internal var zero = 0
        private set

    /** History rows held now. */
    public var historyLength: Int = 0
        private set

    /** How many rows the viewport is scrolled back into history. */
    public var displayOffset: Int = 0
        private set

    private var nextId = 0L
    private var recentStyle = StyleId.DEFAULT.value
    internal var recentCluster = NO_CLUSTER
    internal var releasedSinceSweep = 0
        private set

    /**
     * Bumped by every sweep that reclaimed ids. An id held outside the grid (the cursor's pen) is
     * valid only while the epoch it was interned in lasts; the most recently interned style and
     * cluster always survive.
     */
    public var internEpoch: Long = 0
        private set

    init {
        repeat(screenRows) { rows += Row(freshId(), cols) }
    }

    /** Ring slots: a power of two covering screen plus history limit. */
    public val capacity: Int get() = mask + 1

    /** Logical row [i]: 0 is the oldest history row, [historyLength] the top of the screen. */
    public fun row(i: Int): Row? = if (i in 0 until historyLength + screenRows) rows[phys(i)] else null

    /** Screen row [r], ignoring the viewport. */
    public fun screenRow(r: Int): Row? = if (r in 0 until screenRows) rows[phys(historyLength + r)] else null

    /** Row [r] of the viewport, which may be scrolled back into history. */
    public fun visibleRow(r: Int): Row? = if (r in 0 until screenRows) row(historyLength - displayOffset + r) else null

    /** Moves the viewport [delta] rows back into history (negative: towards the screen), clamped to what history holds. */
    public fun scrollDisplay(delta: Int) {
        displayOffset = (displayOffset.toLong() + delta).coerceIn(0, historyLength.toLong()).toInt()
    }

    /**
     * Scrolls the whole screen up [n] rows: the top rows enter history (the oldest history rows leave
     * once the limit is reached) and [n] rows of [fill] appear at the bottom. Costs one offset change
     * and one recycled row per line.
     */
    public fun scrollUp(
        n: Int,
        fill: Cell = Cell.EMPTY,
    ) {
        repeat(n.coerceIn(0, screenRows)) { advance(fill) }
    }

    /** Scrolls screen rows [start] until [end] up [n] rows (DECSTBM). The full screen feeds history; a partial region rotates in place and drops its top. */
    public fun scrollRegionUp(
        start: Int,
        end: Int,
        n: Int,
        fill: Cell = Cell.EMPTY,
    ) {
        val (from, to) = clampRegion(start, end)
        if (from == 0 && to == screenRows) return scrollUp(n, fill)
        val count = n.coerceIn(0, to - from)
        for (i in from until to - count) swapScreen(i, i + count)
        recycleScreen(to - count, to, fill)
    }

    /** Scrolls screen rows [start] until [end] down [n] rows (RI, SD). History is never touched; rows pushed off the bottom are dropped. */
    public fun scrollRegionDown(
        start: Int,
        end: Int,
        n: Int,
        fill: Cell = Cell.EMPTY,
    ) {
        val (from, to) = clampRegion(start, end)
        val count = n.coerceIn(0, to - from)
        for (i in to - 1 downTo from + count) swapScreen(i, i - count)
        recycleScreen(from, from + count, fill)
    }

    /** Drops every history row (ED 3); the screen is unchanged. */
    public fun clearHistory() {
        for (i in 0 until historyLength) rows[phys(i)].release()
        releasedSinceSweep += historyLength
        zero = phys(historyLength)
        historyLength = 0
        displayOffset = 0
    }

    /**
     * Resizes to [cols] by [screenRows] and moves [cursor] and every point in [points] with its cell.
     *
     * With [Reflow.REWRAP] and a new width, history and screen are rewrapped together in one pass:
     * soft-wrapped rows (see [Row.wrapped], set by whoever wraps) are joined into lines and cut again,
     * wide characters move whole to the next row behind a [CellFlags.LEADING_SPACER], and styles,
     * clusters and links travel with their cells. A row that starts where an old row started keeps
     * that row's id, and its generation when its content is unchanged; every other row gets a fresh
     * id. A frame repaints everything after a resize anyway.
     *
     * Then trailing blank rows below every point are dropped, the cursor's row is kept on the screen
     * (rows that do not fit below it are lost), the screen is the last [screenRows] rows, and history
     * keeps at most the configured scrollback, evicting the oldest rows. A scrolled-back viewport stays
     * on the line at its top. The cursor ends on the screen; other points (a saved cursor among them)
     * may end in history, and the caller clamps those that must be on the screen.
     *
     * The sizes come from the UI and are untrusted: a size outside 1..[MAX_COLS] by 1..[MAX_RING_ROWS]
     * throws [IllegalArgumentException] and changes nothing, and a point outside the grid is clamped
     * into it first and reported as clamped. The same size moves nothing.
     */
    public fun resize(
        cols: Int,
        screenRows: Int,
        reflow: Reflow,
        cursor: TrackPoint,
        points: List<TrackPoint> = emptyList(),
    ): Tracked {
        require(cols in 1..MAX_COLS && screenRows in 1..MAX_RING_ROWS) { "a grid of $cols x $screenRows" }
        val total = historyLength + this.screenRows
        val viewport = displayOffset > 0
        val spots = ArrayList<Spot>(points.size + 2)
        spots += cursor.clampIn(total, this.cols)
        // The viewport follows the line at its top, right after the cursor.
        if (viewport) spots += TrackPoint((historyLength - displayOffset).coerceAtLeast(0), 0).clampIn(total, this.cols)
        points.mapTo(spots) { it.clampIn(total, this.cols) }
        if (cols != this.cols || screenRows != this.screenRows) {
            val old = takeRows(total)
            val laid = if (reflow == Reflow.REWRAP && cols != this.cols) rewrap(old, cols, spots, ::freshId) else truncate(old, cols, spots)
            layOut(laid, cols, screenRows, spots)
            if (viewport) displayOffset = (historyLength - spots[1].row).coerceAtLeast(0)
        }
        val done = spots.map { TrackPoint(it.row, it.col, it.lost) }
        return Tracked(done[0], done.subList(if (viewport) 2 else 1, done.size))
    }

    /** Every live row, oldest first, out of the ring. */
    private fun takeRows(total: Int): Array<Row?> {
        val old = Array<Row?>(total) { rows[phys(it)] }
        rows.clear()
        return old
    }

    /** Splits [laid] into history and screen and installs them as the ring. */
    private fun layOut(
        laid: ArrayList<Row>,
        cols: Int,
        screen: Int,
        spots: List<Spot>,
    ) {
        val lowestPoint = spots.maxOf { it.row }
        var blankTail = 0
        while (laid.size - 1 - blankTail > lowestPoint && laid[laid.size - 1 - blankTail].isBlank) blankTail++
        // Blank rows go only where they would push content into history.
        val keep = maxOf(laid.size - blankTail, minOf(laid.size, screen))
        laid.subList(keep, laid.size).clear()
        val fits = spots[CURSOR].row + screen
        if (laid.size > fits) {
            laid.subList(fits, laid.size).clear()
            releasedSinceSweep += keep - fits
            for (s in spots) {
                if (s.row >= fits) {
                    s.row = fits - 1
                    s.col = cols - 1
                    s.lost = true
                }
            }
        }
        while (laid.size < screen) laid += Row(freshId(), cols)
        val limit = scrollback.coerceAtMost(MAX_RING_ROWS - screen)
        val evicted = (laid.size - screen - limit).coerceAtLeast(0)
        laid.subList(0, evicted).clear()
        releasedSinceSweep += evicted
        for (s in spots) {
            if (s.row < evicted) {
                s.row = 0
                s.col = 0
                s.lost = true
            } else {
                s.row -= evicted
            }
        }
        historyLength = laid.size - screen
        historyLimit = limit
        mask = ceilPowerOfTwo(screen + limit) - 1
        zero = 0
        rows.addAll(laid)
        this.cols = cols
        screenRows = screen
        displayOffset = 0
    }

    /**
     * The id of [style]. When the table is full, unused styles are swept first if that pays off:
     * after an eighth of the cap in inserts, or an eighth of the ring in recycled rows, since the
     * last sweep.
     */
    public fun internStyle(style: Style): StyleId {
        var id = styles.tryIntern(style)
        if (id == FULL && sweepPays(styles.insertsSinceSweep, styles.cap)) {
            sweep()
            id = styles.tryIntern(style)
        }
        if (id == FULL) throw GridException.StyleTableFull(styles.cap)
        recentStyle = id
        return StyleId(id)
    }

    /** The id of a multi-code-point cluster, sweeping like [internStyle]. */
    public fun internCluster(text: String): ClusterId {
        var id = clusters.tryIntern(text)
        if (id == FULL && sweepPays(clusters.insertsSinceSweep, clusters.cap)) {
            sweep()
            id = clusters.tryIntern(text)
        }
        if (id == FULL) throw GridException.ClusterTableFull(clusters.cap)
        recentCluster = id
        return ClusterId(id)
    }

    internal fun phys(logical: Int): Int = (zero + logical) and mask

    internal fun freshId(): RowId = RowId(++nextId)

    private fun advance(fill: Cell) {
        if (historyLimit > 0) rows[phys(historyLength)].compact()
        if (historyLength < historyLimit) {
            historyLength++
        } else {
            val oldest = zero
            zero = (zero + 1) and mask
            releasedSinceSweep++
            // With a ring larger than screen plus history, the oldest slot is not the one reused below.
            if (phys(historyLength + screenRows - 1) != oldest && oldest < rows.size) rows[oldest].release()
        }
        if (displayOffset > 0) displayOffset = (displayOffset + 1).coerceAtMost(historyLength)
        install(phys(historyLength + screenRows - 1), fill)
    }

    /** Starts a new line in slot [at], adding the slot when it is the first use of that part of the ring. */
    private fun install(
        at: Int,
        fill: Cell,
    ) {
        while (rows.size <= at) rows += Row(RowId.NONE, cols)
        rows[at].recycle(freshId(), fill)
    }

    private fun clampRegion(
        start: Int,
        end: Int,
    ): Pair<Int, Int> {
        val to = end.coerceIn(0, screenRows)
        return start.coerceIn(0, to) to to
    }

    private fun swapScreen(
        a: Int,
        b: Int,
    ) {
        val x = phys(historyLength + a)
        val y = phys(historyLength + b)
        rows[x] = rows[y].also { rows[y] = rows[x] }
    }

    private fun recycleScreen(
        from: Int,
        to: Int,
        fill: Cell,
    ) {
        releasedSinceSweep += to - from
        for (r in from until to) install(phys(historyLength + r), fill)
    }

    private fun sweepPays(
        inserts: Int,
        cap: Int,
    ): Boolean = inserts.toLong() * SWEEP_DIVISOR >= cap || releasedSinceSweep.toLong() * SWEEP_DIVISOR >= rows.size

    private fun sweep() {
        val styleMarks = styles.marks()
        val clusterMarks = clusters.marks()
        styleMarks.mark(recentStyle)
        clusterMarks.mark(recentCluster)
        for (row in rows) row.mark(styleMarks, clusterMarks)
        val freed = styles.sweep(styleMarks) + clusters.sweep(clusterMarks)
        releasedSinceSweep = 0
        if (freed > 0) internEpoch++
    }
}

private fun ceilPowerOfTwo(n: Int): Int {
    var p = 1
    while (p < n) p = p shl 1
    return p
}
