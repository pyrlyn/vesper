package scull.term

import scull.grid.Attrs
import scull.grid.Color
import scull.grid.Style
import scull.grid.Underline
import scull.harness.readFixture
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// Conformance runner: replays the cases in tests/fixtures against a fresh terminal and compares
// screen text, cursor, styles, history, replies and events. The cases, their esctest2 and vttest
// sources and the fixture format are described in tests/README.md.

/** The files T7 answers for; `shell.txt` and `selection.txt` wait for their tasks. */
private val FILES =
    listOf(
        "charsets",
        "cursor",
        "erase",
        "insert_delete",
        "margins",
        "modes",
        "reports",
        "reset",
        "screens",
        "scroll",
        "sgr",
        "tabs",
        "unicode",
        "wrap",
    )

private const val DEFAULT_COLS = 10
private const val DEFAULT_ROWS = 5
private const val HEX = 16

/** One case: a byte stream and what it must leave behind. */
private class Case(
    val file: String,
    val name: String,
) {
    var cols = DEFAULT_COLS
    var rows = DEFAULT_ROWS
    var scrollback = 0
    var input = ByteArray(0)
    var cursor: String? = null
    var screen: List<String>? = null
    val styles = ArrayList<Triple<Int, Int, String>>()
    var history: Int? = null
    var replies: ByteArray? = null
    var events: List<String>? = null

    val checksSomething: Boolean
        get() = cursor != null || screen != null || styles.isNotEmpty() || history != null || replies != null || events != null
}

private fun unescape(text: String): ByteArray {
    var out = ByteArray(0)
    val plain = StringBuilder()

    fun flush() {
        out += plain.toString().encodeToByteArray()
        plain.clear()
    }
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        if (c != '\\') {
            plain.append(c)
            continue
        }
        flush()
        when (val escape = text.getOrNull(i++)) {
            'e' -> {
                out += 0x1B
            }

            'r' -> {
                out += '\r'.code.toByte()
            }

            'n' -> {
                out += '\n'.code.toByte()
            }

            't' -> {
                out += '\t'.code.toByte()
            }

            'b' -> {
                out += 0x08
            }

            '\\' -> {
                out += '\\'.code.toByte()
            }

            's' -> {
                out += ' '.code.toByte()
            }

            'x' -> {
                out += text.substring(i, i + 2).toInt(HEX).toByte()
                i += 2
            }

            else -> {
                error("unknown escape \\$escape in $text")
            }
        }
    }
    flush()
    return out
}

private fun parse(
    file: String,
    text: String,
): List<Case> {
    val cases = ArrayList<Case>()
    val lines = text.lines()
    var at = 0

    /** The `|...|` lines that follow. */
    fun pipes(): List<String> {
        val out = ArrayList<String>()
        while (at < lines.size && lines[at].trim().startsWith('|')) {
            val row = lines[at++].trim()
            check(row.length >= 2 && row.endsWith('|')) { "$file: row does not end in |: $row" }
            out += row.substring(1, row.length - 1)
        }
        return out
    }
    while (at < lines.size) {
        val line = lines[at++].trimEnd()
        if (line.isEmpty() || line.startsWith('#')) continue
        if (line.startsWith("=== ")) {
            cases += Case(file, line.removePrefix("=== "))
            continue
        }
        val case = checkNotNull(cases.lastOrNull()) { "$file: a field before the first ===" }
        val colon = line.indexOf(':')
        check(colon > 0) { "$file: not `key: value`: $line" }
        val value = line.substring(colon + 1).trim()
        when (val key = line.substring(0, colon)) {
            // Where the case comes from; for the reader, not the runner.
            "source" -> {}

            "size" -> {
                case.cols = value.substringBefore('x').toInt()
                case.rows = value.substringAfter('x').toInt()
            }

            "scrollback" -> {
                case.scrollback = value.toInt()
            }

            "input" -> {
                case.input += unescape(value)
            }

            "cursor" -> {
                case.cursor = value
            }

            "history" -> {
                case.history = value.toInt()
            }

            "replies" -> {
                case.replies = unescape(value)
            }

            "style" -> {
                val position = value.substringBefore(' ')
                case.styles +=
                    Triple(
                        position.substringBefore(',').toInt(),
                        position.substringAfter(',').toInt(),
                        value.substringAfter(' ', "").trim(),
                    )
            }

            "screen" -> {
                case.screen = pipes()
            }

            "events" -> {
                case.events = pipes()
            }

            else -> {
                error("$file: unknown field $key")
            }
        }
    }
    return cases
}

