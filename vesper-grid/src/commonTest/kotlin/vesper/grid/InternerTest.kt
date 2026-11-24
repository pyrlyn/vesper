package vesper.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val CAP = 4

class InternerTest {
    private fun table() = Interner(CAP, listOf(0))

    @Test
    fun sameValueGetsSameId() {
        val t = table()
        val a = t.intern(7)
        assertEquals(a, t.intern(7))
        assertEquals(2, t.size)
    }

    @Test
    fun insertPastCapIsRefusedButLookupStillWorks() {
        val t = table()
        for (v in 1 until CAP) assertNotEquals(FULL, t.intern(v))
        assertEquals(FULL, t.intern(99))
        assertEquals(1, t.intern(1))
        assertEquals(CAP, t.size)
    }

    @Test
    fun sweepFreesUnmarkedIdsForReuseAndKeepsPinned() {
        val t = table()
        val a = t.intern(1)
        val b = t.intern(2)
        val live = t.marks()
        live.mark(a)
        assertEquals(1, t.sweep(live))
        assertEquals(0, t[0], "pinned survives without a mark")
        assertEquals(1, t[a])
        assertNull(t[b])
        assertEquals(b, t.intern(3), "freed id is reused")
        assertEquals(1, t.insertsSinceSweep)
    }

    @Test
    fun internsBetweenMarksAndSweepSurvive() {
        val t = table()
        val a = t.intern(1)
        var live = t.marks()
        live.mark(a)
        val appended = t.intern(2)
        assertEquals(0, t.sweep(live), "stale marks free nothing")
        assertEquals(2, t[appended])

        // A freed id handed out again lies inside the marks' length.
        live = t.marks()
        live.mark(a)
        assertEquals(1, t.sweep(live))
        live = t.marks()
        val reused = t.intern(3)
        assertEquals(appended, reused, "freed id is reused")
        assertEquals(0, t.sweep(live))
        assertEquals(3, t[reused])
        assertEquals(1, t[a])

        assertEquals(2, t.sweep(t.marks()), "fresh marks sweep as before")
        assertEquals(0, t[0])
    }

    @Test
    fun markPastTheTableIsIgnored() {
        val live = table().marks()
        live.mark(Int.MAX_VALUE)
        live.mark(-1)
        assertFalse(live.isMarked(Int.MAX_VALUE))
        assertFalse(live.isMarked(-1))
    }
}
