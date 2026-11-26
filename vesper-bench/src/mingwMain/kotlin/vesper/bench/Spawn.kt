package vesper.bench

import kotlin.time.Duration

// Only kitty has a headless feed, and kitty has no Windows build, so no row on Windows comes here.
internal actual fun feedStdin(
    program: String,
    args: List<String>,
    bytes: ByteArray,
    timeout: Duration,
): FeedOutcome = FeedOutcome.Failed("no headless feed from the Windows build")
