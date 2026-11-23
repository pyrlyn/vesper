package vesper.harness

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/** The variable Gradle sets to the repository's `tests` directory for every native test task. */
public const val TESTS_DIR_VARIABLE: String = "VESPER_TESTS_DIR"

/** The value of the environment variable [name], or null when it is not set. */
public expect fun environment(name: String): String?

/**
 * Reads `tests/<relative>` from the repository. Native test binaries have no resources, so every
 * module's tests read the shared fixtures through this one door.
 */
public fun readFixture(relative: String): String {
    val root = checkNotNull(environment(TESTS_DIR_VARIABLE)) { "$TESTS_DIR_VARIABLE is not set; run the tests through Gradle" }
    return FileSystem.SYSTEM.read(root.toPath() / relative) { readUtf8() }
}

/** A golden frame from `tests/harness`: the grid size and every row, each exactly [cols] characters. */
public class GoldenFrame(
    public val cols: Int,
    public val rows: Int,
    public val lines: List<String>,
)

/** Reads the golden frame `tests/harness/<name>`. */
public fun readGolden(name: String): GoldenFrame = parseGolden(name, readFixture("harness/$name"))

/**
 * A `cols rows` line, then exactly `rows` lines of `cols` characters, each ending in a newline.
 * The shape is checked, so a frame trimmed by an editor fails here rather than as a wrong grid.
 */
internal fun parseGolden(
    name: String,
    text: String,
): GoldenFrame {
    val all = text.replace("\r\n", "\n").split('\n')
    val size = all.first().split(' ').map { it.toIntOrNull() ?: 0 }
    require(size.size == 2 && size.all { it >= 1 }) { "golden frame $name: header '${all.first()}' is not 'cols rows'" }
    val (cols, rows) = size
    val lines = all.drop(1)
    require(lines.size == rows + 1 && lines.last().isEmpty()) { "golden frame $name: not $rows rows each ending in a newline" }
    val body = lines.dropLast(1)
    body.forEachIndexed { i, line -> require(line.length == cols) { "golden frame $name: row $i is not $cols characters" } }
    return GoldenFrame(cols, rows, body)
}
