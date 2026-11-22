@file:OptIn(ExperimentalForeignApi::class)

package scull.bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.time.Duration

internal actual fun environment(name: String): String? = getenv(name)?.toKString()

// The Windows renderer and input numbers come from windows/Scull.Bench, which owns its children.
internal actual fun feedStdin(
    program: String,
    args: List<String>,
    bytes: ByteArray,
    timeout: Duration,
): FeedOutcome = FeedOutcome.Failed("no headless feed from the Windows build")
