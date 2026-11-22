package scull.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BREAK = "÷"
private const val NO_BREAK = "×"
private const val HEX = 16

/** Clusters of [text], fed one code point at a time the way the grid feeds them. */
internal fun clusters(text: String): List<String> {
    val state = GraphemeState()
    val out = mutableListOf<StringBuilder>()
    text.forEachCodePoint { cp ->
        if (state.next(cp) || out.isEmpty()) out += StringBuilder()
        out.last().appendCodePoint(cp)
    }
    return out.map(StringBuilder::toString)
}

internal fun StringBuilder.appendCodePoint(cp: Int) {
    if (cp < 0x10000) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append((0xD800 + (v shr 10)).toChar())
        append((0xDC00 + (v and 0x3FF)).toChar())
    }
}

class GraphemeBreakTest {
    /** Runs every line of the Unicode conformance file, which the generator copies into GRAPHEME_BREAK_CASES. */
    @Test
    fun everyGraphemeBreakTestLinePasses() {
        val lines = GRAPHEME_BREAK_CASES.lines().filter { it.isNotBlank() }
        val failures =
            lines.filter { line ->
                val state = GraphemeState(UnicodeVersion.LATEST)
                var boundary = false
                var ok = true
                for (token in line.split(' ')) {
                    when (token) {
                        BREAK -> boundary = true
                        NO_BREAK -> boundary = false
                        else -> if (state.next(token.toInt(HEX)) != boundary) ok = false
                    }
                }
                !ok
            }
        assertTrue(lines.size > 800, "only ${lines.size} cases")
        assertTrue(failures.isEmpty(), "${failures.size} of ${lines.size} lines fail:\n${failures.joinToString("\n")}")
    }

    @Test
    fun crlfStaysTogetherAndControlsSplit() {
        assertEquals(listOf("a", "\r\n", "b"), clusters("a\r\nb"))
    }

    @Test
    fun devanagariConjunctJoinsThroughVirama() {
        // KA VIRAMA SSA: GB9c keeps the conjunct in one cluster.
        assertEquals(1, clusters("क्ष").size)
    }

    @Test
    fun thirdRegionalIndicatorStartsANewFlag() {
        val us = "🇺🇸"
        val a = "🇦"
        assertEquals(listOf(us, a), clusters(us + a))
    }
}
