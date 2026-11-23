package scull.grid

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val COLS = 10
private val RED = StyleId(1)

private fun ch(c: Char) = Cell.char(c.code)

/** The row as text: a character, `_` for a wide character's spacer, `.` for anything else. */
internal fun text(r: Row): String =
    (0 until r.cols)
        .map {
            val c = r.cell(it)
            when {
                c.codePoint != 0 -> c.codePoint.toChar()
                c.hasFlags(CellFlags.SPACER) -> '_'
                else -> '.'
            }
        }.joinToString("")

class RowTest {
    private fun row() = Row(RowId(1), COLS)

    private fun typed(s: String): Row {
        val r = row()
        s.forEachIndexed { col, c -> if (c != '.') r.put(col, ch(c)) }
        return r
    }

    @Test
    fun blankRowAllocatesNoCells() {
        val r = row()
        assertTrue(r.isUniform)
        assertEquals(0, r.cellCapacity)
        assertEquals("..........", text(r))
    }

    @Test
    fun eraseWithAStyleKeepsTheRowUniform() {
        val r = row()
        r.put(3, ch('x'))
        r.fillRange(0, COLS, Cell.blank(RED))
        assertTrue(r.isUniform)
        assertTrue((0 until COLS).all { r.cell(it) == Cell.blank(RED) })
    }

    @Test
    fun touchingAColumnPastTheRowThrows() {
        val r = row()
        assertFailsWith<IndexOutOfBoundsException> { r.put(COLS, ch('x')) }
        assertFailsWith<IndexOutOfBoundsException> { r.set(COLS, ch('x')) }
        assertFailsWith<IndexOutOfBoundsException> { r.put(COLS - 1, ch('x'), wide = true) }
        assertFailsWith<IndexOutOfBoundsException> { r.cell(-1) }
        assertEquals("..........", text(r), "a refused write leaves no trace")
    }

    @Test
    fun everyVisibleChangeBumpsTheGenerationAndANoOpDoesNot() {
        val r = row()
        val g0 = r.generation
        r.set(0, Cell.EMPTY)
        assertEquals(g0, r.generation, "same cell is not a change")
        r.put(0, ch('a'))
        val g1 = r.generation
        assertNotEquals(g0, g1)
        r.setLink(0, 1, LinkId(1))
        assertNotEquals(g1, r.generation)
        val g2 = r.generation
        r.wrapped = true
        assertNotEquals(g2, r.generation)
        assertEquals(RowId(1), r.id, "edits keep the id")
    }

    @Test
    fun clearingLinksWhereNoneExistIsGenerationNeutral() {
        val r = row()
        val g0 = r.generation
        r.clearLinks(0, COLS)
        assertEquals(g0, r.generation, "nothing was linked")

        r.setLink(2, 5, LinkId(1))
        val g1 = r.generation
        assertNotEquals(g0, g1, "a link was added")
        r.clearLinks(5, COLS)
        r.clearLinks(-3, 2)
        assertEquals(g1, r.generation, "ranges that touch the link without overlapping it hold none of it")
        r.clearLinks(4, 4)
        r.clearLinks(3, 1)
        r.clearLinks(COLS, COLS + 5)
        assertEquals(g1, r.generation, "an empty range or one past the row clears nothing")

        r.clearLinks(4, 9)
        val g2 = r.generation
        assertNotEquals(g1, g2, "the link was cut short")
        assertEquals(listOf(LinkSpan(2, 4, LinkId(1))), r.links)
        r.clearLinks(0, COLS)
        val g3 = r.generation
        assertNotEquals(g2, g3, "the rest of the link was removed")
        assertTrue(r.links.isEmpty())
        r.clearLinks(0, COLS)
        assertEquals(g3, r.generation, "a second clear finds nothing left to remove")
    }

    @Test
    fun wideCharWritesHeadAndSpacer() {
        val r = row()
        r.put(2, ch('W'), wide = true)
        assertTrue(r.cell(2).hasFlags(CellFlags.WIDE))
        assertEquals(Cell.spacer(StyleId.DEFAULT), r.cell(3))
        assertEquals("..W_......", text(r))
    }

