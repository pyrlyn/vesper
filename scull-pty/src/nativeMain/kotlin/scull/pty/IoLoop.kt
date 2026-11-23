package scull.pty

/**
 * Drives [sink] until the child has exited and its output is delivered, or the terminal stops.
 * The only place child output meets terminal state: no call that can block on the child runs
 * while [lock] is held, a hold feeds at most [PtyLimits.MAX_LOCKED_BYTES], [FairLock] hands the
 * lock to a waiting frame read, and [wakeup] runs after the lock is released.
 */
internal fun runIoLoop(
    shared: Shared,
    lock: FairLock,
    sink: Sink,
    wakeup: () -> Unit,
) {
    var deadline: Instant? = null
    while (true) {
        val next =
            when (val work = shared.waitForWork(deadline)) {
                Work.Stop -> {
                    return
                }

                // Wait for the lock first, take the output after: whatever the reader queued while a
                // frame read held the lock goes in one hold (up to the budget) instead of one hold
                // per read. The wait never stops the reader, which keeps filling the queue up to its
                // cap, so this is the buffering the back-pressure needs.
                Work.Data -> {
                    hold(shared, lock, sink, wakeup, feed = true)
                }

                Work.Tick -> {
                    hold(shared, lock, sink, wakeup, feed = false)
                }

                is Work.Exited -> {
                    lock.withLock {
                        // A terminal closed while this thread waited for the lock gets no more sink calls.
                        if (shared.isStopped) return
                        sink.childExited(work.status)
                    }
                    wakeup()
                    return
                }
            }
        deadline = (next ?: return).deadline
    }
}

/** What a hold leaves: when the sink wants to be asked again, if ever. */
private class Held(
    val deadline: Instant?,
)

/**
 * One lock hold: feeds a batch when [feed], collects the replies, asks the sink whether to wake the
 * UI and when to ask again, unlocks, then wakes. Null when the terminal stopped while the lock was
 * awaited.
 */
private fun hold(
    shared: Shared,
    lock: FairLock,
    sink: Sink,
    wakeup: () -> Unit,
    feed: Boolean,
): Held? {
    val wake: Boolean
    val held: Held
    lock.withLock {
        if (shared.isStopped) return null
        if (feed) {
            for (chunk in shared.takeBatch()) sink.feed(chunk)
            shared.queueReplies(sink)
        }
        wake = sink.wantsWakeup(now())
        held = Held(sink.deadline())
    }
    if (wake) wakeup()
    return held
}
