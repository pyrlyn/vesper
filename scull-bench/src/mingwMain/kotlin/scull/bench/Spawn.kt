package scull.bench

import kotlin.time.Duration

// The Windows renderer and input numbers come from windows/Scull.Bench, which owns its children.
internal actual fun feedStdin(
    program: String,
    args: List<String>,
    bytes: ByteArray,
    timeout: Duration,
): FeedOutcome = FeedOutcome.Failed("no headless feed from the Windows build")
