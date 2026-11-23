package scull.pty

import scull.pty.PtyLimits.EXIT_DRAIN_MAX
import scull.pty.PtyLimits.EXIT_IDLE
import scull.pty.PtyLimits.MAX_LOCKED_BYTES
import scull.pty.PtyLimits.MAX_PENDING_READ_BYTES
import scull.pty.PtyLimits.MAX_PENDING_WRITE_BYTES
import scull.pty.PtyLimits.WRITE_CHUNK_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** How long a thread that should stay blocked is given to prove it does not return. */
private val NEGATIVE_WAIT = 150.milliseconds
private const val CHUNK = 16 * 1024

private fun Shared.fillReadQueue() {
    repeat(MAX_PENDING_READ_BYTES / CHUNK) { assertTrue(pushChunk(ByteArray(CHUNK))) }
}

private object Silent : Sink {
    override fun feed(bytes: ByteArray) = Unit

    override fun drainReplies(limit: Int): ByteArray? = null

    override fun childExited(status: ExitStatus) = Unit
}

class SharedTest {
    @Test
    fun aPushBlocksAtTheCapUntilABatchIsTaken() {
        val shared = Shared()
        shared.fillReadQueue()
        val push = Background { shared.pushChunk(ByteArray(CHUNK)) }
        sleep(NEGATIVE_WAIT)
        assertFalse(push.isDone, "a push past the cap returned")
        assertEquals(MAX_PENDING_READ_BYTES, shared.queuedBytes)
        shared.takeBatch()
        assertTrue(push.join())
        assertTrue(shared.queuedBytes <= MAX_PENDING_READ_BYTES)
    }

    @Test
    fun stopReleasesABlockedReader() {
        val shared = Shared()
        shared.fillReadQueue()
        val push = Background { shared.pushChunk(ByteArray(CHUNK)) }
        sleep(NEGATIVE_WAIT)
        shared.stop()
        assertFalse(push.join())
    }

    @Test
    fun aBatchStaysWithinTheBudgetAndNeverSplitsOrReordersAChunk() {
        val budget = MAX_LOCKED_BYTES
        val sizes = listOf(budget * 2 / 3, budget / 2, 1, budget, budget / 3, budget / 3, budget / 4)
        val shared = Shared()
        sizes.forEachIndexed { index, size -> shared.pushChunk(ByteArray(size) { index.toByte() }) }
        val batches = generateSequence { shared.takeBatch().takeIf { it.isNotEmpty() } }.toList()
        assertEquals(
            listOf(listOf(sizes[0]), listOf(sizes[1], 1), listOf(budget), listOf(sizes[4], sizes[5], sizes[6])),
            batches.map { batch -> batch.map { it.size } },
        )
        batches.forEach { batch -> assertTrue(batch.sumOf { it.size } <= budget) }
        assertEquals(sizes.indices.toList(), batches.flatten().map { it.first().toInt() })
        assertEquals(0, shared.queuedBytes)
    }

    @Test
    fun repliesAreClampedToTheWriteQueue() {
        val chatty =
            object : Sink by Silent {
                // Ignores the limit on purpose: the clamp must not rely on the sink.
                override fun drainReplies(limit: Int): ByteArray = ByteArray(4 * MAX_PENDING_WRITE_BYTES)
            }
        val shared = Shared()
        shared.queueReplies(chatty)
        assertEquals(MAX_PENDING_WRITE_BYTES, shared.outboxBytes)
        shared.queueReplies(chatty)
        assertEquals(MAX_PENDING_WRITE_BYTES, shared.outboxBytes)
    }

    @Test
    fun inputIsTakenUpToTheRoomAndRoomComesBackPerWrittenChunk() {
        val shared = Shared()
        assertEquals(MAX_PENDING_WRITE_BYTES, shared.pushInput(ByteArray(MAX_PENDING_WRITE_BYTES + 10)))
        assertEquals(0, shared.pushInput(ByteArray(1)))
        val chunk = assertNotNull(shared.nextOutput())
        assertEquals(WRITE_CHUNK_BYTES, chunk.size)
        // Peeked, not written yet: still no room.
        assertEquals(0, shared.pushInput(ByteArray(1)))
        shared.outputWritten(chunk.size)
        assertEquals(WRITE_CHUNK_BYTES, shared.pushInput(ByteArray(5000)))
    }

