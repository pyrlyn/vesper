package vesper.pty

/** A byte queue of fixed [capacity]: the bytes waiting to be written to the child. */
internal class ByteRing(
    val capacity: Int,
) {
    // Allocated on first use: most terminals never queue more than a few keys.
    private var bytes = ByteArray(0)
    private var head = 0

    var size = 0
        private set

    val room: Int get() = capacity - size

    /** Appends [count] bytes of [source] from [from]; the caller has checked [room]. */
    fun push(
        source: ByteArray,
        from: Int,
        count: Int,
    ) {
        require(count in 0..room) { "$count bytes into $room of room" }
        if (bytes.isEmpty() && count > 0) bytes = ByteArray(capacity)
        var copied = 0
        while (copied < count) {
            val at = (head + size) % capacity
            val run = minOf(count - copied, capacity - at)
            source.copyInto(bytes, at, from + copied, from + copied + run)
            copied += run
            size += run
        }
    }

    /** A copy of the first [count] bytes, which stay queued. */
    fun peek(count: Int): ByteArray {
        val n = count.coerceIn(0, size)
        val out = ByteArray(n)
        var copied = 0
        while (copied < n) {
            val at = (head + copied) % capacity
            val run = minOf(n - copied, capacity - at)
            bytes.copyInto(out, copied, at, at + run)
            copied += run
        }
        return out
    }

    /** Removes the first [count] bytes. */
    fun drop(count: Int) {
        val n = count.coerceIn(0, size)
        head = (head + n) % capacity
        size -= n
    }

    fun clear() {
        head = 0
        size = 0
    }
}
