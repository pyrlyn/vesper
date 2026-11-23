package scull.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PosixPtyTest {
    @Test
    fun aResizeReachesTheChild() {
        val sink = Recorder()
        spawn(SpawnOptions.command("/bin/sh", "-c", "read line; stty size"), sink).use {
            it.resize(PtySize(rows = 31, cols = 101))
            assertEquals(3, it.writeInput("go\n".encodeToByteArray()))
            sink.awaitExit()
            assertTrue("31 101" in sink.text(), "output: ${sink.text()}")
        }
    }

    @Test
    fun theInitialSizeReachesTheChild() {
        val sink = Recorder()
        spawn(SpawnOptions(program = "/bin/sh", args = listOf("-c", "stty size"), size = PtySize(rows = 12, cols = 34)), sink).use {
            sink.awaitExit()
            assertTrue("12 34" in sink.text(), "output: ${sink.text()}")
        }
    }

    @Test
    fun repliesReachTheChild() {
        val sink = Recorder { output -> if ("ask?" in output) "yes\n" else null }
        spawn(SpawnOptions.command("/bin/sh", "-c", "printf 'ask?'; read answer; echo \"got:\$answer\""), sink).use {
            sink.awaitExit()
            assertTrue("got:yes" in sink.text(), "output: ${sink.text()}")
        }
    }

    @Test
    fun theEnvironmentAndDirectoryReachTheChild() {
        val sink = Recorder()
        val options =
            SpawnOptions(
                program = "/bin/sh",
                args = listOf("-c", "echo \"\$SCULL_PTY_TEST:\$(pwd)\""),
                cwd = "/",
                env = mapOf("SCULL_PTY_TEST" to "here"),
            )
        spawn(options, sink).use {
            sink.awaitExit()
            assertTrue("here:/\r\n" in sink.text(), "output: ${sink.text()}")
        }
    }

    @Test
    fun aSignalIsReported() {
        val sink = Recorder()
        spawn(SpawnOptions.command("/bin/sh", "-c", "kill -TERM \$\$"), sink).use {
            val status = sink.awaitExit()
            assertEquals(15, status.signal)
        }
    }

    @Test
    fun aChildIsTheLeaderOfItsOwnSessionOnThePty() {
        val sink = Recorder()
        // A process with a controlling terminal can open /dev/tty; without one the open fails.
        spawn(SpawnOptions.command("/bin/sh", "-c", "echo ok > /dev/tty"), sink).use {
            assertEquals(ExitStatus(0u), sink.awaitExit())
            assertTrue("ok" in sink.text(), "output: ${sink.text()}")
        }
    }

    @Test
    fun controlCInterruptsTheChild() {
        val sink = Recorder()
        // ^C becomes SIGINT only for the foreground group of the terminal that controls the session.
        spawn(runForMinutes(), sink).use {
            sleep(200.milliseconds)
            assertEquals(1, it.writeInput(byteArrayOf(3)))
            assertEquals(2, sink.awaitExit().signal)
        }
    }
}
