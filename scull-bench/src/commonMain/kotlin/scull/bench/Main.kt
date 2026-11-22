package scull.bench

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.system.exitProcess

/**
 * Writes `docs/benchmarks/baseline.md` (or the path in the first argument) and prints the table.
 * With `parser` as the argument it prints the parser throughput table instead.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "parser") {
        print(parserMarkdown())
        return
    }
    val path = (args.firstOrNull() ?: "docs/benchmarks/baseline.md").toPath()
    val text = baselineMarkdown()
    try {
        path.parent?.let(FileSystem.SYSTEM::createDirectories)
        FileSystem.SYSTEM.write(path) { writeUtf8(text) }
    } catch (e: IOException) {
        println("scull-bench: cannot write $path: ${e.message}")
        exitProcess(1)
    }
    print(text)
}
