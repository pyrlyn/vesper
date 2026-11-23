package vesper.pty

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import vesper.pty.conpty.vesper_conpty_close_console
import vesper.pty.conpty.vesper_conpty_close_handle
import vesper.pty.conpty.vesper_conpty_environment
import vesper.pty.conpty.vesper_conpty_free_environment
import vesper.pty.conpty.vesper_conpty_kill
import vesper.pty.conpty.vesper_conpty_read
import vesper.pty.conpty.vesper_conpty_resize
import vesper.pty.conpty.vesper_conpty_spawn
import vesper.pty.conpty.vesper_conpty_wait
import vesper.pty.conpty.vesper_conpty_write_all

private const val STAGE_OPEN = 1

/** The entries of a block of NUL-terminated UTF-16 strings that ends with an empty one. */
private fun CPointer<UShortVar>.entries(): List<String> {
    val entries = mutableListOf<String>()
    var at = 0
    while (this[at] != 0.toUShort()) {
        val entry = StringBuilder()
        while (this[at] != 0.toUShort()) entry.append(this[at++].toInt().toChar())
        entries += entry.toString()
        at++
    }
    return entries
}

private fun inheritedEnvironment(): List<String> {
    val block = vesper_conpty_environment() ?: return emptyList()
    try {
        return block.entries()
    } finally {
        vesper_conpty_free_environment(block)
    }
}

/**
 * An environment block for CreateProcessW: names compare without case, and the block must be
 * sorted by name without case (CreateProcessW, lpEnvironment). Entries such as `=C:=C:\` that
 * carry the current directory of each drive start with '=' and are kept as they are.
 */
private fun environmentBlock(extra: Map<String, String>): String {
    val merged = mergedEnvironment(inheritedEnvironment(), extra) { a, b -> a.equals(b, ignoreCase = true) }
    val sorted = merged.sortedBy { it.substringBefore('=', it).uppercase() }
    return sorted.joinToString(separator = "\u0000", postfix = "\u0000")
}

/**
 * One argument quoted the way the C runtime splits a command line back into `argv`: backslashes
 * are literal except before a quote, where they are doubled, and the quote itself is escaped.
 */
internal fun quoteArgument(argument: String): String {
    if (argument.isNotEmpty() && argument.none { it == ' ' || it == '\t' || it == '\n' || it == '\u000B' || it == '"' }) {
        return argument
    }
    val quoted = StringBuilder("\"")
    var backslashes = 0
    for (char in argument) {
        when (char) {
            '\\' -> {
                backslashes++
            }

            '"' -> {
                repeat(backslashes * 2 + 1) { quoted.append('\\') }
                quoted.append('"')
                backslashes = 0
            }

            else -> {
                repeat(backslashes) { quoted.append('\\') }
                quoted.append(char)
                backslashes = 0
            }
        }
    }
    repeat(backslashes * 2) { quoted.append('\\') }
    return quoted.append('"').toString()
}

internal actual fun spawnChild(options: SpawnOptions): Child {
    val program = options.program ?: environmentValue("COMSPEC") ?: "cmd.exe"
    val arguments = if (options.program == null) emptyList() else options.args
    val commandLine = (listOf(program) + arguments).joinToString(" ") { quoteArgument(it) }
    val size = options.size
    return memScoped {
        val console = alloc<COpaquePointerVar>()
        val process = alloc<COpaquePointerVar>()
        val input = alloc<COpaquePointerVar>()
        val output = alloc<COpaquePointerVar>()
        val stage = alloc<IntVar>()
        val block = if (options.env.isEmpty()) null else environmentBlock(options.env)
        val cBlock =
            block?.let { text ->
                val chars = allocArray<UShortVar>(text.length + 1)
                text.forEachIndexed { index, char -> chars[index] = char.code.toUShort() }
                chars[text.length] = 0.toUShort()
                chars
            }
        val error =
            vesper_conpty_spawn(
                commandLine.wcstr,
                cBlock,
                options.cwd?.wcstr,
                size.cols.toShort(),
                size.rows.toShort(),
                console.ptr,
                process.ptr,
                input.ptr,
                output.ptr,
                stage.ptr,
            )
        if (error != 0) {
            val reason = "error 0x${error.toUInt().toString(16)}"
            throw if (stage.value == STAGE_OPEN) PtyException.Open(reason) else PtyException.Spawn("$program: $reason")
        }
        ConptyChild(console.value!!, process.value!!, input.value!!, output.value!!)
    }
}

private fun environmentValue(name: String): String? =
    inheritedEnvironment().lastOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }?.substringAfter('=')

/**
 * A child on a pseudo console. The reader and the writer own the two pipe ends; closing the console
 * ends the child's view of them, which ends the reader with a broken pipe.
 */
private class ConptyChild(
    private val console: COpaquePointer,
    private val process: COpaquePointer,
    private var input: COpaquePointer?,
    private var output: COpaquePointer?,
) : Child {
    // Guards the console and the process handle: neither is used after it is closed.
    private val state = Monitor(1)
    private var consoleOpen = true
    private var ended = false

    override fun openReader(): ByteSource {
        val handle = state.locked { output.also { output = null } } ?: throw PtyException.Attach("the output pipe was already taken")
        return object : ByteSource {
            override fun read(buffer: ByteArray): Int = buffer.usePinned { vesper_conpty_read(handle, it.addressOf(0), buffer.size) }

            override fun close() = vesper_conpty_close_handle(handle)
        }
    }

    override fun openWriter(): ByteOutput {
        val handle = state.locked { input.also { input = null } } ?: throw PtyException.Attach("the input pipe was already taken")
        return object : ByteOutput {
            override fun write(bytes: ByteArray): Boolean =
                bytes.isEmpty() || bytes.usePinned { vesper_conpty_write_all(handle, it.addressOf(0), bytes.size) } == 0

            override fun close() = vesper_conpty_close_handle(handle)
        }
    }

    override fun waitExit(): ExitStatus =
        memScoped {
            val code = alloc<UIntVar>()
            val error = vesper_conpty_wait(process, code.ptr)
            state.locked {
                ended = true
                vesper_conpty_close_handle(process)
            }
            if (error != 0) ExitStatus.UNKNOWN else ExitStatus(code.value)
        }

    override fun resize(size: PtySize) {
        val error =
            state.locked {
                if (!consoleOpen) throw PtyException.Closed()
                vesper_conpty_resize(console, size.cols.toShort(), size.rows.toShort())
            }
        if (error != 0) throw PtyException.Resize("error 0x${error.toUInt().toString(16)}")
    }

    override fun hangUp(): Boolean = state.locked { !ended && vesper_conpty_kill(process) == 0 }

    override fun closeMaster() {
        val (reader, writer) =
            state.locked {
                val open = consoleOpen
                consoleOpen = false
                if (open) vesper_conpty_close_console(console)
                // Ends not yet handed to a worker would otherwise leak.
                (output to input).also {
                    output = null
                    input = null
                }
            }
        reader?.let { vesper_conpty_close_handle(it) }
        writer?.let { vesper_conpty_close_handle(it) }
    }
}