    @Test
    fun wholeInputIsAllOrNothing() {
        val shared = Shared()
        shared.pushInput(ByteArray(MAX_PENDING_WRITE_BYTES - 3))
        assertFalse(shared.pushInputWhole(ByteArray(4)))
        assertEquals(MAX_PENDING_WRITE_BYTES - 3, shared.outboxBytes)
        assertTrue(shared.pushInputWhole(ByteArray(3)))
        assertEquals(MAX_PENDING_WRITE_BYTES, shared.outboxBytes)
    }

    @Test
    fun inputIsRefusedAfterAFailedWriteOrAStop() {
        val failed = Shared()
        failed.pushInput(ByteArray(10))
        failed.writerFailed()
        assertEquals(0, failed.outboxBytes)
        assertFailsWith<PtyException.Closed> { failed.pushInput(ByteArray(1)) }
        assertFailsWith<PtyException.Closed> { failed.pushInputWhole(ByteArray(1)) }
        val stopped = Shared()
        stopped.stop()
        assertFailsWith<PtyException.Closed> { stopped.pushInput(ByteArray(1)) }
    }

    @Test
    fun outputComesBeforeTheExit() {
        val shared = Shared()
        shared.pushChunk(byteArrayOf(1))
        shared.setExit(ExitStatus(0u))
        shared.readerDone()
        assertSame(Work.Data, shared.waitForWork(null))
        shared.takeBatch()
        assertEquals(Work.Exited(ExitStatus(0u)), shared.waitForWork(null))
    }

    @Test
    fun theExitArrivesAfterQuietOutputWithoutEof() {
        val shared = Shared()
        val start = now()
        shared.setExit(ExitStatus(2u))
        assertEquals(Work.Exited(ExitStatus(2u)), shared.waitForWork(null))
        val waited = start.elapsedNow()
        assertTrue(waited >= EXIT_IDLE - 5.milliseconds, "the exit came after $waited")
        assertTrue(waited < EXIT_DRAIN_MAX, "the exit came after $waited")
    }

    @Test
    fun stopBeatsQueuedOutput() {
        val shared = Shared()
        shared.pushChunk(byteArrayOf(1))
        shared.stop()
        assertSame(Work.Stop, shared.waitForWork(null))
    }

    @Test
    fun pausedOutputWaitsWhileTheDeadlineStillTicks() {
        val shared = Shared()
        shared.pushChunk(byteArrayOf(1))
        shared.setPaused(true)
        assertSame(Work.Tick, shared.waitForWork(now() + 50.milliseconds))
    }

    @Test
    fun theExitWaitsBehindPausedOutput() {
        val shared = Shared()
        shared.pushChunk(byteArrayOf(1))
        shared.setExit(ExitStatus(0u))
        shared.readerDone()
        shared.setPaused(true)
        assertSame(Work.Tick, shared.waitForWork(now() + EXIT_IDLE * 2))
        shared.setPaused(false)
        assertSame(Work.Data, shared.waitForWork(null))
    }

    @Test
    fun resumeWakesAWaitingLoop() {
        val shared = Shared()
        shared.pushChunk(byteArrayOf(1))
        shared.setPaused(true)
        val loop = Background { shared.waitForWork(null) }
        sleep(NEGATIVE_WAIT)
        assertFalse(loop.isDone)
        shared.setPaused(false)
        assertSame(Work.Data, loop.join())
    }

    @Test
    fun aFinishedWorkerIsMarkedAndAFailingLoopStopsTheTerminal() {
        val shared = Shared()
        shared.runWorker(Worker.READER) {}
        assertFalse(shared.isStopped)
        val boom = IllegalStateException("boom")
        shared.runWorker(Worker.LOOP) { throw boom }
        assertTrue(shared.isStopped)
        assertSame(boom, shared.failure)
        val all = Worker.READER.bit or Worker.LOOP.bit
        assertEquals(all, shared.waitSettled(all, now()))
    }
}
