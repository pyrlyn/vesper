package vesper.harness

/**
 * The fuzz target of the stub: arbitrary bytes must leave the grid size alone. The seeded corpus
 * test runs it under `./gradlew check`; a coverage-guided runner (T25) calls the same function.
 * The size stays fixed because the property under test is that [Stub.feed] does not resize.
 */
public fun stubFeedKeepsSize(data: ByteArray) {
    val stub = Stub(80, 24)
    stub.feed(data)
    check(stub.cols == 80 && stub.rows == 24) { "feed resized the stub to ${stub.cols}x${stub.rows}" }
    check(stub.lines().all { it.length == 80 }) { "feed changed a row width" }
}
