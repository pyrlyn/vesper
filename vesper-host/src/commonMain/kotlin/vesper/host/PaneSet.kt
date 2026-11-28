package vesper.host

/**
 * One window's [PaneTree] with a [T] (a session, a view) per pane. A pane keeps its [T] through
 * every split and close of its neighbours, so a split never restarts a shell, and closing a pane
 * closes its own [T] only. [create] makes a pane's [T] from its id, once per pane.
 */
public class PaneSet<T : AutoCloseable>(
    private val create: (Int) -> T,
) : AutoCloseable {
    /** The layout; change it only through this set. */
    public val tree: PaneTree = PaneTree()

    private val items = HashMap<Int, T>()

    init {
        items[0] = create(0)
    }

    /** The [T] of [pane], or null when it is not here. */
    public operator fun get(pane: Int): T? = items[pane]

    /** The focused pane's [T]. */
    public val focused: T get() = items.getValue(tree.focused)

    /** Splits the focused pane. The new pane's [T] is made first, so a throw leaves the tree as it was. */
    public fun split(axis: SplitAxis): Int {
        val made = create(tree.nextId)
        val id = tree.splitFocused(axis)
        items[id] = made
        return id
    }

    /** Closes [pane] and its [T]. False when it is not here or is the last pane, which only the window closes. */
    public fun close(pane: Int): Boolean {
        if (!tree.close(pane)) return false
        items.remove(pane)?.close()
        return true
    }

    /** Closes every pane's [T]; one that throws does not keep the others open, and the first throw is rethrown. */
    override fun close() {
        val all = items.values.toList()
        items.clear()
        var first: Throwable? = null
        for (item in all) {
            try {
                item.close()
            } catch (thrown: Throwable) {
                first = first ?: thrown
            }
        }
        first?.let { throw it }
    }
}
