package scull.pty

private const val MAX_U16 = 0xFFFF

/**
 * Window size in cells, with the window in pixels where the system uses it (the `TIOCGWINSZ` pixel
 * fields; ConPTY ignores them).
 */
public data class PtySize(
    val rows: Int = 24,
    val cols: Int = 80,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
) {
    init {
        require(rows in 1..MAX_U16 && cols in 1..MAX_U16) { "a PTY of $cols by $rows cells" }
        require(pixelWidth in 0..MAX_U16 && pixelHeight in 0..MAX_U16) { "a PTY of $pixelWidth by $pixelHeight pixels" }
    }
}

/** What to run and where. */
public data class SpawnOptions(
    /** The program, searched on `PATH`; null runs the user's shell. */
    val program: String? = null,
    /** Arguments; ignored for the default shell. */
    val args: List<String> = emptyList(),
    /** Working directory; the parent's when null. */
    val cwd: String? = null,
    /** Variables added to, or replacing those of, the inherited environment. */
    val env: Map<String, String> = emptyMap(),
    /** Initial window size. */
    val size: PtySize = PtySize(),
) {
    public companion object {
        /** Runs [program] with [args]. */
        public fun command(
            program: String,
            vararg args: String,
        ): SpawnOptions = SpawnOptions(program = program, args = args.toList())
    }
}

/**
 * Refuses what the system's C strings cannot carry: a NUL would cut the string short and run
 * something other than what was asked, and `=` in a variable name would split it differently.
 */
internal fun SpawnOptions.validate() {
    val texts = listOfNotNull(program, cwd) + args + env.keys + env.values
    if (texts.any { '\u0000' in it }) throw PtyException.Spawn("a NUL character in the command, directory or environment")
    if (env.keys.any { it.isEmpty() || '=' in it }) throw PtyException.Spawn("an empty environment variable name or one with '='")
    if (program?.isEmpty() == true) throw PtyException.Spawn("an empty program name")
}

/** The inherited environment, as "KEY=value" entries, with [extra] added or replacing. */
internal fun mergedEnvironment(
    inherited: List<String>,
    extra: Map<String, String>,
    sameName: (String, String) -> Boolean = { a, b -> a == b },
): List<String> {
    val kept = inherited.filter { entry -> extra.keys.none { sameName(it, entry.substringBefore('=')) } }
    return kept + extra.map { (key, value) -> "$key=$value" }
}
