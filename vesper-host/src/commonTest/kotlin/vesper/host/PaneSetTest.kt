package vesper.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class Shell(
    val id: Int,
) : AutoCloseable {
    var closed = false

    override fun close() {
        closed = true
    }
}

class PaneSetTest {
    @Test
    fun aSplitKeepsEveryShellAndACloseEndsOnlyItsOwn() {
        val made = ArrayList<Shell>()
        PaneSet { id -> Shell(id).also { made += it } }.use { set ->
            val first = set.focused
            val right = set.split(SplitAxis.Horizontal)
            val below = set.split(SplitAxis.Vertical)
            assertEquals(listOf(0, right, below), made.map { it.id }, "one shell per pane, made once")
            assertSame(first, set[0], "a split never restarts a shell")
            assertTrue(set.close(right))
            assertTrue(made[1].closed)
            assertFalse(made[0].closed || made[2].closed)
            assertNull(set[right])
            assertTrue(set.close(below))
            assertFalse(set.close(0), "the last pane is the window's")
            assertFalse(first.closed)
        }
        assertTrue(made[0].closed, "closing the set closes what is left")
    }

    @Test
    fun aShellThatCannotStartLeavesTheTreeAsItWas() {
        var refuse = true
        PaneSet { id -> if (id == 1 && refuse) error("no pty") else Shell(id) }.use { set ->
            assertFailsWith<IllegalStateException> { set.split(SplitAxis.Horizontal) }
            assertEquals(listOf(0), set.tree.panes)
            assertEquals(0, set.tree.focused)
            refuse = false
            val next = set.split(SplitAxis.Horizontal)
            assertEquals(listOf(0, next), set.tree.panes)
        }
    }

    @Test
    fun oneShellThatThrowsOnCloseDoesNotKeepTheOthersOpen() {
        val made = ArrayList<Shell>()
        val set =
            PaneSet { id ->
                object : AutoCloseable {
                    val shell = Shell(id).also { made += it }

                    override fun close() {
                        shell.close()
                        if (id == 0) error("stuck")
                    }
                }
            }
        set.split(SplitAxis.Horizontal)
        assertFailsWith<IllegalStateException> { set.close() }
        assertTrue(made.all { it.closed })
    }
}
