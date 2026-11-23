package scull.pty

// The three threads that sit in a blocking call on the PTY: the reader, the writer and the child
// waiter. They hold no terminal state and take no sink lock, so a stalled sink never stops them
// draining the kernel buffer up to the cap, and the loop never blocks on the child.

/** The child's output, read by one thread. */
internal interface ByteSource {
    /** Reads into [buffer]; the count, or 0 or less at end of output or on an error. */
    fun read(buffer: ByteArray): Int

    fun close()
}

/** The child's input, written by one thread. */
internal interface ByteOutput {
    /** Writes all of [bytes]; false when the child no longer accepts input. */
    fun write(bytes: ByteArray): Boolean

    fun close()
}

/**
 * Reads the child's output into the bounded queue until EOF, a read error or stop. An error counts
 * as EOF: Linux reports a hung-up PTY as `EIO`.
 */
internal fun readLoop(
    shared: Shared,
    source: ByteSource,
) {
    val buffer = ByteArray(PtyLimits.READ_CHUNK_BYTES)
    while (true) {
        val count = source.read(buffer)
        if (count <= 0) break
        // pushChunk blocks while the queue is full: this is where a flooding child is slowed down.
        if (!shared.pushChunk(buffer.copyOf(count))) return
    }
    shared.readerDone()
}

/** Writes queued replies and input to the child, one bounded chunk at a time. A failed write drops the queue. */
internal fun writeLoop(
    shared: Shared,
    output: ByteOutput,
) {
    while (true) {
        val chunk = shared.nextOutput() ?: return
        if (!output.write(chunk)) {
            shared.writerFailed()
            return
        }
        shared.outputWritten(chunk.size)
    }
}

/**
 * Waits for the child and hands its status to the loop, which delivers it after the output: the
 * status goes through the loop, not around it, so exit and output stay ordered.
 */
internal fun waitLoop(
    shared: Shared,
    wait: () -> ExitStatus,
) {
    shared.setExit(wait())
}
