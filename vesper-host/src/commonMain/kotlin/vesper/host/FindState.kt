package vesper.host

import vesper.term.MAX_SEARCH_MATCHES

// Find in the scrollback: a state machine that says what to ask of the core. The find bar reports
// what the user did and the view runs the effects. Ported from the removed Swift host's `FindState`.

/** A core call the find state asks for. */
public sealed interface FindEffect {
    /** Replace the search; an empty pattern ends it. */
    public data class Search(
        public val pattern: String,
        public val matchCase: Boolean,
    ) : FindEffect

    /** Step to the next match ([forward]) or the previous one. */
    public data class Step(
        public val forward: Boolean,
    ) : FindEffect

    /** Count the matches. */
    public data object Count : FindEffect
}

public class FindState {
    public var isOpen: Boolean = false
        private set

    /** Kept across a close, so the bar reopens on the last search. */
    public var pattern: String = ""
        private set

    public var matchCase: Boolean = false
        private set

    /** Counted once the pattern settles; null until then. */
    public var count: Int? = null

    /** A step and a count both rescan the whole history, so typing waits for the pattern to settle before either runs. */
    public var unsettled: Boolean = false
        private set

    public val canStep: Boolean get() = isOpen && pattern.isNotEmpty()

    public fun open(): List<FindEffect> {
        if (isOpen) return emptyList()
        isOpen = true
        return if (pattern.isEmpty()) emptyList() else edit(pattern)
    }

    public fun edit(text: String): List<FindEffect> {
        if (!isOpen) return emptyList()
        pattern = text
        count = null
        unsettled = text.isNotEmpty()
        return listOf(FindEffect.Search(text, matchCase))
    }

    public fun toggleCase(): List<FindEffect> {
        matchCase = !matchCase
        return edit(pattern)
    }

    /** The pattern stopped changing: show the first match and count. */
    public fun settle(): List<FindEffect> {
        if (!unsettled || !canStep) return emptyList()
        unsettled = false
        return listOf(FindEffect.Step(true), FindEffect.Count)
    }

    public fun step(forward: Boolean): List<FindEffect> {
        if (!canStep) return emptyList()
        // Stepping now stands in for the settle's own first step.
        unsettled = false
        return if (count == null) listOf(FindEffect.Step(forward), FindEffect.Count) else listOf(FindEffect.Step(forward))
    }

    public fun close(): List<FindEffect> {
        if (!isOpen) return emptyList()
        isOpen = false
        count = null
        unsettled = false
        return listOf(FindEffect.Search("", matchCase))
    }

    public val label: String get() =
        when (val n = count) {
            null -> ""
            0 -> "No matches"
            1 -> "1 match"
            else -> if (n >= MAX_SEARCH_MATCHES) "$n+ matches" else "$n matches"
        }
}
