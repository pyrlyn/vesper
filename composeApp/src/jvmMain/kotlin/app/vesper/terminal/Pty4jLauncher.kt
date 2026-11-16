package app.vesper.terminal

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class Pty4jLauncher : PtyLauncher {
    override fun start(
        launch: ShellLaunch,
        cols: Int,
        rows: Int,
        onData: (ByteArray, Int) -> Unit,
        onExit: (Int) -> Unit,
    ): PtyConnection {
        val process: PtyProcess = PtyProcessBuilder(launch.command.toTypedArray())
            .setEnvironment(launch.env)
            .setDirectory(launch.cwd)
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setConsole(false)
            .setUseWinConPty(true)
            .start()

        thread(name = "vesper-pty-reader-${process.pid()}", isDaemon = true) {
            val buffer = ByteArray(64 * 1024)
            val input = process.inputStream
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) onData(buffer, n)
                }
            } catch (_: Exception) {
                // Stream closes when the process exits.
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            onExit(code)
        }

        // Writes go through one thread so a large paste never blocks the UI.
        val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "vesper-pty-writer").apply { isDaemon = true } }

        return object : PtyConnection {
            override val pid: Long = process.pid()
            override val isAlive: Boolean get() = process.isAlive

            override fun write(bytes: ByteArray) {
                writer.execute {
                    runCatching {
                        process.outputStream.write(bytes)
                        process.outputStream.flush()
                    }
                }
            }

            override fun resize(cols: Int, rows: Int) {
                if (process.isAlive) runCatching { process.winSize = WinSize(cols, rows) }
            }

            override fun kill() {
                writer.shutdown()
                // Hang up the whole process group like closing a terminal window does.
                runCatching { process.descendants().forEach { it.destroy() } }
                process.destroy()
            }
        }
    }
}
