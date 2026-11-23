package scull.term

import scull.parser.Osc
import kotlin.io.encoding.Base64

// OSC commands: `Ps ; Pt`, read into events (titles, the directory, shell marks, the clipboard,
// notifications) and into the link table (OSC 8, in Links.kt). Separate from the queue because each
// command is a parser of untrusted text with its own caps; any other code is ignored, as xterm does.
//
// Sources: xterm ctlseqs (OSC 0/1/2, 52), iTerm2 escape codes (OSC 7, 9), FinalTerm and VS Code
// shell integration (OSC 133), rxvt-unicode `notify` (OSC 777), ConEmu (OSC 9 subcommands).

/** The most bytes of text a title or a notification carries; longer ones are dropped, not cut. */
internal const val MAX_EVENT_TEXT = 1024

/** The most bytes of a URI (OSC 7 and OSC 8) and of OSC 8's parameters; longer ones are dropped. */
internal const val MAX_URI_BYTES = 4096

/** The most clipboard bytes one OSC 52 carries, after decoding, either way. */
public const val MAX_CLIPBOARD_BYTES: Int = 1 shl 20

internal const val SEMICOLON: Byte = ';'.code.toByte()
private const val MAX_CODE_DIGITS = 5
private const val DECIMAL = 10

private const val OSC_DIRECTORY = 7
private const val OSC_LINK = 8
private const val OSC_NOTIFY = 9
private const val OSC_CLIPBOARD = 52
private const val OSC_SHELL = 133
private const val OSC_RXVT = 777

/** The selections OSC 52 may name: clipboard, primary, secondary, select, cut buffers 0 to 7. */
private const val CLIPBOARD_TARGETS = "cpqs01234567"

/** What xterm reads an empty target list as. */
private const val DEFAULT_TARGETS = "s0"

/** The padding is optional because some programs strip it; anything else not base64 drops the write. */
private val clipboardBase64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

internal fun State.dispatchOsc(osc: Osc) {
    endCluster()
    var code = 0
    var at = 0
    while (at < osc.size && osc[at] != SEMICOLON) {
        val digit = osc[at] - '0'.code
        if (digit !in 0..9 || at == MAX_CODE_DIGITS) return
        code = code * DECIMAL + digit
        at++
    }
    if (at == 0 || at == osc.size) return
    val payload = ByteArray(osc.size - at - 1) { osc[at + 1 + it] }
    when (code) {
        TitleTarget.Both.code, TitleTarget.Icon.code, TitleTarget.Window.code -> title(code, payload)
        OSC_DIRECTORY -> directory(payload)
        OSC_LINK -> link(payload)
        OSC_NOTIFY -> notify(payload)
        OSC_CLIPBOARD -> clipboard(payload, osc.bellTerminated)
        OSC_SHELL -> shellMark(payload)
        OSC_RXVT -> rxvt(payload)
    }
}

