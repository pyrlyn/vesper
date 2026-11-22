@file:OptIn(ExperimentalForeignApi::class)

package scull.bench

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EAGAIN
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.O_WRONLY
import platform.posix.SIGKILL
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.getenv
import platform.posix.kill
import platform.posix.open
import platform.posix.pipe
import platform.posix.signal
import platform.posix.usleep
import platform.posix.waitpid
import platform.posix.write
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val POLL_MICROS = 10_000u
private const val CHUNK = 64 * 1024

internal actual fun environment(name: String): String? = getenv(name)?.toKString()

internal actual fun feedStdin(
    program: String,
    args: List<String>,
    bytes: ByteArray,
    timeout: Duration,
): FeedOutcome =
    memScoped {
        // A terminal that exits before reading everything must fail this feed, not kill the bench.
        signal(SIGPIPE, SIG_IGN)
        val fds = allocArray<IntVar>(2)
        if (pipe(fds) != 0) return FeedOutcome.Failed("pipe failed")
        val readEnd = fds[0]
        val writeEnd = fds[1]
        // Built before fork: the child may only call async-signal-safe functions.
        val argv = allocArray<CPointerVar<ByteVar>>(args.size + 2)
        argv[0] = program.cstr.ptr
        args.forEachIndexed { i, arg -> argv[i + 1] = arg.cstr.ptr }
        argv[args.size + 1] = null
        val devNull = "/dev/null".cstr.ptr

        val started = TimeSource.Monotonic.markNow()
        val pid = fork()
        if (pid == 0) {
            dup2(readEnd, STDIN_FILENO)
            val sink = open(devNull, O_WRONLY)
            dup2(sink, STDOUT_FILENO)
            dup2(sink, STDERR_FILENO)
            close(writeEnd)
            execvp(program, argv)
            _exit(127)
        }
        close(readEnd)
        if (pid < 0) {
            close(writeEnd)
            return FeedOutcome.Failed("failed to start")
        }

        val status = alloc<IntVar>()
        fcntl(writeEnd, F_SETFL, O_NONBLOCK)
        var offset = 0
        var exited = false
        bytes.usePinned { pinned ->
            while (offset < bytes.size && !exited && started.elapsedNow() < timeout) {
                val n = write(writeEnd, pinned.addressOf(offset), minOf(CHUNK, bytes.size - offset).toULong())
                when {
                    n > 0 -> {
                        offset += n.toInt()
                    }

                    errno == EAGAIN -> {
                        exited = waitpid(pid, status.ptr, WNOHANG) == pid
                        if (!exited) usleep(POLL_MICROS)
                    }

                    else -> {
                        break
                    }
                }
            }
        }
        close(writeEnd)

        while (!exited) {
            exited = waitpid(pid, status.ptr, WNOHANG) == pid
            if (exited) break
            if (started.elapsedNow() >= timeout) {
                kill(pid, SIGKILL)
                waitpid(pid, status.ptr, 0)
                return FeedOutcome.Failed("timed out")
            }
            usleep(POLL_MICROS)
        }
        val elapsed = started.elapsedNow()
        // WIFEXITED and WEXITSTATUS are macros that cinterop does not expose.
        val code = status.value
        when {
            code and 0x7F != 0 -> FeedOutcome.Failed("killed by a signal")
            (code shr 8) and 0xFF == 127 -> FeedOutcome.Failed("failed to start")
            (code shr 8) and 0xFF != 0 -> FeedOutcome.Failed("exited nonzero")
            else -> FeedOutcome.Done(elapsed)
        }
    }
