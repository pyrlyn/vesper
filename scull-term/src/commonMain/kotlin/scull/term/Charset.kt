package scull.term

// Character sets: the G0-G3 designations (SCS), the locking shifts that pick one for GL (SI, SO,
// LS2, LS3) and the single shifts (SS2, SS3). Separate because it is a small translation table
// with its own state that DECSC saves and restores whole.
//
// Only the sets programs still use are kept: ASCII, DEC Special Graphics (line drawing, VT100 User
// Guide table 3-9 on vt100.net) and the UK national set. Other final bytes are ignored, as xterm
// ignores sets it does not support.

/** The first code point DEC Special Graphics replaces. */
private const val DEC_SPECIAL_FIRST = '_'

/** DEC Special Graphics for `_` through `~`; xterm shows `_` as a blank. */
private const val DEC_SPECIAL = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

/** The only code point the UK set changes. */
private const val UK_POUND_SOURCE = '#'
private const val POUND = '£'

private const val ASCII_LAST = 0x7F

/** The SCS intermediates for G0 through G3, in slot order. */
private const val SLOT_INTERMEDIATES = "()*+"
private const val SLOTS = 4
private const val NO_SLOT = -1

/** One 94-character set. */
internal enum class Charset {
    Ascii,
    DecSpecial,
    Uk,
    ;

    fun map(codePoint: Int): Int =
        when (this) {
            Ascii -> codePoint
            DecSpecial -> DEC_SPECIAL.getOrNull(codePoint - DEC_SPECIAL_FIRST.code)?.code ?: codePoint
            Uk -> if (codePoint == UK_POUND_SOURCE.code) POUND.code else codePoint
        }

    companion object {
        /** The set an SCS final byte names. */
        fun fromFinal(final: Char): Charset? =
            when (final) {
                'B' -> Ascii
                '0' -> DecSpecial
                'A' -> Uk
                else -> null
            }
    }
}

/** The four designations and the shift state. */
internal class Charsets {
    private val slots = Array(SLOTS) { Charset.Ascii }

    /** The slot GL maps to. */
    private var gl = 0

    /** A slot for the next printed character only (SS2, SS3), or [NO_SLOT]. */
    private var single = NO_SLOT

    /** Whether the next ASCII character prints as itself, so a run of them needs no translation. */
    val isAscii: Boolean get() = single == NO_SLOT && slots[gl] == Charset.Ascii

    fun designate(
        slot: Int,
        set: Charset,
    ) {
        if (slot in slots.indices) slots[slot] = set
    }

    fun lockShift(slot: Int) {
        if (slot in slots.indices) gl = slot
    }

    fun singleShift(slot: Int) {
        if (slot in slots.indices) single = slot
    }

    /** Translates one printed character, using up a pending single shift. */
    fun map(codePoint: Int): Int {
        val slot = if (single == NO_SLOT) gl else single
        single = NO_SLOT
        return if (codePoint > ASCII_LAST) codePoint else slots[slot].map(codePoint)
    }

    fun reset() {
        slots.fill(Charset.Ascii)
        gl = 0
        single = NO_SLOT
    }

    /** Takes the designations and shifts of [other], for DECRC. */
    fun assign(other: Charsets) {
        other.slots.copyInto(slots)
        gl = other.gl
        single = other.single
    }

    /** A snapshot, for DECSC. */
    fun copy(): Charsets = Charsets().also { it.assign(this) }

    /** For tests: the same designations and shifts. */
    fun sameAs(other: Charsets): Boolean = slots.contentEquals(other.slots) && gl == other.gl && single == other.single

    companion object {
        /** The slot an SCS intermediate designates, or -1. */
        fun slotFor(intermediate: Char): Int = SLOT_INTERMEDIATES.indexOf(intermediate)
    }
}