    @Test
    fun putAsciiWritesARunLikeOnePutPerByte() {
        val bytes = "xhello!".encodeToByteArray()
        val run = row()
        run.put(2, ch('W'), wide = true)
        run.put(7, ch('V'), wide = true)
        val g0 = run.generation
        run.putAscii(3, bytes, 1, 5, RED)
        assertNotEquals(g0, run.generation)
        val single = row()
        single.put(2, ch('W'), wide = true)
        single.put(7, ch('V'), wide = true)
        for (i in 0 until 5) single.put(3 + i, Cell.char(bytes[1 + i].toInt(), RED))
        assertEquals("...hello..", text(run), "both cut wide characters lost their other half")
        for (col in 0 until COLS) assertEquals(single.cell(col), run.cell(col), "column $col")
        assertEquals(RED, run.cell(5).style)
    }

    @Test
    fun putAsciiRefusesARunPastTheRowOrTheBytes() {
        val r = row()
        val bytes = "abc".encodeToByteArray()
        assertFailsWith<IndexOutOfBoundsException> { r.putAscii(COLS - 2, bytes, 0, 3, StyleId.DEFAULT) }
        assertFailsWith<IndexOutOfBoundsException> { r.putAscii(0, bytes, 1, 3, StyleId.DEFAULT) }
        assertFailsWith<IndexOutOfBoundsException> { r.putAscii(COLS, bytes, 0, 1, StyleId.DEFAULT) }
        val g0 = r.generation
        r.putAscii(0, bytes, 0, 0, StyleId.DEFAULT)
        assertEquals(g0, r.generation, "an empty run is not a change")
        // A byte outside ASCII keeps only its low seven bits: the style and flags stay intact.
        r.putAscii(0, byteArrayOf(0xE1.toByte()), 0, 1, RED)
        assertEquals(Cell.char('a'.code, RED), r.cell(0))
    }

    @Test
    fun overwritingHalfAWideCharBlanksTheOtherHalf() {
        val r = row()
        r.put(2, ch('W'), wide = true)
        r.put(3, ch('x'))
        assertEquals("...x......", text(r), "spacer overwritten: head blanked")
        r.put(5, ch('V'), wide = true)
        r.put(5, ch('y'))
        assertEquals("...x.y....", text(r), "head overwritten: spacer blanked")
        r.put(7, ch('A'), wide = true)
        r.put(6, ch('B'), wide = true)
        assertEquals("...x.yB_..", text(r), "wide over a head blanks its spacer")
    }

    @Test
    fun erasingHalfAWideCharBlanksTheOtherHalf() {
        val r = row()
        r.put(2, ch('W'), wide = true)
        r.fillRange(3, 4, Cell.EMPTY)
        assertEquals("..........", text(r))
    }

    @Test
    fun insertCellsShiftsRightInsideTheRangeOnly() {
        val r = typed("abcdefghij")
        r.insertCells(2, 8, 2, Cell.EMPTY)
        assertEquals("ab..cdefij", text(r))
        r.insertCells(0, COLS, COLS + 5, Cell.EMPTY)
        assertEquals("..........", text(r), "a count past the range clears it")
    }

    @Test
    fun deleteCellsShiftsLeftAndFillsTheRangeEnd() {
        val r = typed("abcdefghij")
        r.deleteCells(2, 8, 2, Cell.blank(RED))
        assertEquals("abefgh..ij", text(r))
        assertEquals(Cell.blank(RED), r.cell(6))
    }

    @Test
    fun shiftingNeverLeavesHalfAWideChar() {
        var r = row()
        r.put(6, ch('W'), wide = true)
        r.insertCells(0, 8, 1, Cell.EMPTY)
        assertEquals("..........", text(r), "spacer pushed past the range end")
        r = row()
        r.put(2, ch('W'), wide = true)
        r.deleteCells(3, COLS, 1, Cell.EMPTY)
        assertEquals("..........", text(r), "spacer deleted under the head")
        r = row()
        r.put(4, ch('W'), wide = true)
        r.insertCells(5, COLS, 1, Cell.EMPTY)
        assertEquals("..........", text(r), "insert between head and spacer")
        r = row()
        r.put(4, ch('W'), wide = true)
        r.insertCells(0, COLS, 2, Cell.EMPTY)
        assertEquals("......W_..", text(r), "a whole wide char moves intact")
    }

