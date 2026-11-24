package vesper.bench

import vesper.harness.fixed
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// The inputs of the Rust bench that measured `vte` (vesper-parser/benches at 69d9be4): plain ASCII
// lines, UTF-8 lines and SGR-heavy output. The parser and the terminal are timed on the same three,
// so the two tables show what the state behind the parser adds.

/** Size of every generated input: many PTY reads, still a quick run. */
private const val THROUGHPUT_BYTES = 1 shl 20

/** Timed feeds per input; an odd count gives the median a middle run. */
private const val THROUGHPUT_RUNS = 21
private const val LINE_CHARS = 80
private const val PALETTE = 256

// Large primes keep the generated truecolour values from repeating early.
private const val RED_STEP = 37
private const val GREEN_STEP = 91
private const val BLUE_STEP = 53

private const val MIB = 1024.0 * 1024.0

private val asciiWords = listOf("lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do")
private val utf8Words = listOf("привет", "мир", "日本語", "テキスト", "🎉", "émoji", "Straße", "αβγ", "한국어", "✓")

private fun String.codePoints(): Int = count { !it.isLowSurrogate() }

private fun lines(words: List<String>): ByteArray {
    val out = StringBuilder()
    var bytes = 0
    var lineLength = 0
    var i = 0
    while (bytes < THROUGHPUT_BYTES) {
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
    while (out.length < THROUGHPUT_BYTES) {
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

/** Median wall-clock time of [THROUGHPUT_RUNS] calls of [feed] on [input], after a warm-up call. */
private fun medianFeed(
    input: ByteArray,
    feed: (ByteArray) -> Int,
): Duration {
    var sink = feed(input)
    val runs =
        List(THROUGHPUT_RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            sink += feed(input)
            mark.elapsedNow()
        }
    // The sink keeps the feeds observable, so the compiler cannot drop them.
    check(sink > 0)
    return runs.sorted()[THROUGHPUT_RUNS / 2]
}

/**
 * One row per input with its size, median time and throughput. [consumer] is called once per input
 * and returns what is timed: a function that takes the whole input and returns a number that depends
 * on it.
 */
internal fun throughputMarkdown(consumer: () -> (ByteArray) -> Int): String {
    val inputs = listOf("ASCII lines" to lines(asciiWords), "UTF-8 lines" to lines(utf8Words), "SGR-heavy" to sgrHeavy())
    return buildString {
        appendLine("| Input | Bytes | Median (µs) | MiB/s |")
        appendLine("| --- | --- | --- | --- |")
        for ((name, input) in inputs) {
            val elapsed = medianFeed(input, consumer())
            val mib = input.size / MIB / elapsed.toDouble(DurationUnit.SECONDS)
            appendLine("| $name | ${input.size} | ${fixed(elapsed.toDouble(DurationUnit.MICROSECONDS), 0)} | ${fixed(mib, 1)} |")
        }
    }
}
