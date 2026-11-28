package vesper.host

import vesper.host.PaneNode.Leaf
import vesper.host.PaneNode.Split
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaneTreeTest {
    @Test
    fun splittingFocusesTheNewPaneAfterTheOldOne() {
        val tree = PaneTree()
        assertEquals(listOf(0), tree.panes)
        assertEquals(0, tree.focused)
        val right = tree.splitFocused(SplitAxis.Horizontal)
        assertEquals(listOf(0, right), tree.panes)
        assertEquals(right, tree.focused)
        // Splitting the first pane again nests inside its share only.
        tree.focus(0)
        val below = tree.splitFocused(SplitAxis.Vertical)
        assertEquals(listOf(0, below, right), tree.panes)
        assertEquals(Split(SplitAxis.Horizontal, Split(SplitAxis.Vertical, Leaf(0), Leaf(below)), Leaf(right)), tree.root)
    }

    @Test
    fun closingGivesTheShareToTheSiblingAndMovesFocus() {
        val tree = PaneTree()
        val b = tree.splitFocused(SplitAxis.Horizontal)
        val c = tree.splitFocused(SplitAxis.Vertical)
        assertTrue(tree.close(c))
        assertEquals(Split(SplitAxis.Horizontal, Leaf(0), Leaf(b)), tree.root)
        assertEquals(b, tree.focused, "the pane before takes it when the last one closes")
        tree.focus(0)
        assertTrue(tree.close(0))
        assertEquals(Leaf(b), tree.root)
        assertEquals(b, tree.focused, "the pane after takes it")
        assertFalse(tree.close(b), "the last pane is the window's to close")
        assertFalse(tree.close(99))
        // Ids are never reused, so a stale id cannot name a new pane.
        assertEquals(3, tree.splitFocused(SplitAxis.Horizontal))
    }

    @Test
    fun closingAnUnfocusedPaneKeepsTheFocus() {
        val tree = PaneTree()
        val b = tree.splitFocused(SplitAxis.Horizontal)
        val c = tree.splitFocused(SplitAxis.Horizontal)
        assertTrue(tree.close(b))
        assertEquals(c, tree.focused)
        assertEquals(listOf(0, c), tree.panes)
    }

    @Test
    fun focusWalksTheReadingOrderAndWraps() {
        val tree = PaneTree()
        val b = tree.splitFocused(SplitAxis.Horizontal)
        val c = tree.splitFocused(SplitAxis.Vertical)
        tree.focusNext()
        assertEquals(0, tree.focused)
        tree.focusNext(-1)
        assertEquals(c, tree.focused)
        tree.focus(b)
        tree.focusNext()
        assertEquals(c, tree.focused)
        tree.focusNext(7)
        assertEquals(0, tree.focused, "7 steps over 3 panes is 1")
        tree.focus(99)
        assertEquals(0, tree.focused)
    }

    @Test
    fun theLayoutCutsTheSurfaceWithDividersAndNoDrift() {
        val tree = PaneTree()
        assertEquals(listOf(PaneViewport(0, 0, 0, 101, 50)), tree.layout(101, 50, divider = 1))
        val right = tree.splitFocused(SplitAxis.Horizontal)
        val below = tree.splitFocused(SplitAxis.Vertical)
        val views = tree.layout(101, 50, divider = 1)
        assertEquals(
            listOf(
                PaneViewport(0, 0, 0, 50, 50),
                PaneViewport(right, 51, 0, 50, 24),
                PaneViewport(below, 51, 25, 50, 25),
            ),
            views,
        )
        assertEquals(0, views.paneAt(49.9, 10.0))
        assertNull(views.paneAt(50.5, 10.0), "the divider")
        assertEquals(below, views.paneAt(60.0, 25.0))
        assertNull(views.paneAt(101.0, 0.0), "the right edge is outside")
    }

    @Test
    fun aSurfaceThinnerThanTheDividerStillFitsEveryPane() {
        val tree = PaneTree()
        tree.splitFocused(SplitAxis.Horizontal)
        val views = tree.layout(3, 10, divider = 8)
        assertEquals(listOf(0, 0), views.map { it.width })
        assertTrue(views.all { it.x + it.width <= 3 })
        assertEquals(emptyList(), PaneTree().layout(0, 0).filter { it.width > 0 })
        assertFailsWith<IllegalArgumentException> { tree.layout(-1, 10) }
    }
}
