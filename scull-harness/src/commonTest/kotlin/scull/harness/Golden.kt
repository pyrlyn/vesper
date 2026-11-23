package scull.harness

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `cols rows`, then one line per row: the frame the stub must show. */
internal fun render(stub: Stub): String =
    buildString {
        append("${stub.cols} ${stub.rows}\n")
        stub.lines().forEach { append(it).append('\n') }
    }

/** Feeds [bytes] to a stub of the fixture's size and compares the whole frame with the fixture. */
internal fun assertGolden(
    bytes: ByteArray,
    fixture: String,
) {
    val snapshot = readFixture("harness/$fixture").replace("\r\n", "\n")
    val size =
        snapshot
            .lineSequence()
            .first()
            .split(' ')
            .map { it.toIntOrNull() ?: 0 }
    assertTrue(size.size == 2 && size.all { it >= 1 }, "fixture header of $fixture")
    val stub = Stub(size[0], size[1])
    stub.feed(bytes)
    assertEquals(snapshot, render(stub), fixture)
}
