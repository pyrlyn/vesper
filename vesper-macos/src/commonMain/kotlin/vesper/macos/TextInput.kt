package vesper.macos

import vesper.term.PreeditClause
import vesper.term.PreeditKind
import vesper.term.TextRange

// The input method's side of the terminal view: the marked (composing) text, its selection, and
// what a key event turned into. Kept apart from the view so tests drive the NSTextInputClient logic
// without a window or a real input method. Ported from the removed Swift host's `TextInput`.
//
// The terminal shows the input system no document of its own: only the marked text exists, so its
// ranges start at 0. What the child already printed is not editable text.

/** What a key event becomes once the input method has seen it. */
internal sealed interface KeyOutcome {
    /** The key is the core's to encode. */
    data object Key : KeyOutcome

    /** An input method composed [text] (a dead key, a CJK commit); it goes to the child as text, not as the key that finished it. */
    data class Text(
        val text: String,
    ) : KeyOutcome

    /** The input method took the key to edit its composition. */
    data object Consumed : KeyOutcome
}

/** A stretch of the marked text from UTF-16 offset [start] until [end], with what the input method said about it. */
internal data class MarkedRun(
    val start: Int,
    val end: Int,
    /** A thick underline: the clause being converted. */
    val thick: Boolean,
    /** Tagged as a clause segment. */
    val tagged: Boolean,
)

internal class TextInput {
    var marked: String = ""
        private set
    private var runs: List<MarkedRun> = emptyList()

    /** The input method's selection inside the marked text. */
    private var selection = TextRange(0, 0)

    /** Text inserted during the key event being interpreted; null outside one, when insertions go straight to the child. */
    private var typed: StringBuilder? = null
    private var composedBefore = false

    val hasMarkedText: Boolean get() = marked.isNotEmpty()

    /** The marked text's range; null (NSNotFound) when there is none. */
    val markedRange: TextRange? get() = if (hasMarkedText) TextRange(0, marked.length) else null

    // An empty document still has an insertion point; input methods anchor to it.
    val selectedRange: TextRange get() = if (hasMarkedText) selection else TextRange(0, 0)

    fun beginKey() {
        typed = StringBuilder()
        composedBefore = hasMarkedText
    }

    fun endKey(): KeyOutcome {
        val text = typed?.toString().orEmpty()
        typed = null
        if (!composedBefore && !hasMarkedText) return KeyOutcome.Key
        return if (text.isEmpty()) KeyOutcome.Consumed else KeyOutcome.Text(text)
    }

    /** Commits [text] and ends the composition. Answers the text when it must be sent now; inside a key event it is kept for [endKey]. */
    fun insert(text: String): String? {
        marked = ""
        runs = emptyList()
        selection = TextRange(0, 0)
        val inKey = typed ?: return text
        inKey.append(text)
        return null
    }

    /** Sets the composition; [start] null (NSNotFound) puts the caret at its end. Everything is cut to the text. */
    fun setMarked(
        text: String,
        start: Int?,
        length: Int,
        runs: List<MarkedRun> = emptyList(),
    ) {
        marked = text
        this.runs = runs
        val at = start?.coerceIn(0, text.length) ?: text.length
        selection = TextRange(at, at + length.coerceIn(0, text.length - at))
    }

    /** The part of the marked text in [range], or null when they do not meet. */
    fun substring(range: TextRange): TextRange? {
        val start = maxOf(range.start, 0)
        val end = minOf(range.end, marked.length)
        return if (start < end) TextRange(start, end) else null
    }

    /** The caret for the frame: the start of the input method's selection. */
    val caret: Int get() = selection.start

    /**
     * What the input method says about each stretch of the marked text. A Japanese input method
     * gives the clause being converted a thick underline and tags every clause as a segment; the
     * target is the thick one, the other tagged clauses are converted, and while nothing is thick
     * nothing is being converted, so the text is still input. An input method that marks nothing
     * gets no clauses, and the core shows its text as one plain stretch.
     */
    val clauses: List<PreeditClause> get() {
        if (runs.none { it.thick || it.tagged }) return emptyList()
        val converting = runs.any { it.thick }
        return runs.map { run ->
            val kind =
                when {
                    run.thick -> PreeditKind.Target
                    run.tagged && converting -> PreeditKind.Converted
                    else -> PreeditKind.Input
                }
            PreeditClause(run.start, run.end, kind)
        }
    }
}
