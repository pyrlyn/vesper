package app.vesper

import app.vesper.terminal.BlockStatus
import app.vesper.terminal.Pty4jLauncher
import app.vesper.terminal.SessionMode
import app.vesper.terminal.ShellIntegration
import app.vesper.terminal.TerminalSession
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Runs real shells in a PTY and checks that commands become blocks. */
class ShellSessionTest {
    private fun waitUntil(timeoutMs: Long = 15_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        error("timed out waiting for $what")
    }

    private fun exercise(shell: String) {
        if (!File(shell).canExecute()) return
        val dir = Files.createTempDirectory("vesper test").toFile().canonicalFile
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = TerminalSession(1, ShellIntegration.build(shell, dir.path), Pty4jLauncher(), scope, 80, 24)
        try {
            waitUntil(what = "$shell prompt") { session.mode == SessionMode.Blocks }

            session.submit("echo hello-vesper")
            waitUntil(what = "echo block") { session.blocksSnapshot().any { it.command == "echo hello-vesper" && !it.isRunning } }
            val echo = session.blocksSnapshot().first { it.command == "echo hello-vesper" }
            assertEquals(BlockStatus.Success, echo.status)
            assertEquals("hello-vesper", echo.terminal.plainText().trim())

            session.submit("sh -c 'echo oops; exit 3'")
            waitUntil(what = "failing block") { session.blocksSnapshot().any { it.command.startsWith("sh -c") && !it.isRunning } }
            val failed = session.blocksSnapshot().first { it.command.startsWith("sh -c") }
            assertEquals(BlockStatus.Failed, failed.status)
            assertEquals(3, failed.exitCode)

            val sub = File(dir, "sub dir").apply { mkdirs() }
            session.submit("cd '${sub.path}'")
            waitUntil(what = "cwd update") { session.cwd == sub.path }

            session.submit("printf 'a\\nb\\n'")
            waitUntil(what = "printf block") { session.blocksSnapshot().any { it.command.startsWith("printf") && !it.isRunning } }
            val printf = session.blocksSnapshot().first { it.command.startsWith("printf") }
            assertEquals("a\nb", printf.terminal.plainText().trim())
            assertTrue(session.blocksSnapshot().none { it.isRunning })
        } finally {
            session.close()
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun zsh() = exercise("/bin/zsh")

    @Test
    fun bash() = exercise("/bin/bash")
}
