package vesper.harness

/** Columns of the T3 baseline's terminal, and the period of the line feed in its input. */
public const val BASELINE_COLUMNS: Int = 80

/** Length of the T3 baseline's input. */
public const val BASELINE_BYTES: Int = 1_000_000

private const val ALPHABET = 26

/**
 * The T3 baseline's input: [BASELINE_BYTES] of a repeating `A`..`Z` pattern with a line feed closing
 * every [BASELINE_COLUMNS] bytes. Generated, so the repository does not store it; the bench's test
 * pins its FNV-1a hash, and every later measurement feeds the same bytes.
 */
public fun baselineInput(): ByteArray {
    var letter = 0
    return ByteArray(BASELINE_BYTES) { i ->
        if ((i + 1) % BASELINE_COLUMNS == 0) '\n'.code.toByte() else ('A'.code + letter++ % ALPHABET).toByte()
    }
}

/** [value] with exactly [digits] decimals, for benchmark tables; Kotlin/Native has no `String.format`. */
public fun fixed(
    value: Double,
    digits: Int,
): String {
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val scaled = kotlin.math.round(value * scale).toLong()
    val whole = scaled / scale
    val fraction = (scaled % scale).toString().padStart(digits, '0')
    return if (digits == 0) "$whole" else "$whole.$fraction"
}
