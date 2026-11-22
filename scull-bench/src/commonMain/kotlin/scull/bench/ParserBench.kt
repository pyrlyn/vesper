package scull.bench

import scull.parser.Csi
import scull.parser.Handler
import scull.parser.Parser
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// Parser throughput on the inputs of the Rust bench that measured `vte` (scull-parser/benches at
// 69d9be4): plain ASCII lines, UTF-8 lines and SGR-heavy output, with a handler that only counts,
// so the number is the parser's own cost.

/** Size of every generated input: many PTY reads, still a quick run. */
private const val PARSER_INPUT_BYTES = 1 shl 20

/** Timed feeds per input; an odd count gives the median a middle run. */
private const val PARSER_RUNS = 21
private const val LINE_CHARS = 80
private const val PALETTE = 256

// Large primes keep the generated truecolour values from repeating early.
private const val RED_STEP = 37
private const val GREEN_STEP = 91
private const val BLUE_STEP = 53

private val asciiWords = listOf("lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do")
private val utf8Words = listOf("привет", "мир", "日本語", "テキスト", "🎉", "émoji", "Straße", "αβγ", "한국어", "✓")

private fun String.codePoints(): Int = count { !it.isLowSurrogate() }

private fun lines(words: List<String>): ByteArray {
    val out = StringBuilder()
    var bytes = 0
    var lineLength = 0
    var i = 0
    while (bytes < PARSER_INPUT_BYTES) {
        val word = words[i++ % words.size]
        if (lineLength + word.codePoints() >= LINE_CHARS) {
            out.append("\r\n")
            bytes += 2
            lineLength = 0
        }
        out.append(word).append(' ')
        bytes += word.encodeToByteArray().size + 1
        lineLength += word.codePoints() + 1
    }
    return out.toString().encodeToByteArray()
}

/** `ls --color` and compiler output style: most words carry their own colour. */
private fun sgrHeavy(): ByteArray {
    val out = StringBuilder()
    var i = 0
    while (out.length < PARSER_INPUT_BYTES) {
        val word = asciiWords[i % asciiWords.size]
        if (i % 2 == 0) {
            out.append("\u001B[1;38;5;${i % PALETTE}m$word\u001B[0m ")
        } else {
            val r = (i * RED_STEP) % PALETTE
            val g = (i * GREEN_STEP) % PALETTE
            val b = (i * BLUE_STEP) % PALETTE
            out.append("\u001B[38;2;$r;$g;${b}m$word\u001B[m ")
        }
        if (i % asciiWords.size == asciiWords.size - 1) out.append("\r\n")
        i++
    }
    return out.toString().encodeToByteArray()
}

private class Count : Handler {
    var total = 0

    override fun print(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        total += end - start
    }

    override fun csiDispatch(csi: Csi) {
        total++
    }
}

private fun feedParser(input: ByteArray): Int = Count().also { Parser().feed(input, it) }.total

/** Median wall-clock time of [PARSER_RUNS] feeds of [input], each through a new parser, after a warm-up. */
private fun timeParser(input: ByteArray): Duration {
    var sink = feedParser(input)
    val runs =
        List(PARSER_RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            sink += feedParser(input)
            mark.elapsedNow()
        }
    // The sink keeps the feeds observable, so the compiler cannot drop them.
    check(sink > 0)
    return runs.sorted()[PARSER_RUNS / 2]
}

/** The parser table: one row per input with its size, median time and throughput. */
internal fun parserMarkdown(): String {
    val inputs = listOf("ASCII lines" to lines(asciiWords), "UTF-8 lines" to lines(utf8Words), "SGR-heavy" to sgrHeavy())
    return buildString {
        appendLine("| Input | Bytes | Median (µs) | MiB/s |")
        appendLine("| --- | --- | --- | --- |")
        for ((name, input) in inputs) {
            val elapsed = timeParser(input)
            val mib = input.size / MIB / elapsed.toDouble(DurationUnit.SECONDS)
            appendLine("| $name | ${input.size} | ${fixed(elapsed.toDouble(DurationUnit.MICROSECONDS), 0)} | ${fixed(mib, 1)} |")
        }
    }
}

private const val MIB = 1024.0 * 1024.0
