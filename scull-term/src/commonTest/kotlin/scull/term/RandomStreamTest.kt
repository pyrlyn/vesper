package scull.term

import scull.grid.CellFlags
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Seeded so a failure reproduces: the PTY is untrusted, so no stream may throw, move the cursor or
// the margins off the screen, or leave half a wide character behind.
private const val SEED = 0x5C011
private const val CASES = 1500
private const val MAX_COLS = 24
private const val MAX_SCREEN_ROWS = 8
private const val SCROLLBACK = 4

private fun assertSound(
    term: Terminal,
    cols: Int,
    rows: Int,
) {
    val cursor = term.cursor
    assertTrue(cursor.row in 0 until rows && cursor.col in 0 until cols, "cursor $cursor on ${cols}x$rows")
    val m = term.margins
    assertTrue(m.top in 0..m.bottom && m.bottom < rows, "margins $m")
    assertTrue(m.left in 0..m.right && m.right < cols, "margins $m")
    assertTrue(term.takeReplies().size <= MAX_REPLY_BYTES)
    for (r in 0 until rows) {
        val row = checkNotNull(term.grid.screenRow(r))
        for (c in 0 until cols) {
            val cell = row.cell(c)
            if (cell.hasFlags(CellFlags.WIDE)) {
                assertTrue(c + 1 < cols && row.cell(c + 1).hasFlags(CellFlags.SPACER), "wide cell without its spacer at $r,$c")
            }
            if (cell.hasFlags(CellFlags.SPACER)) {
                assertTrue(c > 0 && row.cell(c - 1).hasFlags(CellFlags.WIDE), "spacer without its wide cell at $r,$c")
            }
        }
    }
}

class RandomStreamTest {
    @Test
    fun noByteStreamThrowsLeavesTheScreenOrHalvesAWideCharacter() {
        val random = Random(SEED)
        repeat(CASES) {
            val cols = random.nextInt(1, MAX_COLS)
            val rows = random.nextInt(1, MAX_SCREEN_ROWS)
            val term = Terminal(cols, rows, SCROLLBACK)
            term.feed(random.stream())
            assertSound(term, cols, rows)
        }
    }

    @Test
    fun aStreamGivesTheSameScreenHoweverItIsCutIntoFeeds() {
        val random = Random(SEED)
        repeat(CASES) {
            val cols = random.nextInt(1, MAX_COLS)
            val rows = random.nextInt(1, MAX_SCREEN_ROWS)
            val bytes = random.stream()
            val whole = Terminal(cols, rows, SCROLLBACK)
            whole.feed(bytes)
            val cut = Terminal(cols, rows, SCROLLBACK)
            var at = 0
            while (at < bytes.size) {
                val to = minOf(bytes.size, at + random.nextInt(1, 8))
                cut.feed(bytes, at, to)
                at = to
            }
            assertEquals(whole.screenText(), cut.screenText())
            assertEquals(whole.cursor, cut.cursor)
            assertEquals(whole.grid.historyLength, cut.grid.historyLength)
            assertEquals(whole.takeReplies().decodeToString(), cut.takeReplies().decodeToString())
        }
    }
}
