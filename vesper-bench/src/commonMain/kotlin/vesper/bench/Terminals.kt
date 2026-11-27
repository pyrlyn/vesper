package vesper.bench

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import vesper.harness.environment
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** How long a terminal may take to open, run the writer and close before it counts as `unavailable`. */
internal val RUN_TIMEOUT: Duration = 60.seconds

/** Timed writes inside a terminal's window; an odd count gives the median a middle run. */
internal const val WINDOW_RUNS = 5

/** The subcommand a terminal runs as its program: it writes the payload and records the time. */
internal const val WRITE_COMMAND = "write"

/**
 * A reference terminal: where to find it and, if it has one, the arguments that open one window
 * running a program and exit when that program does.
 */
private class Spec(
    val name: String,
    val programs: List<String>,
    val fallbacks: List<String> = emptyList(),
    val launch: ((List<String>) -> List<String>)? = null,
)

// Each launch runs the writer as the window's only program and returns when it exits, so the wait
// below ends with the window. kitty would read the user's config otherwise; the containers in CI
// have none for the others. Warp opens its windows through the app and has no such launch.
private val SPECS =
    listOf(
        Spec(
            "kitty",
            listOf("kitty"),
            listOf("/Applications/kitty.app/Contents/MacOS/kitty"),
        ) { listOf("--config", "NONE") + it },
        Spec(
            "WezTerm",
            listOf("wezterm"),
            listOf("/Applications/WezTerm.app/Contents/MacOS/wezterm"),
        ) { listOf("start", "--always-new-process", "--") + it },
        Spec(
            "Alacritty",
            listOf("alacritty"),
            listOf("/Applications/Alacritty.app/Contents/MacOS/alacritty"),
        ) { listOf("-e") + it },
        Spec("foot", listOf("foot")) { it },
        Spec(
            "Contour",
            listOf("contour"),
            listOf("/Applications/contour.app/Contents/MacOS/contour"),
        ) { listOf("terminal") + it },
        Spec("Warp", listOf("warp", "warp-terminal"), listOf("/Applications/Warp.app/Contents/MacOS/stable")),
    )

internal fun referenceRows(bytes: Int): List<Row> = SPECS.map { rowFor(it, bytes) }

private fun rowFor(
    spec: Spec,
    bytes: Int,
): Row {
    val program = locate(spec) ?: return Row.notInstalled(spec.name, bytes)
    val launch = spec.launch ?: return Row.unavailable(spec.name, bytes, "no launch that waits for its program")
    val writer = selfPath() ?: return Row.unavailable(spec.name, bytes, "this build cannot run itself inside a terminal")
    val result = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "vesper-bench-${spec.name.lowercase()}.txt"
    FileSystem.SYSTEM.delete(result)
    val args = launch(listOf(writer, WRITE_COMMAND, result.toString()))
    return when (val outcome = runToExit(program.toString(), args, RUN_TIMEOUT)) {
        is RunOutcome.Failed -> {
            Row.unavailable(spec.name, bytes, outcome.reason)
        }

        RunOutcome.Exited -> {
            readTime(result)?.let { Row.measured(spec.name, bytes, it) }
                ?: Row.unavailable(spec.name, bytes, "the writer left no time")
        }
    }
}

/** The time the writer left in [path]; the terminal ran it, so anything but a positive count is no time. */
internal fun readTime(path: Path): Duration? =
    try {
        FileSystem.SYSTEM
            .read(path) { readUtf8() }
            .trim()
            .toLongOrNull()
            ?.takeIf { it > 0 }
            ?.nanoseconds
    } catch (_: IOException) {
        null
    }

private fun locate(spec: Spec): Path? {
    val dirs = environment("PATH")?.split(pathSeparator)?.filter { it.isNotEmpty() }.orEmpty()
    for (program in spec.programs) {
        dirs.map { it.toPath() / programFile(program) }.firstOrNull(::isFile)?.let { return it }
    }
    return spec.fallbacks.map { it.toPath() }.firstOrNull(::isFile)
}

private fun isFile(path: Path): Boolean = FileSystem.SYSTEM.metadataOrNull(path)?.isRegularFile == true

@OptIn(ExperimentalNativeApi::class)
private val isWindows = Platform.osFamily == OsFamily.WINDOWS

private val pathSeparator: Char = if (isWindows) ';' else ':'

/** The file [program] is in a `PATH` directory: Windows names programs with an extension, `.exe` for every terminal here. */
internal fun programFile(
    program: String,
    windows: Boolean = isWindows,
): String = if (windows) "$program.exe" else program

/** `<cpu> / <os>` for the table header. */
@OptIn(ExperimentalNativeApi::class)
internal fun machine(): String = "${Platform.cpuArchitecture.name.lowercase()} / ${Platform.osFamily.name.lowercase()}"

internal sealed interface RunOutcome {
    data object Exited : RunOutcome

    class Failed(
        val reason: String,
    ) : RunOutcome
}

/** Runs [program] with [args] and waits at most [timeout] for it to exit with status 0. */
internal expect fun runToExit(
    program: String,
    args: List<String>,
    timeout: Duration,
): RunOutcome

/** The bench's own executable, which a terminal runs as its program; null where it cannot be found. */
internal expect fun selfPath(): String?

/** Writes all of [bytes] to stdout and waits until the terminal has read them; false when it cannot. */
internal expect fun writeToTerminal(bytes: ByteArray): Boolean
