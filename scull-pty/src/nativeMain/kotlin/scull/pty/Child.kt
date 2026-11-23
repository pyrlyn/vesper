package scull.pty

/** One child on one pseudo-terminal, as the system gives it. The only platform-specific part. */
internal interface Child {
    /** A handle for the reader thread, which closes it. */
    fun openReader(): ByteSource

    /** A handle for the writer thread, which closes it. */
    fun openWriter(): ByteOutput

    /** Blocks until the child ends. */
    fun waitExit(): ExitStatus

    fun resize(size: PtySize)

    /** Asks the child to end; false when it already has or the system refused. */
    fun hangUp(): Boolean

    /** Closes our end of the pseudo-terminal; on Windows that is what ends the reader's wait. */
    fun closeMaster()
}

/** Opens a pseudo-terminal and starts the child on it. */
internal expect fun spawnChild(options: SpawnOptions): Child
