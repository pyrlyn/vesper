package vesper.bench

import kotlin.time.Duration

// The Windows runner has none of the reference terminals, and the measured table comes from Linux, so
// the Windows build launches no terminal: every installed one is `unavailable` there.
internal actual fun selfPath(): String? = null

internal actual fun runToExit(
    program: String,
    args: List<String>,
    timeout: Duration,
): RunOutcome = RunOutcome.Failed("no launcher in the Windows build")

internal actual fun writeToTerminal(bytes: ByteArray): Boolean = false
