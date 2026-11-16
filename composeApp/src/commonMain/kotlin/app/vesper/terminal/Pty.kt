package app.vesper.terminal

/** A running process attached to a pseudo-terminal. */
interface PtyConnection {
    val pid: Long
    val isAlive: Boolean

    fun write(bytes: ByteArray)

    fun resize(cols: Int, rows: Int)

    fun kill()
}

/** How to start a shell: the platform layer fills this in (shell integration, env, login flags). */
data class ShellLaunch(
    val command: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    /** Short name shown in the UI, e.g. "zsh 5.9". */
    val shellName: String,
    /** True when the shell reports prompts/commands via OSC 133, enabling blocks. */
    val integrated: Boolean,
)

interface PtyLauncher {
    fun start(
        launch: ShellLaunch,
        cols: Int,
        rows: Int,
        onData: (ByteArray, Int) -> Unit,
        onExit: (Int) -> Unit,
    ): PtyConnection
}
