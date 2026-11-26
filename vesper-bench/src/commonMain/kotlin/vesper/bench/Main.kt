package vesper.bench

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.SYSTEM
import vesper.harness.baselineInput
import kotlin.system.exitProcess

/**
 * Writes `docs/benchmarks/baseline.md` (or the path in the first argument) and prints the table.
 * With `parser`, `grid` or `term` as the argument it prints the parser throughput table, the grid
 * memory and scroll tables or the terminal throughput table instead. `write <file>` is what a
 * reference terminal runs: it writes the payload to that terminal and leaves the time in `<file>`.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "parser" -> return print(parserMarkdown())
        "grid" -> return print(gridMarkdown())
        "term" -> return print(termMarkdown())
        WRITE_COMMAND -> return recordWrites(args.getOrNull(1))
    }
    val path = (args.firstOrNull() ?: "docs/benchmarks/baseline.md").toPath()
    val text = baselineMarkdown()
    try {
        path.parent?.let(FileSystem.SYSTEM::createDirectories)
        FileSystem.SYSTEM.write(path) { writeUtf8(text) }
    } catch (e: IOException) {
        println("vesper-bench: cannot write $path: ${e.message}")
        exitProcess(1)
    }
    print(text)
}

private fun recordWrites(file: String?) {
    if (file == null) {
        println("vesper-bench: $WRITE_COMMAND needs the file for the time")
        exitProcess(2)
    }
    val elapsed = timeWindowWrites(baselineInput()) ?: exitProcess(1)
    try {
        FileSystem.SYSTEM.write(file.toPath()) { writeUtf8("${elapsed.inWholeNanoseconds}\n") }
    } catch (_: IOException) {
        exitProcess(1)
    }
}
