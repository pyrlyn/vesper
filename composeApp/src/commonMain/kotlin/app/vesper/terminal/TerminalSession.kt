package app.vesper.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vesper.terminal.vt.Terminal
import app.vesper.terminal.vt.TerminalListener
import app.vesper.terminal.vt.Utf8Decoder
import app.vesper.terminal.vt.VtHandler
import app.vesper.terminal.vt.VtParser
import app.vesper.util.nowMillis
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class BlockStatus { Running, Success, Failed, Interrupted }

/** One command and its output. Mutable fields are guarded by the owning session's lock. */
class Block(
    val id: Long,
    val command: String,
    val cwd: String,
    val startedAt: Long,
    val terminal: Terminal,
) {
    @Volatile var status: BlockStatus = BlockStatus.Running
    @Volatile var exitCode: Int? = null
    @Volatile var finishedAt: Long? = null
    @Volatile var interruptRequested = false

    val isRunning: Boolean get() = status == BlockStatus.Running
    val durationMs: Long get() = (finishedAt ?: nowMillis()) - startedAt
}

enum class SessionMode {
    /** Waiting for the shell's first prompt marker. */
    Starting,

    /** Shell integration active: output is split into blocks. */
    Blocks,

    /** No shell integration: classic full-screen terminal. */
    Raw,
}

/**
 * A shell running in a PTY. Output is parsed once by a [VtParser] and routed to:
 * the current block's emulator (between OSC 133 C and D), a scratch emulator for the
 * shell's own prompt/echo region (never shown), or the raw emulator when the shell
 * has no integration.
 */