/** [bytes] from [from] until [to] as UTF-8, or null when they are not; such text is dropped rather than shown with replacement characters. */
internal fun utf8(
    bytes: ByteArray,
    from: Int = 0,
    to: Int = bytes.size,
): String? =
    try {
        bytes.decodeToString(from, to, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

private fun State.title(
    code: Int,
    payload: ByteArray,
) {
    if (payload.size > MAX_EVENT_TEXT) return
    val target = TitleTarget.entries.first { it.code == code }
    events.push(Event.Title(target, utf8(payload) ?: return))
}

private fun State.directory(payload: ByteArray) {
    if (payload.isEmpty() || payload.size > MAX_URI_BYTES) return
    events.push(Event.Directory(utf8(payload) ?: return))
}

/** OSC 133: the mark is the first field; D's second field is the exit code. Other fields (`aid=`, `cl=`) are not read. */
private fun State.shellMark(payload: ByteArray) {
    val fields = split(payload)
    val mark = fields[0]
    if (mark.size != 1) return
    val kind = ShellMarkKind.entries.firstOrNull { it.letter.code == mark[0].toInt() } ?: return
    val exit = if (kind == ShellMarkKind.CommandFinished) fields.getOrNull(1)?.let { utf8(it)?.toIntOrNull() } else null
    events.push(Event.ShellMark(kind, exit))
}

/**
 * OSC 9: iTerm2's notification. ConEmu uses the same code for numbered subcommands (`9;4;…` is
 * progress, `9;9;…` a directory), so a payload that starts with a number is never a notification.
 */
private fun State.notify(payload: ByteArray) {
    var digits = 0
    while (digits < payload.size && payload[digits] in '0'.code.toByte()..'9'.code.toByte()) digits++
    if (digits > 0 && (digits == payload.size || payload[digits] == SEMICOLON)) return
    if (payload.isEmpty() || payload.size > MAX_EVENT_TEXT) return
    events.push(Event.Notification("", utf8(payload) ?: return))
}

/** OSC 777: only rxvt's `notify;title;body` is read; the body keeps its own semicolons. */
private fun State.rxvt(payload: ByteArray) {
    val command = payload.indexOf(SEMICOLON)
    if (command < 0 || utf8(payload, 0, command) != "notify") return
    val rest = command + 1
    if (payload.size - rest > MAX_EVENT_TEXT) return
    val titleEnd = payload.indexOf(SEMICOLON, rest).let { if (it < 0) payload.size else it }
    val title = utf8(payload, rest, titleEnd) ?: return
    val body = if (titleEnd < payload.size) utf8(payload, titleEnd + 1) ?: return else ""
    events.push(Event.Notification(title, body))
}

/** OSC 52: `Pc ; Pd`, where Pd is base64 to write or `?` to read. */
private fun State.clipboard(
    payload: ByteArray,
    bellTerminated: Boolean,
) {
    val split = payload.indexOf(SEMICOLON)
    if (split < 0) return
    val targets = clipboardTargets(utf8(payload, 0, split) ?: return) ?: return
    val data = payload.copyOfRange(split + 1, payload.size)
    if (data.size == 1 && data[0] == '?'.code.toByte()) {
        clipboardReadBell = bellTerminated
        events.push(Event.ClipboardRead(targets))
        return
    }
    // Checked on the encoded size first so a flood is not decoded only to be dropped.
    if (data.size > base64Length(MAX_CLIPBOARD_BYTES)) return
    val decoded =
        try {
            clipboardBase64.decode(data)
        } catch (_: IllegalArgumentException) {
            return
        }
    events.push(Event.ClipboardWrite(targets, decoded))
}

/** [targets] if every letter names a selection (empty reads as xterm's default), else null. */
internal fun clipboardTargets(targets: String): String? =
    when {
        targets.isEmpty() -> DEFAULT_TARGETS
        targets.length > CLIPBOARD_TARGETS.length || targets.any { it !in CLIPBOARD_TARGETS } -> null
        else -> targets
    }

/** The OSC 52 reply carrying [data] for [targets], ended the way the last read was. */
internal fun State.clipboardReply(
    targets: String,
    data: ByteArray,
): ByteArray {
    val valid = requireNotNull(clipboardTargets(targets)) { "not OSC 52 targets: $targets" }
    require(data.size <= MAX_CLIPBOARD_BYTES) { "${data.size} clipboard bytes, at most $MAX_CLIPBOARD_BYTES" }
    val end = if (clipboardReadBell) "\u0007" else "\u001B\\"
    return "\u001B]52;$valid;${Base64.Default.encode(data)}$end".encodeToByteArray()
}

/** The base64 length of [bytes] bytes, padded. */
internal fun base64Length(bytes: Int): Int = (bytes + 2) / 3 * 4

private fun ByteArray.indexOf(
    byte: Byte,
    from: Int,
): Int {
    for (i in from until size) if (this[i] == byte) return i
    return -1
}

/** [payload] split on `;`; always at least one field. */
private fun split(payload: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var start = 0
    for (i in 0..payload.size) {
        if (i == payload.size || payload[i] == SEMICOLON) {
            out += payload.copyOfRange(start, i)
            start = i + 1
        }
    }
    return out
}
