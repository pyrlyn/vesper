package vesper.bench

import vesper.parser.Csi
import vesper.parser.Handler
import vesper.parser.Parser

// Parser throughput with a handler that only counts, so the number is the parser's own cost.

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

/** The parser table: every feed goes through a new parser. */
internal fun parserMarkdown(): String = throughputMarkdown { { input -> Count().also { Parser().feed(input, it) }.total } }
