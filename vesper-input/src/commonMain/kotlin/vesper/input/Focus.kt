package vesper.input

// Focus reports (DECSET 1004): `CSI I` when the terminal gains focus and `CSI O` when it loses
// it. Separate because it belongs to no other input kind; the caller sends it only while the mode
// is set. https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h3-FocusIn_FocusOut

private const val FOCUS_IN = 'I'.code
private const val FOCUS_OUT = 'O'.code

/** Appends the report for a focus change. */
public fun encodeFocus(
    focused: Boolean,
    out: ByteSink,
) {
    out.csi()
    out.push(if (focused) FOCUS_IN else FOCUS_OUT)
}
