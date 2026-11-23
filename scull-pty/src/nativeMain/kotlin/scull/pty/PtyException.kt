package scull.pty

/** Why a PTY operation failed. */
public sealed class PtyException(
    message: String,
) : Exception(message) {
    /** The system refused to create a pseudo-terminal. */
    public class Open(
        reason: String,
    ) : PtyException("cannot open a pseudo-terminal: $reason")

    /** The child could not be started: a missing program, a bad directory, a bad argument. */
    public class Spawn(
        reason: String,
    ) : PtyException("cannot start the child process: $reason")

    /** A second handle on the pseudo-terminal could not be taken for the reader or the writer. */
    public class Attach(
        reason: String,
    ) : PtyException("cannot attach to the pseudo-terminal: $reason")

    /** The window size could not be applied. */
    public class Resize(
        reason: String,
    ) : PtyException("cannot resize the pseudo-terminal: $reason")

    /** A worker thread could not be started. */
    public class Thread(
        name: String,
        code: Int,
    ) : PtyException("cannot start the $name thread: system error $code")

    /** The terminal has shut down: the child is gone or the handle was closed. */
    public class Closed : PtyException("the pseudo-terminal is closed")
}
