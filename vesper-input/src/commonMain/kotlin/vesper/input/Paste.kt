package vesper.input

// Pasted text. In bracketed paste mode (DECSET 2004) it is wrapped in `CSI 200 ~` … `CSI 201 ~`,
// and any marker inside the text is removed so a crafted clipboard cannot end the paste early and
// have the rest run as typed commands. Separate from the key encoders because paste is a trust
// boundary of its own: the text comes from another program.
// https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-Bracketed-Paste-Mode
//
// Policy beyond that (confirming control characters, large pastes) is the UI's. Ported from the
// earlier Rust core (`paste.rs` of its `input` crate).

private val START = "\u001b[200~".encodeToByteArray()
private val END = "\u001b[201~".encodeToByteArray()

// The same markers with the 8-bit CSI, U+009B in UTF-8, which a program reading 8-bit controls
// would honour.
private val START_C1 = "\u009b200~".encodeToByteArray()
private val END_C1 = "\u009b201~".encodeToByteArray()

/** Every marker a bracketed body must not hold. */
internal val PASTE_MARKERS: List<ByteArray> = listOf(START, END, START_C1, END_C1)

private const val LF = '\n'.code.toByte()
private const val CR_BYTE = '\r'.code.toByte()

/**
 * Appends [text] as a paste. With [bracketed], the text is wrapped in the markers and stripped of
 * any marker inside it; without, line feeds become carriage returns, as typing Enter would send.
 */
public fun encodePaste(
    text: String,
    bracketed: Boolean,
    out: ByteSink,
) {
    val bytes = text.encodeToByteArray()
    if (!bracketed) {
        var afterCr = false
        for (b in bytes) {
            // CR LF is one line break, already a CR.
            if (!(b == LF && afterCr)) out.push((if (b == LF) CR_BYTE else b).toInt())
            afterCr = b == CR_BYTE
        }
        return
    }
    out.pushAll(START)
    val body = out.size
    for (b in bytes) {
        out.push(b.toInt())
        // Checking after every byte removes markers that only form once an inner marker is gone
        // (`ESC [ 20 ESC [ 201 ~ 1 ~`), in one pass.
        PASTE_MARKERS.firstOrNull { out.endsWith(body, it) }?.let { out.dropLast(it.size) }
    }
    out.pushAll(END)
}
