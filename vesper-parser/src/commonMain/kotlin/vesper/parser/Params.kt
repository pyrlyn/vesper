package vesper.parser

/**
 * Most parameter values one sequence keeps, sub-parameters included. `38:2::255:255:255` is six
 * values and xterm stops at 30 parameters; 32 fits both with room and fills an `Int`
 * sub-parameter mask exactly.
 */
public const val MAX_PARAMS: Int = 32

/** Values saturate here: far beyond any row, column or mode number a terminal acts on. */
public const val MAX_PARAM_VALUE: Int = 0xFFFF

private const val DECIMAL_BASE = 10

/**
 * Numeric parameters of a CSI or DCS header, with colon sub-parameters. Kept in one fixed-size
 * array because it is the one piece of sequence state a hostile stream can try to grow.
 *
 * The parser reuses one instance; read it only during the handler call that received it.
 */
public class Params internal constructor() {
    private val values = IntArray(MAX_PARAMS)

    // Bit `i` set: value `i` followed a `:` and belongs to the parameter before it.
    private var sub = 0

    /** Number of values, sub-parameters included. */
    public var size: Int = 0
        private set

    // A value slot is open and still taking digits.
    private var open = false

    // The next value to open follows a `:`.
    private var nextIsSub = false

    // A separator was seen, so an empty trailing parameter counts.
    private var separated = false

    /** More values arrived than [MAX_PARAMS]; the excess was dropped. */
    public var truncated: Boolean = false
        private set

    /** No parameters at all, as in `CSI m`. */
    public fun isEmpty(): Boolean = size == 0

    /** Value [index]; an empty parameter reads as `0`, which every consumer treats as its default. */
    public operator fun get(index: Int): Int {
        if (index !in 0 until size) throw IndexOutOfBoundsException("parameter $index of $size")
        return values[index]
    }

    /** Whether value [index] followed a `:` and belongs to the parameter before it. */
    public fun isSubParameter(index: Int): Boolean = index in 0 until size && (sub ushr index) and 1 != 0

    /** Each parameter with its sub-parameters: `1;38:5:9` gives `[1]`, `[38, 5, 9]`. Allocates. */
    public fun groups(): List<IntArray> {
        val out = ArrayList<IntArray>()
        var start = 0
        while (start < size) {
            var end = start + 1
            while (end < size && isSubParameter(end)) end++
            out += values.copyOfRange(start, end)
            start = end
        }
        return out
    }

    internal fun clear() {
        sub = 0
        size = 0
        open = false
        nextIsSub = false
        separated = false
        truncated = false
    }

    /** Appends the ASCII digit [byte] to the open value, opening one if needed. */
    internal fun digit(byte: Int) {
        if (!open && !startValue()) return
        val value = values[size - 1] * DECIMAL_BASE + (byte - '0'.code)
        values[size - 1] = if (value > MAX_PARAM_VALUE) MAX_PARAM_VALUE else value
    }

    /** `;` starts a new parameter, `:` a sub-parameter of the current one. */
    internal fun separator(colon: Boolean) {
        if (!open) startValue()
        open = false
        separated = true
        nextIsSub = colon
    }

    /** Closes the sequence: a trailing separator leaves one empty parameter. */
    internal fun finish() {
        if (!open && separated) startValue()
        open = false
    }

    private fun startValue(): Boolean {
        if (size == MAX_PARAMS) {
            truncated = true
            return false
        }
        values[size] = 0
        if (nextIsSub) sub = sub or (1 shl size)
        size++
        open = true
        return true
    }
}
