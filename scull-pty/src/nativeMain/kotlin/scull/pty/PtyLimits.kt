package scull.pty

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every cap and timeout of the PTY layer. The child is untrusted: anything it can make us hold, or
 * make us wait for, is bounded here, and the tests name these values rather than repeating numbers.
 */
public object PtyLimits {
    /**
     * Most bytes fed to the sink in one lock hold. The bound of Alacritty's `MAX_LOCKED_READ`
     * (`u16::MAX`) and of Warp: long enough to amortise the lock, short enough that a frame read
     * waits for at most one parse of this size.
     */
    public const val MAX_LOCKED_BYTES: Int = 64 * 1024

    /** Size of one read from the PTY. Never above [MAX_LOCKED_BYTES], so no chunk has to be split. */
    public const val READ_CHUNK_BYTES: Int = MAX_LOCKED_BYTES

    /**
     * Most unprocessed output held between the reader and the sink. While the lock is busy the
     * reader buffers up to this, then blocks: the back-pressure that ends at the child's `write`.
     */
    public const val MAX_PENDING_READ_BYTES: Int = 1024 * 1024

    /**
     * Most bytes waiting to be written to the child: replies and input together. Input beyond it
     * is refused; replies beyond it are dropped.
     */
    public const val MAX_PENDING_WRITE_BYTES: Int = 256 * 1024

    /** Size of one write to the child. A PTY buffer is a few KiB; a larger write only blocks longer. */
    public const val WRITE_CHUNK_BYTES: Int = 4 * 1024

    /**
     * How long after the child exits output keeps being delivered before the exit is reported with
     * no more arriving. EOF normally comes at once; ConPTY, and an orphaned grandchild holding the
     * PTY open, never send it.
     */
    public val EXIT_IDLE: Duration = 100.milliseconds

    /** Hard end of that drain, so a flooding grandchild cannot delay the exit report forever. */
    public val EXIT_DRAIN_MAX: Duration = 1.seconds

    /** How long [Pty.close] waits for the threads before leaving the stuck ones to end by themselves. */
    public val SHUTDOWN_GRACE: Duration = 500.milliseconds

    /** Exit code reported when the system cannot tell how the child ended. */
    public const val UNKNOWN_EXIT_CODE: UInt = UInt.MAX_VALUE
}
