package scull.grid

// A capped interning table with mark-and-sweep reclaim, shared by the style and cluster tables, so
// the cap, the free list and the sweep are written and tested once.
//
// Reclaim is a sweep rather than a refcount: a cell write stays one store, at the price of an
// occasional walk over every row (kitty garbage-collects its text cache the same way,
// docs/research/kitty.md §6). The grid decides when a sweep is worth it; this file only counts.

private const val WORD_SHIFT = 6
private const val WORD_MASK = Long.SIZE_BITS - 1

/** The id [Interner.intern] returns when the table is full. */
internal const val FULL = -1

/**
 * One bit per id: which entries a sweep must keep. Take the set from the table, mark every id in use,
 * then sweep, with no intern in between. A sweep with marks taken before the latest intern frees
 * nothing, so a missed mark can delay reclaim but never free a live entry.
 */
public class Marks internal constructor(
    size: Int,
    // The table's insert count when the set was made: an id handed out after that is live but
    // unmarked, so a sweep with these marks must not run. Checking the set's length would not do:
    // a freed id handed out again lies inside it.
    internal val stamp: Long,
) {
    /** A blank set for ids below [size], for tables the terminal layer keeps itself (link ids). */
    public constructor(size: Int) : this(size, 0)

    private val words = LongArray((size + WORD_MASK) shr WORD_SHIFT)

    /** Keeps [id] alive through the next sweep. Ids past the table are ignored. */
    public fun mark(id: Int) {
        val word = id shr WORD_SHIFT
        if (id >= 0 && word < words.size) words[word] = words[word] or (1L shl (id and WORD_MASK))
    }

    /** Whether a sweep keeps [id]. */
    public fun isMarked(id: Int): Boolean {
        val word = id shr WORD_SHIFT
        return id >= 0 && word < words.size && words[word] and (1L shl (id and WORD_MASK)) != 0L
    }
}

/** Values in, small ids out, at most [cap] live at once. The [pinned] values are never swept. */
internal class Interner<T : Any>(
    val cap: Int,
    pinned: List<T>,
) {
    private val slots = ArrayList<T?>()
    private val index = HashMap<T, Int>()
    private val free = ArrayList<Int>()
    private var pinnedCount = 0

    /** Inserts since the last sweep, so the grid can tell whether a sweep now would pay off. */
    var insertsSinceSweep = 0
        private set

    /** Every insert ever, so a sweep can tell its marks went stale. */
    private var inserts = 0L

    init {
        for (value in pinned) if (intern(value) != FULL) pinnedCount++
        insertsSinceSweep = 0
    }

    val size: Int get() = index.size

    operator fun get(id: Int): T? = slots.getOrNull(id)

    /** The id of [value], inserting it when absent; [FULL] when [cap] entries are live. */
    fun intern(value: T): Int {
        index[value]?.let { return it }
        if (size >= cap) return FULL
        val id =
            if (free.isNotEmpty()) {
                free.removeAt(free.lastIndex).also { slots[it] = value }
            } else {
                slots.size.also { slots += value }
            }
        index[value] = id
        insertsSinceSweep++
        inserts++
        return id
    }

    /** A blank mark set sized for this table. */
    fun marks(): Marks = Marks(slots.size, inserts)

    /**
     * Frees every unpinned entry [live] does not mark and returns how many. Marks made before the
     * latest insert free nothing: that entry, appended or reusing a freed id, is live but unmarked.
     * Skipping only delays reclaim to the next sweep.
     */
    fun sweep(live: Marks): Int {
        if (live.stamp != inserts) return 0
        var freed = 0
        for (id in pinnedCount until slots.size) {
            val value = slots[id]
            if (value == null || live.isMarked(id)) continue
            slots[id] = null
            index.remove(value)
            free += id
            freed++
        }
        insertsSinceSweep = 0
        return freed
    }
}