class TerminalSession(
    val id: Int,
    private val launch: ShellLaunch,
    private val launcher: PtyLauncher,
    private val scope: CoroutineScope,
    initialCols: Int = 120,
    initialRows: Int = 38,
    private val colorQuery: (Int) -> String? = { null },
) {
    val lock = Any()

    var cols = initialCols
        private set
    var rows = initialRows
        private set

    val shellName: String get() = launch.shellName

    // ── Compose-observable state ─────────────────────────────────────────
    /** Bumped (coalesced) whenever anything visible changes. */
    var revision by mutableLongStateOf(0L)
        private set
    var cwd by mutableStateOf(launch.cwd)
        private set
    var title by mutableStateOf("")
        private set
    var mode by mutableStateOf(SessionMode.Starting)
        private set
    var exited by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var altScreen by mutableStateOf(false)
        private set
    var bellCount by mutableLongStateOf(0L)
        private set
    val startedAt = nowMillis()

    // ── Guarded by [lock] ────────────────────────────────────────────────
    private val blocks = ArrayList<Block>()
    private var nextBlockId = 1L
    private val pendingCommands = ArrayDeque<String>()
    private var startup: Terminal
    private var promptTerm: Terminal
    private var current: Terminal
    private var currentBlock: Block? = null
    private val decoder = Utf8Decoder()
    private val history = ArrayList<String>()

    private val pty: PtyConnection
    val pid: Long get() = pty.pid

    private val router = object : VtHandler {
        override fun print(cp: Int) = current.print(cp)
        override fun execute(code: Int) = current.execute(code)
        override fun csi(params: IntArray, isSub: BooleanArray, paramCount: Int, private: Char, intermediates: String, final: Char) =
            current.csi(params, isSub, paramCount, private, intermediates, final)

        override fun esc(intermediates: String, final: Char) = current.esc(intermediates, final)
        override fun osc(data: String) = handleOsc(data)
    }
    private val parser = VtParser(router)

    init {
        startup = newTerminal(rows)
        promptTerm = newTerminal(rows)
        current = startup
        if (!launch.integrated) mode = SessionMode.Raw
        pty = launcher.start(launch, cols, rows, ::onData, ::onExit)
        if (launch.integrated) {
            // Fall back to a classic terminal if the shell never reports a prompt.
            scope.launch {
                kotlinx.coroutines.delay(INTEGRATION_TIMEOUT_MS)
                synchronized(lock) {
                    if (mode == SessionMode.Starting) mode = SessionMode.Raw
                }
                invalidate()
            }
        }
    }

    private fun newTerminal(rows: Int, block: () -> Block? = { null }) = Terminal(cols, rows).also { t ->
        t.listener = object : TerminalListener {
            override fun respond(text: String) = write(text)
            override fun onBell() {
                bellCount++
            }

            override fun onTitle(title: String) {
                this@TerminalSession.title = title
            }

            override fun onOsc(code: Int, data: String) {}
            override fun onAltScreen(active: Boolean) {
                altScreen = active
            }

            override fun onClearScrollback() {
                val b = block() ?: return
                // `clear` inside a block wipes the history above it, like a real screen clear.
                blocks.removeAll { it !== b }
            }

            override fun colorQuery(which: Int): String? = this@TerminalSession.colorQuery(which)
        }
    }

    // ── Output path (PTY reader thread) ──────────────────────────────────

    private fun onData(bytes: ByteArray, length: Int) {
        val text = decoder.decode(bytes, 0, length)
        synchronized(lock) {
            parser.feed(text)
        }
        invalidate()
    }

    private fun onExit(code: Int) {
        synchronized(lock) {
            currentBlock?.let { finishBlock(it, code) }
            currentBlock = null
        }
        exited = true
        running = false
        invalidate()
    }

    private fun handleOsc(data: String) {
        when {
            data.startsWith("133;") -> handleShellMarker(data.substring(4))
            data.startsWith("7;") -> parseFileUrl(data.substring(2))?.let { cwd = it }
            data.startsWith("6973;") -> Unit // reserved for Vesper extensions
            else -> current.osc(data)
        }
    }

    private fun handleShellMarker(marker: String) {
        val kind = marker.substringBefore(';')
        when (kind) {
            "A" -> {
                if (mode != SessionMode.Blocks) {
                    mode = SessionMode.Blocks
                    adoptStartupOutput()
                }
                currentBlock?.let { finishBlock(it, null) }
                currentBlock = null
                running = false
                promptTerm.reset()
                current = promptTerm
            }
            "C" -> {
                if (mode != SessionMode.Blocks) return
                val command = pendingCommands.removeFirstOrNull() ?: promptTerm.plainText().trim()
                lateinit var block: Block
                val term = newTerminal(rows) { block }
                block = Block(nextBlockId++, command, cwd, nowMillis(), term)
                blocks += block
                currentBlock = block
                current = term
                running = true
            }
            "D" -> {
                val code = marker.substringAfter(';', "").substringBefore(';').toIntOrNull()
                currentBlock?.let { finishBlock(it, code) }
                currentBlock = null
                running = false
                current = promptTerm
            }
        }
    }

    /** Output printed before the first prompt (motd, rc warnings) becomes a block of its own. */
    private fun adoptStartupOutput() {
        if (startup.mainLineCount() == 0) return
        val text = startup.plainText()
        if (text.isBlank()) return
        val block = Block(nextBlockId++, "", cwd, startedAt, startup)
        block.status = BlockStatus.Success
        block.exitCode = 0
        block.finishedAt = nowMillis()
        blocks += block
    }

    private fun finishBlock(block: Block, code: Int?) {
        block.exitCode = code
        block.finishedAt = nowMillis()
        block.status = when {
            block.interruptRequested || code == 130 -> BlockStatus.Interrupted
            code == null || code == 0 -> BlockStatus.Success
            else -> BlockStatus.Failed
        }
        if (block.terminal.isAltScreen) block.terminal.feed("\u001b[?1049l")
        altScreen = false
        if (block.command == "clear" || block.command == "reset") blocks.remove(block)
    }

    @Volatile private var invalidatePending = false

    private fun invalidate() {
        if (invalidatePending) return
        invalidatePending = true
        scope.launch(Dispatchers.Main) {
            invalidatePending = false
            revision++
        }
    }

    // ── Read API (UI thread) ─────────────────────────────────────────────

    fun blocksSnapshot(): List<Block> = synchronized(lock) { blocks.toList() }

    fun currentBlock(): Block? = synchronized(lock) { currentBlock }

    /** The emulator to draw when the session is a classic terminal or a block is full-screen. */
    fun fullscreenTerminal(): Terminal? = synchronized(lock) {
        when {
            mode == SessionMode.Raw -> current
            currentBlock?.terminal?.isAltScreen == true -> currentBlock!!.terminal
            else -> null
        }
    }

    /** Terminal receiving keystrokes right now (for key encoding modes). */
    fun activeTerminal(): Terminal = synchronized(lock) { current }

    fun commandHistory(): List<String> = synchronized(lock) { history.toList() }

    // ── Input ────────────────────────────────────────────────────────────

    fun write(text: String) = write(text.encodeToByteArray())

    fun write(bytes: ByteArray) {
        if (!exited) runCatching { pty.write(bytes) }
    }

    /**
     * Runs [command] at the prompt, or sends it as a line of input to the running program
     * when a block is busy (e.g. a REPL).
     */
    fun submit(command: String) {
        val trimmed = command.trimEnd()
        val atPrompt: Boolean
        val paste: Boolean
        synchronized(lock) {
            atPrompt = mode == SessionMode.Blocks && currentBlock == null
            paste = promptTerm.bracketedPaste && trimmed.contains('\n')
            if (atPrompt && trimmed.isNotBlank()) {
                pendingCommands.addLast(trimmed)
                history.remove(trimmed)
                history.add(trimmed)
            }
        }
        when {
            atPrompt && trimmed.isBlank() -> Unit
            atPrompt -> write("\u0015" + (if (paste) "\u001b[200~$trimmed\u001b[201~" else trimmed) + "\r")
            else -> write("$command\r")
        }
    }

    fun interrupt() {
        synchronized(lock) { currentBlock?.interruptRequested = true }
        write("\u0003")
    }

    fun paste(text: String) {
        val bracketed = synchronized(lock) { current.bracketedPaste }
        write(if (bracketed) "\u001b[200~$text\u001b[201~" else text)
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        synchronized(lock) {
            cols = newCols
            rows = newRows
            promptTerm.resize(newCols, newRows)
            currentBlock?.terminal?.resize(newCols, newRows)
            if (mode != SessionMode.Blocks) current.resize(newCols, newRows)
        }
        runCatching { pty.resize(newCols, newRows) }
        invalidate()
    }

    fun clearBlocks() {
        synchronized(lock) { blocks.removeAll { it !== currentBlock } }
        invalidate()
    }

    fun removeBlock(block: Block) {
        synchronized(lock) { if (block !== currentBlock) blocks.remove(block) }
        invalidate()
    }

    fun close() {
        runCatching { pty.kill() }
    }

    companion object {
        const val INTEGRATION_TIMEOUT_MS = 4_000L

        /** `file://host/path` → `/path` (percent-decoded). */
        fun parseFileUrl(url: String): String? {
            if (!url.startsWith("file://")) return null
            val afterScheme = url.removePrefix("file://")
            val slash = afterScheme.indexOf('/')
            if (slash < 0) return null
            var path = percentDecode(afterScheme.substring(slash))
            // Windows: /C:/Users → C:/Users
            if (path.length > 2 && path[2] == ':' && path[0] == '/') path = path.substring(1)
            return path
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = ArrayList<Byte>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) {
                        out += v.toByte()
                        i += 3
                        continue
                    }
                }
                c.toString().encodeToByteArray().forEach { out += it }
                i++
            }
            return out.toByteArray().decodeToString()
        }
    }
}
