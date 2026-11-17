package app.vesper

import app.vesper.terminal.findInLine
import app.vesper.terminal.findPattern
import app.vesper.terminal.vt.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindTest {
    private fun line(text: String) = Terminal(30, 3).apply { feed(text) }.mainLine(0)

    @Test
    fun plainQueryIsCaseInsensitiveByDefault() {
        val p = findPattern("Invoice", caseSensitive = false, regex = false)!!
        assertEquals(listOf(4..10, 15..21), findInLine(line("src/invoice.ts INVOICE"), p))
        val strict = findPattern("Invoice", caseSensitive = true, regex = false)!!
        assertEquals(emptyList(), findInLine(line("src/invoice.ts INVOICE"), strict))
    }

    @Test
    fun plainQueryEscapesRegexCharacters() {
        val p = findPattern("a.b", caseSensitive = false, regex = false)!!
        assertEquals(listOf(4..6), findInLine(line("axb a.b"), p))
    }

    @Test
    fun regexAndWideCharactersMapToColumns() {
        val p = findPattern("[0-9]+ms", caseSensitive = false, regex = true)!!
        // "日本" takes four columns, so "12ms" starts at column 5.
        assertEquals(listOf(5..8), findInLine(line("日本 12ms"), p))
    }

    @Test
    fun emptyOrInvalidPatternsFindNothing() {
        assertNull(findPattern("", caseSensitive = false, regex = false))
        assertNull(findPattern("(", caseSensitive = false, regex = true))
        val p = findPattern("x*", caseSensitive = false, regex = true)!!
        assertEquals(listOf(1..2), findInLine(line("axx"), p))
    }
}

class HistoryInsightsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test
    fun countsTheLastWeekAgainstTheOneBefore() {
        val h = listOf(
            app.vesper.app.HistoryEntry("git status -sb", now - 1 * day, "/", 0, 100),
            app.vesper.app.HistoryEntry("git status", now - 2 * day, "/", 0, 300),
            app.vesper.app.HistoryEntry("pnpm test --filter api", now - 3 * day, "/", 1, 40_000),
            app.vesper.app.HistoryEntry("ls -la", now - 10 * day, "/", 0, 5),
        )
        val i = app.vesper.app.historyInsights(h, now)
        assertEquals(3, i.commands)
        assertEquals(200, i.changePercent)
        assertEquals(1, i.failed)
        assertEquals(40_400, i.totalMs)
        assertEquals("pnpm test" to 40_000L, i.longest)
        assertEquals(listOf("git status" to 2, "pnpm test" to 1), i.top)
    }

    @Test
    fun commandKeyKeepsSubcommandsOnly() {
        assertEquals("git status", app.vesper.app.commandKey("git status -sb"))
        assertEquals("ls", app.vesper.app.commandKey("ls -la"))
        assertEquals("cd", app.vesper.app.commandKey("cd ~/apps"))
    }
}
