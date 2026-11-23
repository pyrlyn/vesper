package scull.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val COLS = 4

private fun row(
    id: Long,
    text: String,
    wrapped: Boolean,
): Row =
    Row(RowId(id), COLS).apply {
        text.forEachIndexed { col, c -> set(col, if (c == '>') Cell.EMPTY.withFlags(CellFlags.LEADING_SPACER) else Cell.char(c.code)) }
        this.wrapped = wrapped
    }

class ReflowTest {
    @Test
    fun onlyAWrappedRowDropsItsEdgePlaceholder() {
        assertEquals(3, keptLength(row(1, "abc>", true), false, 0))
        assertEquals(4, keptLength(row(1, "abc>", false), true, 0))
        assertEquals(2, keptLength(row(1, "ab", false), true, 0))
        assertEquals(3, keptLength(row(1, "ab", false), true, 3), "a point extends the line")
    }

    @Test
    fun aRowWhoseFillIsTextKeepsEveryColumn() {
        val r = Row(RowId(1), COLS, Cell.char('z'.code))
        r.compact()
        assertEquals(COLS, keptLength(r, true, 0))
        assertEquals(Cell.EMPTY, lineFill(r))
    }

    @Test
    fun consumedRowsAreDroppedAndEmptyLinesKeepTheirId() {
        val old = arrayOf<Row?>(row(1, "abcd", true), row(2, "ef", false), row(3, "", false))
        var next = 10L
        val rows = rewrap(old, 3, emptyList()) { RowId(++next) }
        assertTrue(old.all { it == null }, "old rows were released")
        assertEquals(listOf(RowId(1), RowId(11), RowId(3)), rows.map { it.id })
        assertEquals(11L, next)
    }
}

class RowResizeTest {
    @Test
    fun aNarrowerRowLosesItsTailAndAWideCharacterCutInHalf() {
        val r = Row(RowId(1), 6)
        r.put(0, Cell.char('a'.code))
        r.put(4, Cell.char(0x4E2D), wide = true)
        r.setLink(0, 6, LinkId(3))
        val generation = r.generation
        r.setCols(5)
        assertEquals(5, r.cols)
        assertEquals(Cell.blank(StyleId.DEFAULT), r.cell(4))
        assertEquals(listOf(LinkSpan(0, 5, LinkId(3))), r.links)
        assertTrue(r.generation != generation)
    }

    @Test
    fun aWiderRowPadsWithBlanksEvenWhenItsFillIsText() {
        val r = Row(RowId(1), 3, Cell.char('z'.code))
        r.setCols(5)
        assertEquals(listOf('z'.code, 'z'.code, 'z'.code, 0, 0), (0 until 5).map { r.cell(it).codePoint })
    }

    @Test
    fun sameContentIgnoresStorageAndWidth() {
        val a = row(1, "ab", false)
        val b = row(2, "ab", false).apply { compact() }
        assertTrue(a.sameContent(b))
        b.wrapped = true
        assertTrue(!a.sameContent(b))
    }
}
