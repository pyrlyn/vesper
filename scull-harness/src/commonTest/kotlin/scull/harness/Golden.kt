package scull.harness

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `cols rows`, then one line per row: the frame the stub must show. */
internal fun render(stub: Stub): String =
    buildString {
        append("${stub.cols} ${stub.rows}\n")
        stub.lines().forEach { append(it).append('\n') }
    }

/** Reads `tests/<relative>` from the repository; Gradle passes its path in `SCULL_TESTS_DIR`. */
internal fun readFixture(relative: String): String {
    val root = checkNotNull(environment("SCULL_TESTS_DIR")) { "SCULL_TESTS_DIR is not set; run the tests through Gradle" }
    return FileSystem.SYSTEM.read(root.toPath() / relative) { readUtf8() }
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

internal expect fun environment(name: String): String?
