package scull.parser

// UTF-8 validation for the ground state. Kept apart from the state machine because the decoding
// rules (RFC 3629 and Unicode §3.9 table 3-7) are the part most worth testing on their own.

/** Lead byte of the two-byte forms of U+0080..U+00BF, which include the C1 controls. */
internal const val C1_LEAD = 0xC2

/** Continuation bytes that, after [C1_LEAD], encode a C1 control U+0080..U+009F. */
internal const val C1_FIRST = 0x80
internal const val C1_LAST = 0x9F

internal const val MAX_UTF8_LEN = 4

/** U+FFFD in UTF-8, delivered for every maximal invalid subpart, as Rust and WHATWG decoders do. */
internal val REPLACEMENT = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

private const val CONTINUATION_FIRST = 0x80
private const val CONTINUATION_LAST = 0xBF

/** Length of the UTF-8 sequence [lead] starts; 0 if it cannot start one. */
internal fun utf8Width(lead: Int): Int =
    when (lead) {
        in 0x00..0x7F -> 1
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

// The second byte is narrower after these leads, which rules out overlong forms, surrogates and
// code points past U+10FFFF.
private fun secondFirst(lead: Int): Int =
    when (lead) {
        0xE0 -> 0xA0
        0xF0 -> 0x90
        else -> CONTINUATION_FIRST
    }

private fun secondLast(lead: Int): Int =
    when (lead) {
        0xED -> 0x9F
        0xF4 -> 0x8F
        else -> CONTINUATION_LAST
    }

/**
 * Checks the sequence that starts at `bytes[at]`, reading no further than [end]. Returns its
 * length when it is complete and valid; `-k` when its first `k` bytes are the maximal invalid
 * subpart; `INCOMPLETE + k` when its `k` bytes before [end] are a valid prefix.
 */
internal fun utf8Check(
    bytes: ByteArray,
    at: Int,
    end: Int,
): Int {
    val lead = bytes[at].toInt() and BYTE_MASK
    val width = utf8Width(lead)
    if (width == 0) return -1
    for (k in 1 until width) {
        if (at + k >= end) return INCOMPLETE + k
        val byte = bytes[at + k].toInt() and BYTE_MASK
        val first = if (k == 1) secondFirst(lead) else CONTINUATION_FIRST
        val last = if (k == 1) secondLast(lead) else CONTINUATION_LAST
        if (byte < first || byte > last) return -k
    }
    return width
}

internal const val INCOMPLETE = 0x100
internal const val BYTE_MASK = 0xFF

internal const val TWO_BYTE_LAST = 0xDF

// E0 and ED narrow their second byte (overlong forms, surrogates), so the fast path leaves them out.
internal const val THREE_BYTE_FIRST = 0xE1
internal const val THREE_BYTE_LAST = 0xEF
internal const val SURROGATE_LEAD = 0xED
internal const val THREE_BYTES = 3

@Suppress("NOTHING_TO_INLINE")
internal inline fun isContinuation(byte: Byte): Boolean = byte < -0x40 // 0x80..0xBF as a signed byte
