package scull.pty

import kotlin.time.TimeSource

/** How the child ended. */
public data class ExitStatus(
    /** The exit code; [PtyLimits.UNKNOWN_EXIT_CODE] when unknown, and 1 when a signal ended the child. */
    val code: UInt,
    /** The signal that ended the child, when one did (Unix only). */
    val signal: Int? = null,
) {
    /** True when the child exited with code 0. */
    val success: Boolean get() = code == 0u && signal == null

    public companion object {
        /** The system could not tell how the child ended. */
        public val UNKNOWN: ExitStatus = ExitStatus(PtyLimits.UNKNOWN_EXIT_CODE)
    }
}

/**
 * What the I/O thread drives: whatever owns the terminal state. Kept apart from it so this module
 * never sees a grid and the terminal never sees a PTY.
 *
 * Every method runs on the I/O thread with the caller's [FairLock] held, so none may block, call
 * into the UI or take another lock.
 */
public interface Sink {
    /**
     * Parses and applies child output. May be called several times per lock hold, with at most
     * [PtyLimits.MAX_LOCKED_BYTES] bytes in all. The split between calls is arbitrary, so a
     * sequence can be cut anywhere.
     */
    public fun feed(bytes: ByteArray)

    /**
     * The replies the child asked for (device reports and the like), at most [limit] bytes; the
     * sink keeps the rest queued under its own cap. Called once at the end of each lock hold.
     * Null or empty when there are none. Bytes past [limit] are dropped.
     */
    public fun drainReplies(limit: Int): ByteArray?

    /** The child ended and all its output has been fed. Called once, last. */
    public fun childExited(status: ExitStatus)

    /**
     * Whether the UI should be woken for what the sink holds now. Asked at the end of every hold and
     * once [deadline] passes; false skips the wakeup, as while synchronized output holds the picture.
     */
    public fun wantsWakeup(now: TimeSource.Monotonic.ValueTimeMark): Boolean = true

    /** When to ask [wantsWakeup] again although no output came: the end of a hold the sink keeps. */
    public fun deadline(): TimeSource.Monotonic.ValueTimeMark? = null
}
