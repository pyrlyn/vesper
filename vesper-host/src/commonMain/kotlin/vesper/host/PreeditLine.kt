package vesper.host

import vesper.term.FramePreedit
import vesper.term.PreeditKind

/** An underline under [cols] preedit columns from [col]; [thick] under the clause being converted. */
public data class PreeditLine(
    public val col: Int,
    public val cols: Int,
    public val thick: Boolean,
)

/** The underlines a shown preedit gets: one per clause, or one plain line when the input method marked none. */
public fun preeditLines(preedit: FramePreedit): List<PreeditLine> =
    if (preedit.spans.isEmpty()) {
        listOf(PreeditLine(preedit.col, preedit.cols, false))
    } else {
        preedit.spans.map { PreeditLine(it.col, it.cols, it.kind == PreeditKind.Target) }
    }
