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
    var sink = 0
    val median = medianTime(STUB_RUNS) { sink += feedStub(input) }
    check(sink > 0) { "the stub inked nothing" }
    return median
}

/** The median of [runs] timed calls of [block] after one untimed one. */
internal fun medianTime(
    runs: Int,
    block: () -> Unit,
): Duration {
    block()
    val times =
        List(runs) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow()
        }
    return times.sorted()[runs / 2]
}

/**
 * What a terminal runs as its program: the median of [WINDOW_RUNS] writes of [input] to the
 * terminal after a warm-up one that also waits out the window's first frame. Null when a write fails.
 */
internal fun timeWindowWrites(input: ByteArray): Duration? {
    var ok = true
    val median = medianTime(WINDOW_RUNS) { ok = ok && writeToTerminal(input) }
    return median.takeIf { ok }
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
    val rows = listOf(Row.measured("vesper stub", input.size, timeStub(input))) + referenceRows(input.size)
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
            "The same $INPUT_BYTES bytes on $date, $machine. The Vesper row is `Stub(80, 24).feed` from " +
                "`vesper-harness`, the median of $STUB_RUNS feeds after a warm-up. Each terminal opens one " +
                "window running `vesper-bench $WRITE_COMMAND`, which writes the bytes to it once untimed and " +
                "$WINDOW_RUNS times timed, each write ended once the terminal has read all of it; its row is " +
                "the median. A missing binary is `not installed`; a terminal that cannot be run this way or " +
                "fails is `unavailable` and is not compared. Numbers are this run only.\n\n",
        )
        append(
            "Reproduce with `./gradlew :vesper-bench:runReleaseExecutableMacosArm64` (or `LinuxX64`, or `MingwX64` " +
                "on Windows); the Linux terminals run in a container through `vesper-bench/terminals.sh`.\n\n",
        )
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
