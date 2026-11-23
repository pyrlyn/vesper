package scull.pty

import scull.pty.PtyLimits.MAX_PENDING_READ_BYTES
import scull.pty.PtyLimits.WRITE_CHUNK_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class Endless : ByteSource {
    override fun read(buffer: ByteArray): Int {
        buffer.fill(1)
        return buffer.size
    }

    override fun close() = Unit
}

private class Recording(
    private val accept: Boolean = true,
) : ByteOutput {
    val monitor = Monitor(1)
    val writes = ArrayList<ByteArray>()

    override fun write(bytes: ByteArray): Boolean {
        monitor.locked { writes += bytes }
        return accept
    }

    override fun close() = Unit
}

class WorkersTest {
    @Test
    fun aFloodingReaderStopsAtTheCapAndResumesWhenDrained() {
        val shared = Shared()
        val reader = Background { readLoop(shared, Endless()) }
        sleep(150.milliseconds)
        assertTrue(shared.queuedBytes <= MAX_PENDING_READ_BYTES)
        var drained = 0L
        val end = now() + GENEROUS
        while (drained <= 2L * MAX_PENDING_READ_BYTES && end.hasNotPassedNow()) {
            drained += shared.takeBatch().sumOf { it.size }
            assertTrue(shared.queuedBytes <= MAX_PENDING_READ_BYTES)
        }
        assertTrue(drained > 2L * MAX_PENDING_READ_BYTES, "drained $drained bytes")
        shared.stop()
        reader.join()
    }

    @Test
    fun endOfOutputIsMarkedAfterTheLastChunk() {
        val shared = Shared()
        var reads = 0
        val source =
            object : ByteSource {
                override fun read(buffer: ByteArray): Int {
                    if (reads == 3) return 0
                    buffer[0] = (reads++).toByte()
                    return 1
                }

                override fun close() = Unit
            }
        readLoop(shared, source)
        shared.setExit(ExitStatus(0u))
        assertEquals(Work.Data, shared.waitForWork(null))
        assertEquals(listOf<Byte>(0, 1, 2), shared.takeBatch().map { it.single() })
        // EOF is known, so the exit does not wait out EXIT_IDLE.
        val start = now()
        assertEquals(Work.Exited(ExitStatus(0u)), shared.waitForWork(null))
        assertTrue(start.elapsedNow() < PtyLimits.EXIT_IDLE)
    }

    @Test
    fun theWriterSendsInputInBoundedChunksAndInOrder() {
        val shared = Shared()
        val input = ByteArray(3 * WRITE_CHUNK_BYTES + 5) { (it % 251).toByte() }
        assertEquals(input.size, shared.pushInput(input))
        val output = Recording()
        val writer = Background { writeLoop(shared, output) }
        val end = now() + GENEROUS
        while (shared.outboxBytes > 0 && end.hasNotPassedNow()) sleep(1.milliseconds)
        shared.stop()
        writer.join()
        val writes = output.monitor.locked { output.writes.toList() }
        assertTrue(writes.all { it.size <= WRITE_CHUNK_BYTES })
        val joined = ByteArray(writes.sumOf { it.size })
        var at = 0
        for (write in writes) {
            write.copyInto(joined, at)
            at += write.size
        }
        assertContentEquals(input, joined)
    }

    @Test
    fun aFailedWriteDropsTheQueueAndRefusesMoreInput() {
        val shared = Shared()
        shared.pushInput(ByteArray(2 * WRITE_CHUNK_BYTES))
        writeLoop(shared, Recording(accept = false))
        assertEquals(0, shared.outboxBytes)
        assertFailsWith<PtyException.Closed> { shared.pushInput(ByteArray(1)) }
    }
}
