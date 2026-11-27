@file:OptIn(ExperimentalForeignApi::class)

package vesper.bench

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fork
import platform.posix.kill
import platform.posix.open
import platform.posix.tcdrain
import platform.posix.usleep
import platform.posix.waitpid
import platform.posix.write
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val POLL_MICROS = 10_000u

internal actual fun runToExit(
    program: String,
    args: List<String>,
    timeout: Duration,
): RunOutcome =
    memScoped {
        // Built before fork: the child may only call async-signal-safe functions.
        val argv = allocArray<CPointerVar<ByteVar>>(args.size + 2)
        argv[0] = program.cstr.ptr
        args.forEachIndexed { i, arg -> argv[i + 1] = arg.cstr.ptr }
        argv[args.size + 1] = null
        val devNull = "/dev/null".cstr.ptr

        val started = TimeSource.Monotonic.markNow()
        val pid = fork()
        if (pid == 0) {
            // The terminal's own logging would interleave with the table on stdout.
            dup2(open(devNull, O_RDONLY), STDIN_FILENO)
            val sink = open(devNull, O_WRONLY)
            dup2(sink, STDOUT_FILENO)
            dup2(sink, STDERR_FILENO)
            execvp(program, argv)
            _exit(127)
        }
        if (pid < 0) return RunOutcome.Failed("failed to start")

        val status = alloc<IntVar>()
        while (waitpid(pid, status.ptr, WNOHANG) != pid) {
            if (started.elapsedNow() >= timeout) {
                kill(pid, SIGKILL)
                waitpid(pid, status.ptr, 0)
                return RunOutcome.Failed("timed out")
            }
            usleep(POLL_MICROS)
        }
        // WIFEXITED and WEXITSTATUS are macros that cinterop does not expose.
        val code = status.value
        when {
            code and 0x7F != 0 -> RunOutcome.Failed("killed by a signal")
            (code shr 8) and 0xFF == 127 -> RunOutcome.Failed("failed to start")
            (code shr 8) and 0xFF != 0 -> RunOutcome.Failed("exited ${(code shr 8) and 0xFF}")
            else -> RunOutcome.Exited
        }
    }

internal actual fun writeToTerminal(bytes: ByteArray): Boolean {
    var offset = 0
    bytes.usePinned { pinned ->
        while (offset < bytes.size) {
            val n = write(STDOUT_FILENO, pinned.addressOf(offset), (bytes.size - offset).toULong())
            when {
                n > 0 -> offset += n.toInt()
                n < 0 && errno == EINTR -> continue
                else -> return false
            }
        }
    }
    // A write returns once the bytes are in the PTY's buffer; tcdrain waits until the terminal has read them.
    return tcdrain(STDOUT_FILENO) == 0
}
