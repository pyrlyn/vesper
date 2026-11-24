package vesper.bench

import vesper.harness.BASELINE_BYTES
import vesper.harness.BASELINE_COLUMNS
import vesper.harness.Stub
import vesper.harness.baselineInput
import vesper.harness.fixed
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Columns of the stub, and the period of the line feed in the fixed input. */
internal const val COLUMNS = BASELINE_COLUMNS

/** Rows of the stub. Bytes past the last row are dropped by the harness. */
internal const val ROWS = 24

/** Length of the payload every terminal receives. */
internal const val INPUT_BYTES = BASELINE_BYTES

/** Timed feeds of the stub; an odd count gives the median a middle run. */
internal const val STUB_RUNS = 9

private const val BYTES_PER_MIB = 1024.0 * 1024.0

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
    val input = baselineInput()
    val rows = listOf(Row.measured("vesper stub", input.size, timeStub(input))) + referenceRows(input)
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
                "The Vesper row is `Stub(80, 24).feed` from `vesper-harness`, the median of $STUB_RUNS feeds " +
                "after a warm-up. A missing binary is `not installed`. A terminal with no headless stdin feed " +
                "is `unavailable` and is not compared. Numbers are this run only.\n\n",
        )
        append("Reproduce with `./gradlew :vesper-bench:runReleaseExecutableMacosArm64` (or `LinuxX64`).\n\n")
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

/** The UTC calendar date of the run. */
private fun utcDate(): String =
    Clock.System
        .now()
        .toString()
        .substringBefore('T')
