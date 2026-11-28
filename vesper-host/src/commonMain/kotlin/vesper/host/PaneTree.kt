package vesper.host

// The layout of one window's panes as a value: a tree whose leaves are panes and whose nodes split
// the space in two. No views and no sessions live here, so both hosts split, close and move focus
// alike and the shape is tested without a screen. On Windows the leaves become viewports of the
// window's one swap chain; on macOS each viewport is a view's frame. The removed PaneTree.swift and
// PaneTree.cs at eaeb20f are the reference.

/** How a split places its two panes. */
public enum class SplitAxis {
    /** Side by side. */
    Horizontal,

    /** One above the other. */
    Vertical,
}

/** A pane's share of the surface, in pixels from its top-left corner. */
public data class PaneViewport(
    val pane: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    /** Whether the pixel ([px], [py]) is inside; the right and bottom edges are not. */
    public fun contains(
        px: Double,
        py: Double,
    ): Boolean = px >= x && px < x + width && py >= y && py < y + height
}

/** A pane, or a split of two subtrees. */
public sealed interface PaneNode {
    public data class Leaf(
        val id: Int,
    ) : PaneNode

    /** Two subtrees along [axis]; [first] is left or top. */
    public data class Split(
        val axis: SplitAxis,
        val first: PaneNode,
        val second: PaneNode,
    ) : PaneNode

    /** The panes in reading order: left to right, top to bottom. */
    public val leaves: List<Int>
        get() =
            when (this) {
                is Leaf -> listOf(id)
                is Split -> first.leaves + second.leaves
            }
}

/** The subtree without [id]; null when [id] was all of it. The sibling takes the closed pane's whole share. */
private fun PaneNode.removing(id: Int): PaneNode? =
    when (this) {
        is PaneNode.Leaf -> {
            if (this.id == id) null else this
        }

        is PaneNode.Split -> {
            val a = first.removing(id)
            val b = second.removing(id)
            when {
                a == null -> b
                b == null -> a
                else -> PaneNode.Split(axis, a, b)
            }
        }
    }

private fun PaneNode.splitting(
    id: Int,
    axis: SplitAxis,
    created: Int,
): PaneNode =
    when (this) {
        is PaneNode.Leaf -> if (this.id == id) PaneNode.Split(axis, this, PaneNode.Leaf(created)) else this
        is PaneNode.Split -> PaneNode.Split(this.axis, first.splitting(id, axis, created), second.splitting(id, axis, created))
    }

private fun PaneNode.place(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    divider: Int,
    into: MutableList<PaneViewport>,
) {
    when (this) {
        is PaneNode.Leaf -> {
            into += PaneViewport(id, x, y, width, height)
        }

        is PaneNode.Split -> {
            val side = if (axis == SplitAxis.Horizontal) width else height
            // A surface thinner than the divider has no room for it, and nothing may start past the edge.
            val gap = minOf(divider, side)
            val room = side - gap
            // The first pane gets the rounded-down half, so sizes never drift between redraws.
            val share = room / 2
            if (axis == SplitAxis.Horizontal) {
                first.place(x, y, share, height, divider, into)
                second.place(x + share + gap, y, room - share, height, divider, into)
            } else {
                first.place(x, y, width, share, divider, into)
                second.place(x, y + share + gap, width, room - share, divider, into)
            }
        }
    }
}

/** One window's panes and the focused one; a single pane 0 at first. Ids are never reused. */
public class PaneTree {
    /** The layout; replaced, never edited, by every change. */
    public var root: PaneNode = PaneNode.Leaf(0)
        private set

    /** The pane that takes keys. */
    public var focused: Int = 0
        private set

    /** The id the next split gives its new pane. */
    public var nextId: Int = 1
        private set

    /** The panes in reading order. */
    public val panes: List<Int> get() = root.leaves

    /** Splits the focused pane, puts the new pane after it and focuses it; returns the new pane. */
    public fun splitFocused(axis: SplitAxis): Int {
        val id = nextId++
        root = root.splitting(focused, axis, id)
        focused = id
        return id
    }

    /**
     * Removes [id]. If it had the focus, the pane after it takes it, or the one before when it was
     * last. False when [id] is not here or is the last pane: closing that is the window's job.
     */
    public fun close(id: Int): Boolean {
        val order = panes
        val index = order.indexOf(id)
        if (index < 0 || order.size < 2) return false
        root = root.removing(id) ?: return false
        if (focused == id) focused = order.getOrElse(index + 1) { order[index - 1] }
        return true
    }

    /** Focuses [id] if it is a pane here. */
    public fun focus(id: Int) {
        if (id in panes) focused = id
    }

    /** Moves the focus [step] panes along the reading order, wrapping round. */
    public fun focusNext(step: Int = 1) {
        val order = panes
        val index = order.indexOf(focused)
        if (index >= 0) focused = order[(index + step % order.size + order.size) % order.size]
    }

    /**
     * Cuts a [width] by [height] pixel surface into one viewport per pane, in reading order, with
     * [divider] pixels between neighbours.
     */
    public fun layout(
        width: Int,
        height: Int,
        divider: Int = 0,
    ): List<PaneViewport> {
        require(width >= 0 && height >= 0 && divider >= 0) { "a surface of $width x $height with a divider of $divider" }
        return ArrayList<PaneViewport>().also { root.place(0, 0, width, height, divider, it) }
    }
}

/** The pane under pixel ([x], [y]) of a [PaneTree.layout], or null over a divider or outside. */
public fun List<PaneViewport>.paneAt(
    x: Double,
    y: Double,
): Int? = firstOrNull { it.contains(x, y) }?.pane
