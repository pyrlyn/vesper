package vesper.bench

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import vesper.harness.environment
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a headless feed may run before it counts as `unavailable`. */
internal val FEED_TIMEOUT: Duration = 5.seconds

/** A reference terminal: where to find it and, if it has one, a windowless way to feed stdin. */
private class Spec(
    val name: String,
    val programs: List<String>,
    val fallback: String? = null,
    val headless: List<String>? = null,
)

// kitty documents `--start-as=hidden` as a windowless start; the others have no stdin feed that
// stays off-screen. Warp's terminal build may be named apart from its CLI, and its macOS binary
// is not on PATH.
private val SPECS =
    listOf(
        Spec("kitty", listOf("kitty"), headless = listOf("--start-as=hidden")),
        Spec("WezTerm", listOf("wezterm")),
        Spec("Alacritty", listOf("alacritty")),
        Spec("foot", listOf("foot")),
        Spec("Contour", listOf("contour")),
        Spec("Warp", listOf("warp", "warp-terminal"), fallback = "/Applications/Warp.app/Contents/MacOS/stable"),
    )

internal fun referenceRows(input: ByteArray): List<Row> = SPECS.map { rowFor(it, input) }

private fun rowFor(
    spec: Spec,
    input: ByteArray,
): Row {
    val program = locate(spec) ?: return Row.notInstalled(spec.name, input.size)
    val args = spec.headless ?: return Row.unavailable(spec.name, input.size, "no headless stdin feed")
    return when (val outcome = feedStdin(program.toString(), args, input, FEED_TIMEOUT)) {
        is FeedOutcome.Done -> Row.measured(spec.name, input.size, outcome.elapsed)
        is FeedOutcome.Failed -> Row.unavailable(spec.name, input.size, outcome.reason)
    }
}

private fun locate(spec: Spec): Path? {
    val dirs = environment("PATH")?.split(pathSeparator)?.filter { it.isNotEmpty() }.orEmpty()
    for (program in spec.programs) {
        dirs.map { it.toPath() / programFile(program) }.firstOrNull(::isFile)?.let { return it }
    }
    return spec.fallback?.toPath()?.takeIf(::isFile)
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

internal sealed interface FeedOutcome {
    class Done(
        val elapsed: Duration,
    ) : FeedOutcome

    class Failed(
        val reason: String,
    ) : FeedOutcome
}

/** Runs [program] with [args], writes [bytes] to its stdin and waits at most [timeout] for it. */
internal expect fun feedStdin(
    program: String,
    args: List<String>,
    bytes: ByteArray,
    timeout: Duration,
): FeedOutcome
