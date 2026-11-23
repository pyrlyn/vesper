package scull.ffi

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import scull.ffi.c.TT_EVENT_CHILD_EXITED
import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_OK
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A C callback cannot capture, so the count lives at the top level; one test uses it.
private val wakeups = AtomicInt(0)

private val countWakeup = staticCFunction { _: COpaquePointer? -> wakeups.incrementAndFetch().let { } }

@OptIn(ExperimentalNativeApi::class)
private val windows = Platform.osFamily == OsFamily.WINDOWS

/** Prints `hi` and exits with status 3. */
private val echoAndExit =
    if (windows) listOf("cmd.exe", "/C", "echo hi& exit 3") else listOf("/bin/sh", "-c", "echo hi; exit 3")

class SpawnTest {
    @Test
    fun theChildsOutputAndExitReachTheHost() {
        val (status, term) = spawn(echoAndExit)
        assertEquals(TT_OK, status)
        term!!
        val frame = ttFrameNew()!!
        var exit: EventView? = null
        waitFor("the exit event") {
            exit = generateSequence { poll(term) }.firstOrNull { it.kind == TT_EVENT_CHILD_EXITED }
            exit != null
        }
        assertEquals(3u, exit!!.exitCode)
        assertEquals(false, exit!!.signaled)
        // Every output was fed before the exit was reported.
        assertTrue(update(frame, term).text.any { it.startsWith("hi") })
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun wakeupsCoalesceUntilTheHostLooks() {
        wakeups.store(0)
        val (status, term) = spawn(echoAndExit, wakeup = countWakeup)
        assertEquals(TT_OK, status)
        term!!
        waitFor("the first wakeup") { wakeups.load() > 0 }
        // Output and exit both arrive in this time; neither may wake the host a second time.
        platform.posix.usleep(500_000u)
        assertEquals(1, wakeups.load())
        waitFor("the exit event") { generateSequence { poll(term) }.any { it.kind == TT_EVENT_CHILD_EXITED } }
        ttTermFree(term)
    }

    @Test
    fun aResizeReachesTheChild() {
        val command = if (windows) listOf("cmd.exe", "/C", "ping -n 30 127.0.0.1") else listOf("sleep", "30")
        val (status, term) = spawn(command)
        assertEquals(TT_OK, status)
        term!!
        assertEquals(TT_OK, ttTermResizeBegin(term))
        assertEquals(TT_OK, ttTermResize(term, 30u, 8u, 300u, 160u))
        val frame = ttFrameNew()!!
        val view = update(frame, term)
        assertEquals(30 to 8, view.cols to view.rows)
        ttFrameFree(frame)
        // Freeing hangs up a child that is still running.
        ttTermFree(term)
    }

    @Test
    fun badSpawnOptionsAreInvalidAndStartNothing() {
        memScoped {
            val out = alloc<COpaquePointerVar>()
            val badProgram = options()
            str(badProgram.program, byteArrayOf(0xFF.toByte()))
            assertEquals(TT_INVALID, ttTermSpawn(badProgram.ptr, out.ptr))

            val badEnv = options()
            str(badEnv.program, echoAndExit.first().encodeToByteArray())
            badEnv.env = strs(listOf("NO_EQUALS_SIGN"))
            badEnv.env_len = 1u
            assertEquals(TT_INVALID, ttTermSpawn(badEnv.ptr, out.ptr))

            badEnv.env = strs(listOf("=value"))
            assertEquals(TT_INVALID, ttTermSpawn(badEnv.ptr, out.ptr))

            // A length with no array behind it.
            val noArgs = options()
            noArgs.args_len = 2u
            assertEquals(TT_INVALID, ttTermSpawn(noArgs.ptr, out.ptr))
        }
    }
}
