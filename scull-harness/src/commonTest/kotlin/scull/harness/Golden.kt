package scull.harness

import kotlin.test.assertEquals

/** Feeds [bytes] to a stub of the fixture's size and compares every row with the fixture. */
internal fun assertGolden(
    bytes: ByteArray,
    fixture: String,
) {
    val golden = readGolden(fixture)
    val stub = Stub(golden.cols, golden.rows)
    stub.feed(bytes)
    assertEquals(golden.lines, stub.lines(), fixture)
}