private fun showEvent(event: Event): String =
    when (event) {
        Event.Bell -> "bell"
        is Event.Title -> "title ${event.target.code} ${event.text}"
    }

private fun color(c: Color): String =
    when {
        c.isDefault -> "default"
        c.isIndexed -> c.index.toString()
        else -> "#" + listOf(c.red, c.green, c.blue).joinToString("") { it.toString(HEX).padStart(2, '0') }
    }

private val ATTR_NAMES =
    listOf(
        Attrs.BOLD to "bold",
        Attrs.DIM to "dim",
        Attrs.ITALIC to "italic",
        Attrs.BLINK to "blink",
        Attrs.INVERSE to "inverse",
        Attrs.HIDDEN to "hidden",
        Attrs.STRIKE to "strike",
        Attrs.OVERLINE to "overline",
    )

private fun styleText(style: Style): String {
    val parts = ATTR_NAMES.filter { style.attrs and it.first != 0 }.mapTo(ArrayList()) { it.second }
    if (!style.fg.isDefault) parts += "fg=${color(style.fg)}"
    if (!style.bg.isDefault) parts += "bg=${color(style.bg)}"
    if (style.underline != Underline.None) parts += "ul=${style.underline.name.lowercase()}"
    if (!style.underlineColor.isDefault) parts += "ulc=${color(style.underlineColor)}"
    return if (parts.isEmpty()) "default" else parts.joinToString(" ")
}

/** Runs one case; returns a description of every mismatch. */
private fun check(case: Case): String =
    buildString {
        val term = Terminal(case.cols, case.rows, case.scrollback)
        term.feed(case.input)
        case.replies?.let { want ->
            val got = term.takeReplies()
            if (!got.contentEquals(want)) append("  replies: want ${want.decodeToString()}, got ${got.decodeToString()}\n")
        }
        case.screen?.let { want ->
            val got = term.screenText()
            if (got != want) append("  screen:\n    want $want\n    got  $got\n")
        }
        case.cursor?.let { want ->
            val c = term.cursor
            val got = "${c.row + 1},${c.col + 1}" + if (c.pendingWrap) " wrap" else ""
            if (got != want) append("  cursor: want $want, got $got\n")
        }
        for ((r, c, want) in case.styles) {
            val got = styleText(checkNotNull(term.grid.styles[term.cell(r - 1, c - 1).style]))
            if (got != want) append("  style $r,$c: want $want, got $got\n")
        }
        case.history?.let { want ->
            val got = term.grid.historyLength
            if (got != want) append("  history: want $want, got $got\n")
        }
        case.events?.let { want ->
            val got = generateSequence(term::pollEvent).map(::showEvent).toList()
            if (got != want) append("  events:\n    want $want\n    got  $got\n")
        }
    }

class ConformanceTest {
    @Test
    fun everyFixtureCaseMatchesItsExpectedState() {
        val failures = StringBuilder()
        var total = 0
        for (file in FILES) {
            val cases = parse(file, readFixture("fixtures/$file.txt"))
            assertTrue(cases.isNotEmpty(), "$file has no cases")
            for (case in cases) {
                total++
                assertTrue(case.checksSomething, "$file: ${case.name} checks nothing")
                val errors = check(case)
                if (errors.isNotEmpty()) failures.append("$file: ${case.name}\n$errors")
            }
        }
        if (failures.isNotEmpty()) fail("failing cases of $total:\n$failures")
    }

    @Test
    fun escapesInAFixtureBecomeBytes() {
        val bytes = unescape("""a\e\r\n\t\b\\\s\x7f中""")
        val expected =
            byteArrayOf('a'.code.toByte(), 0x1B, 0x0D, 0x0A, 0x09, 0x08, '\\'.code.toByte(), ' '.code.toByte(), 0x7F) +
                "中".encodeToByteArray()
        assertTrue(bytes.contentEquals(expected), bytes.joinToString())
    }
}
