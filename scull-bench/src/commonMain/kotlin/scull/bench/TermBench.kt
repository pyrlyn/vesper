package scull.bench

import scull.term.Terminal

// Terminal throughput: the parser plus the state behind it (cursor, wrapping, SGR, scrolling into a
// full history). One terminal takes every feed of an input, so the timed feeds run in the steady
// state of a long session: the scrollback is full and each new line recycles the oldest row.

private const val TERM_COLS = 80
private const val TERM_ROWS = 24
private const val TERM_SCROLLBACK = 10_000

private fun terminalFeed(): (ByteArray) -> Int {
    val terminal = Terminal(TERM_COLS, TERM_ROWS, TERM_SCROLLBACK)
    return { input ->
        terminal.feed(input)
        // Depends on what was fed, so the feed cannot be dropped.
        terminal.grid.historyLength + terminal.cursor.col + 1
    }
}

/** The terminal table: bytes through `Terminal.feed` at 80x24 with 10 000 rows of scrollback. */
internal fun termMarkdown(): String = throughputMarkdown(::terminalFeed)
