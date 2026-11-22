package scull.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FAMILY = "👨‍👩‍👧"

class TablesTest {
    private fun red(n: Int) = Style(fg = Color.rgb(n, 0, 0))

    @Test
    fun colorsKeepWhatTheApplicationNamed() {
        assertTrue(Color.DEFAULT.isDefault)
        val i = Color.indexed(200)
        assertTrue(i.isIndexed)
        assertEquals(200, i.index)
        val rgb = Color.rgb(1, 2, 3)
        assertTrue(rgb.isRgb)
        assertEquals(Triple(1, 2, 3), Triple(rgb.red, rgb.green, rgb.blue))
        assertFailsWith<IllegalArgumentException> { Color.indexed(256) }
        assertFailsWith<IllegalArgumentException> { Color.rgb(0, 0, -1) }
    }

    @Test
    fun defaultStyleIsIdZero() {
        val t = StyleTable()
        assertEquals(StyleId.DEFAULT, t.intern(Style.DEFAULT))
        assertEquals(Style.DEFAULT, t[StyleId.DEFAULT])
    }

    @Test
    fun styleTableRefusesStylesPastItsCap() {
        val t = StyleTable(3)
        t.intern(red(1))
        t.intern(red(2))
        assertEquals(3, assertFailsWith<GridException.StyleTableFull> { t.intern(red(3)) }.cap)
        assertEquals(3, t.size)
    }

    @Test
    fun fullSizeTableNeverHandsOutAnIdPast16Bits() {
        val t = StyleTable()
        var last = StyleId.DEFAULT
        for (n in 0 until MAX_STYLES) {
            try {
                last = t.intern(Style(bg = Color.rgb(n shr 8, n and 0xFF, 1)))
            } catch (e: GridException.StyleTableFull) {
                assertEquals(MAX_STYLES, e.cap)
                break
            }
        }
        assertEquals(StyleId(0xFFFF), last)
        assertEquals(MAX_STYLES, t.size)
    }

    @Test
    fun clusterRoundTripsThroughItsId() {
        val t = ClusterTable()
        val id = t.intern(FAMILY)
        assertEquals(FAMILY, t[id])
        assertEquals(id, t.intern(FAMILY))
        assertEquals(1, t.size)
    }

    @Test
    fun singleCodePointIsNotACluster() {
        val t = ClusterTable()
        assertFailsWith<GridException.ClusterTooShort> { t.intern("a") }
        assertFailsWith<GridException.ClusterTooShort> { t.intern("") }
        assertFailsWith<GridException.ClusterTooShort> { t.intern("👨") }
    }

    @Test
    fun clusterLongerThanTheCapIsRefused() {
        val t = ClusterTable()
        val atCap = "e" + "́".repeat(MAX_CLUSTER_CHARS - 1)
        t.intern(atCap)
        val e = assertFailsWith<GridException.ClusterTooLong> { t.intern(atCap + "́") }
        assertEquals(MAX_CLUSTER_CHARS + 1 to MAX_CLUSTER_CHARS, e.length to e.max)
    }

    @Test
    fun clusterTableRefusesClustersPastItsCap() {
        val t = ClusterTable(2)
        t.intern("á")
        t.intern("é")
        assertEquals(2, assertFailsWith<GridException.ClusterTableFull> { t.intern("ó") }.cap)
    }

    @Test
    fun requestedCapIsClampedToTheHardCap() {
        assertEquals(MAX_CLUSTERS, ClusterTable(Int.MAX_VALUE).cap)
        assertEquals(MAX_STYLES, StyleTable(Int.MAX_VALUE).cap)
        assertEquals(1, StyleTable(0).cap)
    }
}
