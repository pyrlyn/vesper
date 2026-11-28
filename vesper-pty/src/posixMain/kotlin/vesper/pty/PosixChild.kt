package vesper.pty

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
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
import platform.posix.errno
import platform.posix.strerror
import vesper.pty.posix.vesper_pty_close
import vesper.pty.posix.vesper_pty_dup
import vesper.pty.posix.vesper_pty_environ
import vesper.pty.posix.vesper_pty_hangup
import vesper.pty.posix.vesper_pty_is_executable
import vesper.pty.posix.vesper_pty_read
import vesper.pty.posix.vesper_pty_reap
import vesper.pty.posix.vesper_pty_resize
import vesper.pty.posix.vesper_pty_spawn
import vesper.pty.posix.vesper_pty_wait
import vesper.pty.posix.vesper_pty_write_all
import vesper.sync.Monitor

private const val STAGE_OPEN = 1

private fun describe(code: Int): String = strerror(code)?.toKString() ?: "error $code"

private fun inheritedEnvironment(): List<String> {
    val entries = vesper_pty_environ() ?: return emptyList()
    return generateSequence(0) { it + 1 }
        .map { entries[it] }
        .takeWhile { it != null }
        .map { it!!.toKString() }
        .toList()
}

private fun List<String>.variable(name: String): String? = lastOrNull { it.startsWith("$name=") }?.substringAfter('=')

/**
 * The program's path: as given when it names a directory, otherwise the first executable of that
 * name on the child's `PATH`, as `execvp` would find it. Searched here rather than in the child,
 * where the search would have to allocate between fork and exec.
 */
private fun resolveProgram(
    program: String,
    environment: List<String>,
): String {
    if ('/' in program) return program
    val path = environment.variable("PATH") ?: "/usr/bin:/bin"
    return path
        .split(':')
        .map { it.ifEmpty { "." } }
        .map { "$it/$program" }
        .firstOrNull { vesper_pty_is_executable(it) == 1 }
        ?: throw PtyException.Spawn("$program: not found on PATH")
}

internal actual fun spawnChild(options: SpawnOptions): Child {
    val environment = mergedEnvironment(inheritedEnvironment(), options.env)
    val program = options.program ?: environment.variable("SHELL")?.takeIf { it.isNotEmpty() } ?: "/bin/sh"
    val path = resolveProgram(program, environment)
    val argv = listOf(program) + if (options.program == null) emptyList() else options.args
    val size = options.size
    return memScoped {
        val cArgv = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
        argv.forEachIndexed { index, arg -> cArgv[index] = arg.cstr.ptr }
        cArgv[argv.size] = null
        val cEnv = allocArray<CPointerVar<ByteVar>>(environment.size + 1)
        environment.forEachIndexed { index, entry -> cEnv[index] = entry.cstr.ptr }
        cEnv[environment.size] = null
        val master = alloc<IntVar>()
        val stage = alloc<IntVar>()
        val error = alloc<IntVar>()
        val pid =
            vesper_pty_spawn(
                path,
                cArgv,
                cEnv,
                options.cwd,
                size.rows.toUShort(),
                size.cols.toUShort(),
                size.pixelWidth.toUShort(),
                size.pixelHeight.toUShort(),
                master.ptr,
                stage.ptr,
                error.ptr,
            )
        if (pid < 0) {
            val reason = describe(error.value)
            throw if (stage.value == STAGE_OPEN) PtyException.Open(reason) else PtyException.Spawn("$program: $reason")
        }
        PosixChild(pid, master.value)
    }
}

/** A child on a Unix PTY. Each worker gets its own descriptor of the master, so closing one never pulls another's from under a blocked call. */
private class PosixChild(
    private val pid: Int,
    private val master: Int,
) : Child {
    // Guards the master and the reaping: a hangup is sent only to a pid not yet reaped, so never to a reused one.
    private val state = Monitor(1)
    private var masterOpen = true
    private var reaped = false

    private fun duplicate(): Int =
        state.locked {
            if (!masterOpen) throw PtyException.Closed()
            val fd = vesper_pty_dup(master)
            if (fd < 0) throw PtyException.Attach(describe(errno))
            fd
        }

    override fun openReader(): ByteSource {
        val fd = duplicate()
        return object : ByteSource {
            override fun read(buffer: ByteArray): Int = buffer.usePinned { vesper_pty_read(fd, it.addressOf(0), buffer.size) }

            override fun close() = vesper_pty_close(fd)
        }
    }

    override fun openWriter(): ByteOutput {
        val fd = duplicate()
        return object : ByteOutput {
            override fun write(bytes: ByteArray): Boolean =
                bytes.isEmpty() || bytes.usePinned { vesper_pty_write_all(fd, it.addressOf(0), bytes.size) } == 0

            override fun close() = vesper_pty_close(fd)
        }
    }

    override fun waitExit(): ExitStatus {
        // ECHILD when the host reaps children itself (SIGCHLD ignored): the status is lost then.
        if (vesper_pty_wait(pid) != 0) return ExitStatus.UNKNOWN
        return state.locked {
            reaped = true
            memScoped {
                val code = alloc<IntVar>()
                val signal = alloc<IntVar>()
                when {
                    vesper_pty_reap(pid, code.ptr, signal.ptr) != 0 -> ExitStatus.UNKNOWN
                    signal.value != 0 -> ExitStatus(code.value.toUInt(), signal.value)
                    code.value < 0 -> ExitStatus.UNKNOWN
                    else -> ExitStatus(code.value.toUInt())
                }
            }
        }
    }

    override fun resize(size: PtySize) {
        val error =
            state.locked {
                if (!masterOpen) throw PtyException.Closed()
                vesper_pty_resize(
                    master,
                    size.rows.toUShort(),
                    size.cols.toUShort(),
                    size.pixelWidth.toUShort(),
                    size.pixelHeight.toUShort(),
                )
            }
        if (error != 0) throw PtyException.Resize(describe(error))
    }

    override fun hangUp(): Boolean = state.locked { !reaped && vesper_pty_hangup(pid) == 0 }

    override fun closeMaster() {
        state.locked {
            if (masterOpen) {
                masterOpen = false
                vesper_pty_close(master)
            }
        }
    }
}
