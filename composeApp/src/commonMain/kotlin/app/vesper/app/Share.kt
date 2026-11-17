package app.vesper.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vesper.terminal.Block

/** How a shared block is written. */
enum class ShareFormat(val title: String, val detail: String, val ext: String) {
    Markdown("Markdown", "For issues, pull requests and chat", "md"),
    Text("Plain text", "Exactly as it printed", "txt"),
}

/** The Share block dialog, open for [block] of [pane]. */
class ShareRequest(val pane: Pane, val block: Block) {
    var format by mutableStateOf(ShareFormat.Markdown)
    var includeEnv by mutableStateOf(true)
    var fullOutput by mutableStateOf(false)
}

/** What a block looks like when shared: [content] is redacted and ready to save or copy. */
class SharedBlock(
    val content: String,
    val fileName: String,
    /** Redacted output lines for the dialog's preview. */
    val outputLines: List<String>,
    val totalLines: Int,
    val secrets: Int,
    val secretNames: List<String>,
)

/** Shared output keeps this many lines unless the full output is included. */
const val ShareTrimLines = 100

/** The mark that replaces a secret in shared copies. */
const val ShareMark = "••••"

/**
 * Builds the shared copy of a finished command. [meta] is "exit 0 · 4.2s · 14:29", [env] the
 * working directory, branch and shell line, or null to leave it out. Everything is redacted with [patterns].
 */
fun shareBlock(
    command: String,
    output: String,
    meta: String,
    env: String?,
    format: ShareFormat,
    fullOutput: Boolean,
    patterns: List<String>,
    stamp: String,
): SharedBlock {
    val all = output.trimEnd().lines().let { if (it.size == 1 && it[0].isEmpty()) emptyList() else it }
    val kept = if (fullOutput || all.size <= ShareTrimLines) all else all.takeLast(ShareTrimLines)
    val omitted = all.size - kept.size
    val cmd = redactSecrets(command, patterns, ShareMark)
    val out = redactSecrets(kept.joinToString("\n"), patterns, ShareMark)
    val envR = env?.let { redactSecrets(it, patterns, ShareMark) }
    val outText = (if (omitted > 0) "… $omitted earlier lines not included\n" else "") + out.text
    val content = when (format) {
        ShareFormat.Markdown -> buildString {
            val fence = fenceFor(cmd.text + "\n" + outText)
            append(fence).append("sh\n$ ").append(cmd.text).append('\n').append(fence).append('\n')
            append(meta)
            envR?.let { append(" · ").append(it.text) }
            append("\n\n")
            if (outText.isNotEmpty()) append(fence).append('\n').append(outText).append('\n').append(fence).append('\n')
        }
        ShareFormat.Text -> buildString {
            append("$ ").append(cmd.text).append('\n')
            append("# ").append(meta)
            envR?.let { append(" · ").append(it.text) }
            append('\n')
            if (outText.isNotEmpty()) append(outText).append('\n')
        }
    }
    val parts = listOfNotNull(cmd, out, envR)
    return SharedBlock(
        content = content,
        fileName = "vesper-${slug(command)}-$stamp.${format.ext}",
        outputLines = out.text.lines(),
        totalLines = all.size,
        secrets = parts.sumOf { it.count },
        secretNames = parts.flatMap { it.names }.distinct(),
    )
}

/** A Markdown code fence longer than any run of backticks in [text]. */
private fun fenceFor(text: String): String {
    val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    return "`".repeat(maxOf(3, longest + 1))
}

/** "pnpm-test" for "pnpm test --filter x": the first two words, safe for a file name. */
private fun slug(command: String): String =
    command.trim().split(Regex("\\s+")).take(2).joinToString("-")
        .replace(Regex("[^A-Za-z0-9._-]"), "-").replace(Regex("-+"), "-").trim('-', '.')
        .take(32).ifEmpty { "block" }
