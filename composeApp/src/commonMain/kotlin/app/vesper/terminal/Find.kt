package app.vesper.terminal

import app.vesper.terminal.vt.TermLine
import app.vesper.terminal.vt.WIDE_TAIL
import app.vesper.terminal.vt.codePointToString

/** One find-in-output match: columns [start, end) of output [line] in block [blockId]. */
data class FindMatch(val blockId: Long, val line: Int, val start: Int, val end: Int)

/** The pattern for [query], or null when it is empty or not a valid regular expression. */
fun findPattern(query: String, caseSensitive: Boolean, regex: Boolean): Regex? {
    if (query.isEmpty()) return null
    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return runCatching { Regex(if (regex) query else Regex.escape(query), options) }.getOrNull()
}

/**
 * Matches of [pattern] in the output of [blocks], top to bottom, at most [limit].
 * Reads terminal lines, so the caller holds the session lock. A match does not span soft-wrapped rows.
 */
fun findInBlocks(blocks: List<Block>, pattern: Regex, limit: Int = 10_000): List<FindMatch> {
    val out = ArrayList<FindMatch>()
    for (block in blocks) {
        val t = block.terminal
        for (i in 0 until t.mainLineCount()) {
            for (range in findInLine(t.mainLine(i), pattern)) {
                out += FindMatch(block.id, i, range.first, range.last + 1)
                if (out.size >= limit) return out
            }
        }
    }
    return out
}

/** Column ranges of [pattern] in [line]; wide characters count as two columns. */
fun findInLine(line: TermLine, pattern: Regex): List<IntRange> {
    val n = line.contentLength()
    if (n == 0) return emptyList()
    val text = StringBuilder(n)
    val startCol = IntArray(n * 2 + 1) // column where each char of [text] starts
    val cps = line.cps
    for (col in 0 until n) {
        val cp = cps[col]
        if (cp == WIDE_TAIL) continue
        val s = if (cp == 0) " " else codePointToString(cp)
        for (ch in s) {
            startCol[text.length] = col
            text.append(ch)
        }
    }
    startCol[text.length] = n
    val result = ArrayList<IntRange>()
    for (m in pattern.findAll(text)) {
        if (m.range.isEmpty()) continue // e.g. "a*" matching nothing
        result += startCol[m.range.first] until startCol[m.range.last + 1]
    }
    return result
}
