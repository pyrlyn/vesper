package scull.bench

import kotlin.time.Duration

// ConPTY spawning for the bench belongs to the Windows host; until then the row reports a failure.
internal actual fun feedStdin(
    program: String,
    args: List<String>,
    bytes: ByteArray,
    timeout: Duration,
): FeedOutcome = FeedOutcome.Failed("no headless feed from the Windows build")
