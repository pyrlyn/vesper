package scull.pty

import scull.pty.PtyLimits.MAX_LOCKED_BYTES
import scull.pty.PtyLimits.MAX_PENDING_READ_BYTES
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A 60 Hz frame budget is 16 ms; a frame read may wait for at most one hold plus scheduling noise. */
private val MAX_FRAME_WAIT = 250.milliseconds
private val FRAME_INTERVAL = 2.milliseconds
private const val MIN_FRAMES = 100
private val FLOOD_AT_LEAST = 2.seconds
private val FLOOD_AT_MOST = 30.seconds

/** Counts what each hold feeds, as the UI's terminal would parse it. */
private class FloodSink : Sink {
    var fed = 0L
    var thisHold = 0
    var worstHold = 0

    override fun feed(bytes: ByteArray) {
        fed += bytes.size
        thisHold += bytes.size
    }

    override fun drainReplies(limit: Int): ByteArray? = null

    override fun childExited(status: ExitStatus) = Unit

    override fun wantsWakeup(now: Instant): Boolean {
        worstHold = maxOf(worstHold, thisHold)
        thisHold = 0
        return true
    }
}

class StarvationTest {
    @Test
    fun aFloodingChildCannotStarveAFrameRead() {
        val lock = FairLock()
        val sink = FloodSink()
        var frames = 0
        var worstWait = Duration.ZERO
        var fed = 0L
        spawn(flood(), sink, lock).use { pty ->
            val start = now()
            while (true) {
                val asked = now()
                // The frame read: what the UI does under the same lock every frame.
                fed = lock.withLock { sink.fed }
                worstWait = maxOf(worstWait, asked.elapsedNow())
                frames++
                val elapsed = start.elapsedNow()
                if (elapsed >= FLOOD_AT_LEAST && fed > MAX_PENDING_READ_BYTES && frames >= MIN_FRAMES) break
                if (elapsed >= FLOOD_AT_MOST) break
                sleep(FRAME_INTERVAL)
            }
            assertNull(pty.failure)
        }
        val worstHold = lock.withLock { sink.worstHold }
        println("starvation: $frames frames, $fed bytes fed, worst frame wait $worstWait, worst hold $worstHold bytes")
        assertTrue(
            fed > MAX_PENDING_READ_BYTES,
            "the flood fed only $fed bytes in $frames frames, worst wait $worstWait, worst hold $worstHold",
        )
        assertTrue(
            frames >= MIN_FRAMES,
            "only $frames frames, fed $fed, worst wait $worstWait",
        )
        assertTrue(
            worstWait <= MAX_FRAME_WAIT,
            "a frame read waited $worstWait, fed $fed, frames $frames",
        )
        assertTrue(
            worstHold <= MAX_LOCKED_BYTES,
            "a hold fed $worstHold bytes, fed $fed, worst wait $worstWait",
        )
    }
}
