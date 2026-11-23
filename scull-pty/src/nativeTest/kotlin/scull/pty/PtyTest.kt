package scull.pty

import scull.pty.PtyLimits.MAX_PENDING_WRITE_BYTES
import scull.pty.PtyLimits.SHUTDOWN_GRACE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

internal fun spawn(
    options: SpawnOptions,
    sink: Sink,
    lock: FairLock = FairLock(),
): Pty = Pty.spawn(options, lock, sink) {}

class PtyTest {
    @Test
    fun aChildsOutputArrivesBeforeItsExitStatus() {
        val sink = Recorder()
        spawn(echoAndExit(), sink).use {
            val status = sink.awaitExit()
            assertEquals(ExitStatus(3u), status)
            assertTrue("hi" in sink.text(), "output: ${sink.text()}")
            // Nothing may be fed once the exit is out.
            sleep(50.milliseconds)
            assertFalse(sink.fedAfterExit)
            assertNull(it.failure)
        }
    }

    @Test
    fun aMissingProgramFailsToSpawn() {
        assertFailsWith<PtyException.Spawn> { spawn(SpawnOptions.command("scull-pty-no-such-program"), Recorder()) }
    }

    @Test
    fun badOptionsAreRefusedBeforeAnythingStarts() {
        assertFailsWith<PtyException.Spawn> { spawn(SpawnOptions.command("a\u0000b"), Recorder()) }
        assertFailsWith<PtyException.Spawn> { spawn(SpawnOptions(env = mapOf("A=B" to "c")), Recorder()) }
        assertFailsWith<PtyException.Spawn> { spawn(SpawnOptions(program = ""), Recorder()) }
    }

    @Test
    fun closeEndsAChildThatRunsForMinutes() {
        val sink = Recorder()
        val pty = spawn(runForMinutes(), sink)
        val start = now()
        pty.close()
        val took = start.elapsedNow()
        assertTrue(took < SHUTDOWN_GRACE * 4, "close took $took")
        // A shutdown is not an exit.
        sleep(100.milliseconds)
        assertNull(sink.exitStatus)
        pty.close()
        assertFailsWith<PtyException.Closed> { pty.writeInput(byteArrayOf(1)) }
        assertFailsWith<PtyException.Closed> { pty.resize(PtySize(10, 10)) }
    }

    @Test
    fun resizeIsAccepted() {
        spawn(runForMinutes(), Recorder()).use { it.resize(PtySize(rows = 30, cols = 100, pixelWidth = 800, pixelHeight = 600)) }
    }

    @Test
    fun inputIsCutToTheWriteQueue() {
        spawn(runForMinutes(), Recorder()).use {
            assertEquals(MAX_PENDING_WRITE_BYTES, it.writeInput(ByteArray(8 * MAX_PENDING_WRITE_BYTES) { 'x'.code.toByte() }))
        }
    }

    @Test
    fun killEndsTheChildAndTheExitStillArrives() {
        val sink = Recorder()
        spawn(runForMinutes(), sink).use {
            assertTrue(it.kill())
            val status = sink.awaitExit()
            assertFalse(status.success)
        }
    }
}
