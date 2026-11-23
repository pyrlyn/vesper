package scull.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Counts payload bytes without keeping them. */
private class Counter : Handler {
    var dcs = 0
    var apc = 0
    var osc = 0
    var end: End? = null

    override fun oscDispatch(osc: Osc) {
        this.osc++
        assertTrue(osc.size <= MAX_OSC_BYTES)
    }

    override fun dcsPut(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        dcs += end - start
    }

    override fun dcsUnhook(end: End) {
        this.end = end
    }

    override fun apcPut(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        apc += end - start
    }

    override fun apcEnd(end: End) {
        this.end = end
    }
}

private const val CHUNK = 1 shl 20
private val filler = ByteArray(CHUNK) { 'a'.code.toByte() }

/** Feeds [head], then [length] bytes of `a` in large chunks, then [tail]. */
private fun feedLong(
    head: String,
    length: Int,
    tail: String,
    parser: Parser = Parser(),
    counter: Counter = Counter(),
): Pair<Counter, Parser> {
    parser.feed(raw(head), counter)
    var left = length
    while (left > 0) {
        val n = minOf(left, CHUNK)
        parser.feed(filler, 0, n, counter)
        left -= n
    }
    parser.feed(raw(tail), counter)
    return counter to parser
}

class CapsTest {
    @Test
    fun oscPastTheCapIsDroppedAndItsBufferBounded() {
        val (counter, parser) = feedLong("\u001B]52;c;", MAX_OSC_BYTES + OVERFLOW, "\u0007")
        assertEquals(0, counter.osc)
        assertTrue(parser.oscSize <= MAX_OSC_BYTES)
        assertTrue(parser.oscCapacity <= MAX_OSC_BYTES)
    }

    @Test
    fun oscExactlyAtTheCapIsDispatched() {
        val (counter, _) = feedLong("\u001B]2;", MAX_OSC_BYTES - "2;".length, "\u0007")
        assertEquals(1, counter.osc)
    }

    @Test
    fun aLargeOscDoesNotPinItsBuffer() {
        val head = "\u001B]1337;File=:"
        val (counter, parser) = feedLong(head, MAX_OSC_BYTES - (head.length - 2), "\u0007")
        assertEquals(1, counter.osc)
        parser.feed(raw("\u001B]2;title\u0007"), counter)
        assertEquals(2, counter.osc)
        assertTrue(parser.oscCapacity <= OSC_KEPT_BYTES)
    }

    @Test
    fun dcsPastTheCapIsCutAndReportedTruncated() {
        val (counter, _) = feedLong("\u001BPq", MAX_DCS_BYTES + OVERFLOW, "\u001B\\")
        assertEquals(MAX_DCS_BYTES, counter.dcs)
        assertEquals(End.Truncated, counter.end)
    }

    @Test
    fun apcPastTheCapIsCutAndReportedTruncated() {
        val (counter, _) = feedLong("\u001B_G", MAX_APC_BYTES + OVERFLOW, "\u001B\\")
        assertEquals(MAX_APC_BYTES, counter.apc)
        assertEquals(End.Truncated, counter.end)
    }

    @Test
    fun apcAtTheCapIsComplete() {
        val (counter, _) = feedLong("\u001B_", MAX_APC_BYTES, "\u001B\\")
        assertEquals(MAX_APC_BYTES, counter.apc)
        assertEquals(End.Complete, counter.end)
    }
}
