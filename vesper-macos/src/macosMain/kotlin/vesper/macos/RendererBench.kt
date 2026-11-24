package vesper.macos

import vesper.harness.BASELINE_BYTES
import vesper.harness.BASELINE_COLUMNS
import vesper.harness.baselineInput
import vesper.harness.fixed
import vesper.term.Frame
import vesper.term.Terminal
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// Throughput and frame cost of the Metal renderer, run by `vesper --bench-renderer` from a release
// build. The throughput cases feed the T3 baseline's input, so they read against its table.

private const val ROWS = 24
private const val SCROLLBACK = 1000
private const val SCALE = 2.0
private const val RUNS = 9
private const val FRAMES = 300
private const val MIB = 1024.0 * 1024.0
private val CHUNKS = listOf(4096, 65536)
private const val ESC = "\u001b"

private class Setup(
    val renderer: MetalRenderer?,
    val text: TextStyle,
) {
    val terminal = Terminal(BASELINE_COLUMNS, ROWS, SCROLLBACK)
    val frame = Frame()
    val sources = RowSources()
    private val size = viewSize(Cells(BASELINE_COLUMNS, ROWS), text.cell)

    fun feed(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ) {
        terminal.feed(bytes, from, to)
        if (!terminal.updateFrame(frame)) return
        sources.absorb(frame.damage, frame.rows)
        renderer?.render(frame, sources, size.first, size.second, SCALE, focused = true, readBack = false)
    }
}

private fun median(runs: List<Duration>): Duration = runs.sorted()[runs.size / 2]

/** The whole input in [chunk]-byte reads, each followed by an update and, with a renderer, a drawn frame. */
private fun throughput(
    input: ByteArray,
    chunk: Int,
    renderer: MetalRenderer?,
    text: TextStyle,
): Duration {
    val setup = Setup(renderer, text)
    val mark = TimeSource.Monotonic.markNow()
    var at = 0
    while (at < input.size) {
        val end = minOf(at + chunk, input.size)
        setup.feed(input, at, end)
        at = end
    }
    return mark.elapsedNow()
}

/** The median time of one frame after [change], on a full screen. */
private fun frameCost(
    renderer: MetalRenderer,
    text: TextStyle,
    change: (Int) -> ByteArray,
): Duration {
    val setup = Setup(renderer, text)
    setup.feed(baselineInput(), 0, BASELINE_COLUMNS * ROWS * 2)
    repeat(FRAMES / 10) { setup.feed(change(it)) }
    val times =
        List(FRAMES) {
            val bytes = change(it)
            val mark = TimeSource.Monotonic.markNow()
            setup.feed(bytes)
            mark.elapsedNow()
        }
    return median(times)
}

private fun letters(
    seed: Int,
    count: Int,
): String = CharArray(count) { 'A' + (seed + it) % 26 }.concatToString()

internal fun rendererBenchMarkdown(): String {
    val text = TextStyle()
    val renderer = MetalRenderer.create(text, Palette()) ?: return "No Metal device: nothing measured.\n"
    val input = baselineInput()
    val out = StringBuilder()
    out.append("| reads of | frames drawn | seconds | MiB/s |\n| --- | --- | --- | --- |\n")
    for (chunk in CHUNKS) {
        for (drawn in listOf(false, true)) {
            throughput(input, chunk, if (drawn) renderer else null, text)
            val time = median(List(RUNS) { throughput(input, chunk, if (drawn) renderer else null, text) })
            val seconds = time.toDouble(DurationUnit.SECONDS)
            val mibs = BASELINE_BYTES / MIB / seconds
            out.append("| $chunk B | ${if (drawn) "yes" else "no"} | ${fixed(seconds, 6)} | ${fixed(mibs, 2)} |\n")
        }
    }
    val cases =
        listOf<Pair<String, (Int) -> ByteArray>>(
            "one cell changed" to { i -> "$ESC[1;1H${letters(i, 1)}".encodeToByteArray() },
            "one line scrolled in" to { i -> "$ESC[$ROWS;1H\r\n${letters(i, BASELINE_COLUMNS - 1)}".encodeToByteArray() },
            "every row changed" to { i ->
                (0 until ROWS).joinToString("") { r -> "$ESC[${r + 1};1H${letters(i + r, BASELINE_COLUMNS)}" }.encodeToByteArray()
            },
        )
    out.append("\n| frame after | median µs |\n| --- | --- |\n")
    for ((name, change) in cases) {
        val micros = frameCost(renderer, text, change).toDouble(DurationUnit.MICROSECONDS)
        out.append("| $name | ${fixed(micros, 1)} |\n")
    }
    return out.toString()
}