    @Test
    fun writeCellsCopiesASpanAndClipsAtTheRowEnd() {
        val r = typed("abcdefghij")
        val src = typed("xyz")
        r.writeCells(2, src, 0, 3)
        assertEquals("abxyzfghij", text(r))
        r.writeCells(8, src, 0, 3)
        assertEquals("abxyzfghxy", text(r), "the part past the row is dropped")
        r.writeCells(COLS, src, 0, 3)
        assertEquals("abxyzfghxy", text(r), "a copy starting past the row is a no-op")
    }

    @Test
    fun writeCellsNeverLeavesHalfAWideChar() {
        val wide = row()
        wide.put(0, ch('W'), wide = true)
        var r = typed("abcdefghij")
        r.writeCells(3, wide, 0, 1)
        assertEquals("abc.efghij", text(r), "a head without its spacer is blanked")
        r.writeCells(5, wide, 1, 1)
        assertEquals("abc.e.ghij", text(r), "a spacer without its head is blanked")
        r = row()
        r.put(4, ch('V'), wide = true)
        r.writeCells(5, typed("q"), 0, 1)
        assertEquals(".....q....", text(r), "overwriting a spacer blanks its head")
    }

    @Test
    fun compactTrimsTheTailAndCollapsesUniformRows() {
        val r = row()
        r.put(0, ch('a'))
        r.put(1, ch('b'))
        assertEquals(COLS, r.cellCapacity, "the first write reserves the row")
        val g = r.generation
        r.compact()
        assertEquals(2, r.cellCapacity)
        assertEquals("ab........", text(r))
        assertEquals(g, r.generation, "compaction is not a visible change")

        val full = row()
        for (col in 0 until COLS) full.put(col, ch('z'))
        full.compact()
        assertTrue(full.isUniform)
        assertEquals(0, full.cellCapacity)
        assertEquals("zzzzzzzzzz", text(full))
    }

    @Test
    fun linkSpansSplitMergeAndStayDisjoint() {
        val r = row()
        r.setLink(0, 6, LinkId(1))
        r.setLink(2, 4, LinkId(2))
        assertEquals(LinkId(1), r.linkAt(1))
        assertEquals(LinkId(2), r.linkAt(3))
        assertEquals(LinkId(1), r.linkAt(5))
        assertEquals(3, r.links.size)
        r.setLink(2, 4, LinkId(1))
        assertEquals(listOf(LinkSpan(0, 6, LinkId(1))), r.links)
        r.fillRange(0, 3, Cell.EMPTY)
        assertEquals(listOf(LinkSpan(3, 6, LinkId(1))), r.links)
        r.clearLinks(0, COLS)
        assertTrue(r.links.isEmpty())
        assertNull(r.linkAt(4))
    }

    @Test
    fun linkSpansNeverOutnumberColumns() {
        val r = row()
        val rng = Random(0x5eed)
        repeat(1000) {
            val a = rng.nextInt(COLS + 2)
            val b = rng.nextInt(COLS + 2)
            r.setLink(minOf(a, b), maxOf(a, b), LinkId(rng.nextInt(3)))
            val spans = r.links
            assertTrue(spans.size <= COLS)
            assertTrue(spans.zipWithNext().all { (x, y) -> x.end <= y.start })
            assertTrue(spans.all { it.start < it.end && it.end <= COLS })
        }
    }

    @Test
    fun markReachesFillAndClusterCells() {
        val r = Row(RowId(1), COLS, Cell.blank(RED))
        r.put(0, Cell.cluster(ClusterId(4), StyleId(2)))
        val styles = Marks(3)
        val clusters = Marks(5)
        r.mark(styles, clusters)
        assertTrue(styles.isMarked(1) && styles.isMarked(2))
        assertTrue(clusters.isMarked(4))
    }

    @Test
    fun recycleStartsANewLineAndKeepsTheArray() {
        val r = typed("abc")
        r.wrapped = true
        r.setLink(0, 2, LinkId(1))
        r.recycle(RowId(7), Cell.blank(RED))
        assertEquals(RowId(7), r.id)
        assertEquals(0, r.generation)
        assertFalse(r.wrapped)
        assertTrue(r.isUniform)
        assertTrue(r.links.isEmpty())
        assertEquals(Cell.blank(RED), r.cell(0))
        assertEquals(COLS, r.cellCapacity)
        r.release()
        assertEquals(RowId.NONE, r.id)
        assertEquals(0, r.cellCapacity)
    }
}
