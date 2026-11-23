package scull.pty

import scull.pty.PtyLimits.MAX_LOCKED_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Counts what each lock hold feeds; a hold ends where the loop asks [wantsWakeup]. */
private class HoldCounter(
    private val lock: FairLock,
) : Sink {
    var fed = 0L
    var thisHold = 0
    var worstHold = 0
    var feedsWithoutLock = 0
    val events = ArrayList<String>()

    override fun feed(bytes: ByteArray) {
        if (lock.tryLock()) {
            feedsWithoutLock++
            lock.unlock()
        }
        fed += bytes.size
        thisHold += bytes.size
        events += "feed"
    }

    override fun drainReplies(limit: Int): ByteArray? = null

    override fun childExited(status: ExitStatus) {
        events += "exit"
    }

    override fun wantsWakeup(now: Instant): Boolean {
        worstHold = maxOf(worstHold, thisHold)
        thisHold = 0
        return true
    }
}

class IoLoopTest {
    @Test
    fun everyHoldStaysWithinTheBudgetAndTheExitComesLast() {
        val shared = Shared()
        val lock = FairLock()
        val sink = HoldCounter(lock)
        val wakeups = Monitor(1)
        var woken = 0
        val loop = Background { runIoLoop(shared, lock, sink) { wakeups.locked { woken++ } } }
        val chunk = MAX_LOCKED_BYTES / 2 + 1
        repeat(20) { assertTrue(shared.pushChunk(ByteArray(chunk))) }
        shared.readerDone()
        shared.setExit(ExitStatus(0u))
        loop.join()
        assertEquals(20L * chunk, sink.fed)
        assertTrue(sink.worstHold <= MAX_LOCKED_BYTES, "a hold fed ${sink.worstHold} bytes")
        assertEquals(0, sink.feedsWithoutLock)
        assertEquals("exit", sink.events.last())
        assertEquals(1, sink.events.count { it == "exit" })
        assertTrue(wakeups.locked { woken } > 1)
    }

    @Test
    fun repliesReachTheWriteQueue() {
        val shared = Shared()
        val answer = "\u001b[?62c".encodeToByteArray()
        val sink =
            object : Sink {
                var pending: ByteArray? = answer

                override fun feed(bytes: ByteArray) = Unit

                override fun drainReplies(limit: Int): ByteArray? = pending.also { pending = null }

                override fun childExited(status: ExitStatus) = Unit
            }
        val loop = Background { runIoLoop(shared, FairLock(), sink) {} }
        shared.pushChunk("\u001b[c".encodeToByteArray())
        val written = Background { shared.nextOutput() }.join()
        assertContentEquals(answer, assertNotNull(written))
        shared.stop()
        loop.join()
    }

    @Test
    fun aThrowingSinkStopsTheTerminal() {
        val shared = Shared()
        val sink =
            object : Sink {
                override fun feed(bytes: ByteArray): Unit = throw IllegalStateException("broken sink")

                override fun drainReplies(limit: Int): ByteArray? = null

                override fun childExited(status: ExitStatus) = Unit
            }
        val loop = Background { shared.runWorker(Worker.LOOP) { runIoLoop(shared, FairLock(), sink) {} } }
        shared.pushChunk(byteArrayOf(1))
        loop.join()
        assertTrue(shared.isStopped)
        assertEquals("broken sink", shared.failure?.message)
        assertFalse(shared.pushChunk(byteArrayOf(2)))
    }

    @Test
    fun aHeldPictureWakesAtTheSinksDeadlineAndNotBefore() {
        val shared = Shared()
        val hold = 100.milliseconds
        val asked = Monitor(1)
        val times = ArrayList<Instant>()
        val sink =
            object : Sink {
                var deadline: Instant? = null

                override fun feed(bytes: ByteArray) {
                    deadline = now() + hold
                }

                override fun drainReplies(limit: Int): ByteArray? = null

                override fun childExited(status: ExitStatus) = Unit

                override fun wantsWakeup(now: Instant): Boolean {
                    asked.locked {
                        times += now
                        asked.broadcast(0)
                    }
                    return true
                }

                override fun deadline(): Instant? = deadline.also { deadline = null }
            }
        val loop = Background { runIoLoop(shared, FairLock(), sink) {} }
        shared.pushChunk(byteArrayOf(1))
        val end = now() + GENEROUS
        val (first, second) =
            asked.locked {
                while (times.size < 2 && end.hasNotPassedNow()) asked.await(0, end - now())
                times[0] to times[1]
            }
        val gap = second - first
        assertTrue(gap >= hold - 5.milliseconds, "woke after $gap")
        shared.stop()
        loop.join()
    }
}
