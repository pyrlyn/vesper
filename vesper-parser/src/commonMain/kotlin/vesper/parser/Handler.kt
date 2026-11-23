package vesper.parser

/**
 * Intermediate bytes (`0x20..0x2F`) of an escape or control sequence, at most
 * [MAX_INTERMEDIATES]. Reused by the parser: read it only during the handler call.
 */
public class Intermediates internal constructor() {
    private val bytes = IntArray(MAX_INTERMEDIATES)

    public var size: Int = 0
        private set

    public operator fun get(index: Int): Int {
        if (index !in 0 until size) throw IndexOutOfBoundsException("intermediate $index of $size")
        return bytes[index]
    }

    /** Adds [byte]; false when the cap is reached and the byte was dropped. */
    internal fun add(byte: Int): Boolean {
        if (size == MAX_INTERMEDIATES) return false
        bytes[size++] = byte
        return true
    }

    internal fun clear() {
        size = 0
    }
}

/**
 * An escape sequence: `ESC`, intermediates, final byte (`0x30..0x7E`). The seven-bit form of a C1
 * control arrives here too: U+0085 (NEL) as `ESC E`, the way ECMA-48 defines their equivalence.
 */
public class Esc internal constructor(
    public val intermediates: Intermediates,
) {
    public var finalByte: Int = 0
        internal set
}

/** A control sequence (CSI), or the header of a device control string (DCS), which has the same shape. */
public class Csi internal constructor(
    /** Numeric parameters, colon sub-parameters grouped with their parameter. */
    public val params: Params,
    public val intermediates: Intermediates,
) {
    /** A leading `<`, `=`, `>` or `?` that marks a private sequence, or 0. */
    public var private: Int = 0
        internal set

    /** The byte that ended the sequence, `0x40..0x7E`. */
    public var finalByte: Int = 0
        internal set

    /** More parameters arrived than [MAX_PARAMS]; the excess was dropped. */
    public val truncated: Boolean get() = params.truncated
}

/**
 * An operating system command (OSC) payload, terminated by BEL or ST. C0 controls inside the
 * payload are dropped before it is stored, so a title or a link can never carry one to the UI.
 * Reused by the parser: read it only during the handler call.
 */
public class Osc internal constructor() {
    private var data = ByteArray(0)

    public var size: Int = 0
        private set

    /** Ended by BEL rather than ST; a reply should use the same terminator. */
    public var bellTerminated: Boolean = false
        internal set

    public operator fun get(index: Int): Byte {
        if (index !in 0 until size) throw IndexOutOfBoundsException("OSC byte $index of $size")
        return data[index]
    }

    /** A copy of everything between `ESC ]` and the terminator. */
    public fun toByteArray(): ByteArray = data.copyOf(size)

    /** The payload split on `;`, so the parser keeps no index table and needs no field cap. Allocates. */
    public fun fields(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in 0..size) {
            if (i == size || data[i] == SEMICOLON) {
                out += data.copyOfRange(start, i)
                start = i + 1
            }
        }
        return out
    }

    internal val capacity: Int get() = data.size

    /** Empties the payload; a buffer one large image grew is not kept for the next title. */
    internal fun reset() {
        size = 0
        if (data.size > OSC_KEPT_BYTES) data = ByteArray(OSC_KEPT_BYTES)
    }

    internal fun append(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        val needed = size + (end - start)
        if (needed > data.size) {
            var grown = maxOf(data.size * 2, MIN_OSC_CAPACITY)
            while (grown < needed) grown *= 2
            data = data.copyOf(minOf(grown, MAX_OSC_BYTES))
        }
        bytes.copyInto(data, size, start, end)
        size = needed
    }

    private companion object {
        const val SEMICOLON = ';'.code.toByte()
        const val MIN_OSC_CAPACITY = 256
    }
}

/** How a streamed string (DCS or APC) ended. */
public enum class End {
    /** Terminated by ST; every byte was delivered. */
    Complete,

    /** Terminated by ST, but bytes past the cap were dropped. */
    Truncated,

    /** Aborted by CAN, SUB or a new escape; the payload must be discarded. */
    Cancelled,
}

/**
 * Receives the parser's actions. Every method defaults to doing nothing, so a consumer implements
 * only what it understands. Byte ranges are `[start, end)` of an array the parser lends for the
 * call: the input itself where possible, so delivering an action copies nothing. Keep a copy, never
 * the array.
 */
public interface Handler {
    /** A run of printable text, always valid UTF-8: invalid input arrives as U+FFFD. */
    public fun print(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {}

    /** A C0 control. DEL is ignored and never arrives here. */
    public fun execute(byte: Int) {}

    public fun escDispatch(esc: Esc) {}

    public fun csiDispatch(csi: Csi) {}

    public fun oscDispatch(osc: Osc) {}

    /** A device control string begins; its payload follows through [dcsPut]. */
    public fun dcsHook(header: Csi) {}

    public fun dcsPut(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {}

    public fun dcsUnhook(end: End) {}

    /** An application program command (APC) begins, for kitty graphics. */
    public fun apcStart() {}

    public fun apcPut(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {}

    public fun apcEnd(end: End) {}
}
