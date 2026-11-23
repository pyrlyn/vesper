package vesper.grid

/**
 * Why the grid refused to intern something. Every case is recoverable: the terminal layer degrades
 * (default style, a replacement character) instead of failing. A column outside a row is a caller
 * bug and throws [IndexOutOfBoundsException] instead.
 */
public sealed class GridException(
    message: String,
) : RuntimeException(message) {
    /** The style table holds its cap of live styles even after a sweep. */
    public class StyleTableFull(
        public val cap: Int,
    ) : GridException("style table full ($cap live styles)")

    /** The cluster table holds its cap of live clusters even after a sweep. */
    public class ClusterTableFull(
        public val cap: Int,
    ) : GridException("cluster table full ($cap live clusters)")

    /** A grapheme cluster longer than one cell may hold. */
    public class ClusterTooLong(
        public val length: Int,
        public val max: Int,
    ) : GridException("cluster of $length code points exceeds the cap of $max")

    /** A cluster of fewer than two code points; one code point lives inline in the cell. */
    public class ClusterTooShort : GridException("a cluster needs at least two code points")
}
