package scull.input

// Byte-level building blocks every encoder shares, so a sequence is spelled one way. Ported from
// the creator's Rust scull (`crates/scull-input/src/bytes.rs`).

/** ESC: starts every 7-bit sequence and is xterm's Alt prefix. */
internal const val ESC = 0x1B

/** Programs expect 7-bit controls from the keyboard, so CSI and SS3 go out as ESC plus a byte. */
private const val CSI_SECOND = '['.code
private const val SS3_SECOND = 'O'.code

internal const val SEMI = ';'.code
internal const val COLON = ':'.code

private const val DECIMAL_RADIX = 10
private const val MAX_INT_DIGITS = 10
private const val DEFAULT_CAPACITY = 32

private const val UTF8_ONE_BYTE_LIMIT = 0x80
private const val UTF8_TWO_BYTE_LIMIT = 0x800
private const val UTF8_THREE_BYTE_LIMIT = 0x10000
private const val UTF8_CONTINUATION = 0x80
private const val UTF8_TWO_BYTE_LEAD = 0xC0
private const val UTF8_THREE_BYTE_LEAD = 0xE0
private const val UTF8_FOUR_BYTE_LEAD = 0xF0
private const val SIX_BITS = 0x3F
private const val SHIFT_6 = 6
private const val SHIFT_12 = 12
private const val SHIFT_18 = 18

/**
 * The bytes an encoder produced. Encoders append, so a host that keeps one sink and calls [clear]
 * between events encodes a steady stream of keys without allocating.
 */
public class ByteSink(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private var bytes = ByteArray(capacity.coerceAtLeast(1))

    /** Bytes appended since the last [clear]. */
    public var size: Int = 0
        private set

    public fun isEmpty(): Boolean = size == 0

    public fun clear() {
        size = 0
    }

    public fun toByteArray(): ByteArray = bytes.copyOf(size)

    internal fun push(byte: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
        bytes[size++] = byte.toByte()
    }

    internal fun pushAll(bytes: ByteArray) {
        for (b in bytes) push(b.toInt())
    }

    /** Whether the bytes from [from] on end with [suffix]. */
    internal fun endsWith(
        from: Int,
        suffix: ByteArray,
    ): Boolean {
        val start = size - suffix.size
        if (start < from) return false
        for (i in suffix.indices) if (bytes[start + i] != suffix[i]) return false
        return true
    }

    /** Drops the last [count] bytes. */
    internal fun dropLast(count: Int) {
        size -= count
    }

    internal fun csi() {
        push(ESC)
        push(CSI_SECOND)
    }

    internal fun ss3() {
        push(ESC)
        push(SS3_SECOND)
    }

    /** [n] in decimal; only non-negative numbers reach the wire. */
    internal fun decimal(n: Int) {
        require(n >= 0) { "negative number $n" }
        val digits = IntArray(MAX_INT_DIGITS)
        var count = 0
        var rest = n
        do {
            digits[count++] = rest % DECIMAL_RADIX
            rest /= DECIMAL_RADIX
        } while (rest > 0)
        while (count > 0) push('0'.code + digits[--count])
    }

    /** [codePoint] as UTF-8. */
    internal fun codePoint(codePoint: Int) {
        when {
            codePoint < UTF8_ONE_BYTE_LIMIT -> {
                push(codePoint)
            }

            codePoint < UTF8_TWO_BYTE_LIMIT -> {
                push(UTF8_TWO_BYTE_LEAD or (codePoint shr SHIFT_6))
                push(UTF8_CONTINUATION or (codePoint and SIX_BITS))
            }

            codePoint < UTF8_THREE_BYTE_LIMIT -> {
                push(UTF8_THREE_BYTE_LEAD or (codePoint shr SHIFT_12))
                push(UTF8_CONTINUATION or ((codePoint shr SHIFT_6) and SIX_BITS))
                push(UTF8_CONTINUATION or (codePoint and SIX_BITS))
            }

            else -> {
                push(UTF8_FOUR_BYTE_LEAD or (codePoint shr SHIFT_18))
                push(UTF8_CONTINUATION or ((codePoint shr SHIFT_12) and SIX_BITS))
                push(UTF8_CONTINUATION or ((codePoint shr SHIFT_6) and SIX_BITS))
                push(UTF8_CONTINUATION or (codePoint and SIX_BITS))
            }
        }
    }
}

private const val REPLACEMENT = 0xFFFD
internal const val HIGH_SURROGATE_FIRST = 0xD800
internal const val LOW_SURROGATE_FIRST = 0xDC00
internal const val SURROGATE_BITS = 10
internal const val SUPPLEMENTARY_FIRST = 0x10000
private const val C0_END = 0x20
private const val C1_END = 0x9F

/** Whether [codePoint] is a C0 or C1 control or DEL: Unicode's `Cc`. */
internal fun isControl(codePoint: Int): Boolean = codePoint < C0_END || codePoint in DEL..C1_END

/** Each code point of [text]; a lone surrogate reads as U+FFFD, as UTF-8 output would write it. */
internal inline fun forEachCodePoint(
    text: String,
    action: (Int) -> Unit,
) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            action(((c.code - HIGH_SURROGATE_FIRST) shl SURROGATE_BITS) + (text[i + 1].code - LOW_SURROGATE_FIRST) + SUPPLEMENTARY_FIRST)
            i += 2
        } else {
            action(if (c.isSurrogate()) REPLACEMENT else c.code)
            i++
        }
    }
}

/**
 * Appends text an input method committed, without control characters: committed text is not a
 * key, so it must not type an escape sequence or a line break the user never pressed.
 */
public fun encodeText(
    text: String,
    out: ByteSink,
) {
    forEachCodePoint(text) { if (!isControl(it)) out.codePoint(it) }
}
