package scull.bench

import scull.harness.Stub
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Columns of the stub, and the period of the line feed in the fixed input. */
internal const val COLUMNS = 80

/** Rows of the stub. Bytes past the last row are dropped by the harness. */
internal const val ROWS = 24

/** Length of the payload every terminal receives. */
internal const val INPUT_BYTES = 1_000_000

/** Timed feeds of the stub; an odd count gives the median a middle run. */
internal const val STUB_RUNS = 9

private const val ALPHABET = 26
private const val BYTES_PER_MIB = 1024.0 * 1024.0

/**
 * The fixed payload: [INPUT_BYTES] of a repeating `A`..`Z` pattern with a line feed closing every
 * [COLUMNS] bytes. Built here so the repository does not store it; the test pins its FNV-1a hash.
 */
internal fun fixedInput(): ByteArray {
    var letter = 0
    return ByteArray(INPUT_BYTES) { i ->
        if ((i + 1) % COLUMNS == 0) '\n'.code.toByte() else ('A'.code + letter++ % ALPHABET).toByte()
    }
}

/** `Stub(80, 24).feed`. The count is data-dependent so the optimiser cannot drop the writes. */
internal fun feedStub(input: ByteArray): Int {
    val stub = Stub(COLUMNS, ROWS)
    stub.feed(input)
    return stub.lines().sumOf { line -> line.count { it != ' ' } }
}

/**
 * Median wall-clock time of [STUB_RUNS] [feedStub] calls after one untimed warm-up. One feed takes
 * about a millisecond, so a single pass mostly measures the scheduler and a cold cache.
 */
internal fun timeStub(input: ByteArray): Duration {
    var sink = feedStub(input)
    val runs =
        List(STUB_RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            sink += feedStub(input)
            mark.elapsedNow()
        }
    check(sink > 0) { "the stub inked nothing" }
    return runs.sorted()[STUB_RUNS / 2]
}

internal class Row(
    val terminal: String,
    val status: String,
    val bytes: Int,
    val elapsed: Duration?,
) {
    companion object {
        fun measured(
            terminal: String,
            bytes: Int,
            elapsed: Duration,
        ) = Row(terminal, "ok", bytes, elapsed)

        fun notInstalled(
            terminal: String,
            bytes: Int,
        ) = Row(terminal, "not installed", bytes, null)

        fun unavailable(
            terminal: String,
            bytes: Int,
            reason: String,
        ) = Row(terminal, "unavailable: $reason", bytes, null)
    }
}

/** Markdown for `docs/benchmarks/baseline.md`. */
internal fun baselineMarkdown(): String {
    val input = fixedInput()
    val rows = listOf(Row.measured("scull stub", input.size, timeStub(input))) + referenceRows(input)
    return render(rows, utcDate(), machine())
}

internal fun render(
    rows: List<Row>,
    date: String,
    machine: String,
): String =
    buildString {
        append("# Baseline\n\n")
        append(
            "One wall-clock feed of the same $INPUT_BYTES bytes on $date, $machine. " +
                "The Scull row is `Stub(80, 24).feed` from `scull-harness`, the median of $STUB_RUNS feeds " +
                "after a warm-up. A missing binary is `not installed`. A terminal with no headless stdin feed " +
                "is `unavailable` and is not compared. Numbers are this run only.\n\n",
        )
        append("Reproduce with `./gradlew :scull-bench:runReleaseExecutableMacosArm64` (or `LinuxX64`).\n\n")
        append("| terminal | status | bytes | seconds | MiB/s |\n")
        append("| --- | --- | --- | --- | --- |\n")
        rows.forEach { row ->
            append("| ${row.terminal} | ${row.status} | ${row.bytes} | ${seconds(row.elapsed)} | ${mibPerSecond(row)} |\n")
        }
    }

private fun seconds(elapsed: Duration?): String = elapsed?.toDouble(DurationUnit.SECONDS)?.let { fixed(it, 6) } ?: "-"

private fun mibPerSecond(row: Row): String {
    val secs = row.elapsed?.toDouble(DurationUnit.SECONDS) ?: return "-"
    if (secs == 0.0) return "-"
    return fixed(row.bytes / secs / BYTES_PER_MIB, 2)
}

/** [value] with exactly [digits] decimals; Kotlin/Native has no `String.format`. */
internal fun fixed(
    value: Double,
    digits: Int,
): String {
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val scaled = kotlin.math.round(value * scale).toLong()
    val whole = scaled / scale
    val fraction = (scaled % scale).toString().padStart(digits, '0')
    return if (digits == 0) "$whole" else "$whole.$fraction"
}

/** The UTC calendar date of the run. */
private fun utcDate(): String =
    Clock.System
        .now()
        .toString()
        .substringBefore('T')
