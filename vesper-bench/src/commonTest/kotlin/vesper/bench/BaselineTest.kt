package vesper.bench

import okio.FileSystem
import okio.SYSTEM
import vesper.harness.baselineInput
import vesper.harness.fixed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class BaselineTest {
    // FNV-1a over the payload, so a change to the generator changes every recorded baseline.
    @Test
    fun fixedInputHashIsPinned() {
        val input = baselineInput()
        assertEquals(INPUT_BYTES, input.size)
        var hash = 0xCBF29CE484222325uL
        for (byte in input) {
            hash = (hash xor (byte.toULong() and 0xFFu)) * 0x100000001B3uL
        }
        assertEquals(0xB9FFD623F56F0E6DuL, hash)
    }

    @Test
    fun tableEmitsAMissingTerminalAndATimedStub() {
        val input = baselineInput()
        val rows =
            listOf(
                Row.measured("vesper stub", input.size, timeStub(input)),
                Row.notInstalled("kitty", input.size),
            )
        val table = render(rows, "2026-11-22", "arm64 / macosx").lines()
        val stub = table.first { "vesper stub" in it }
        val missing = table.first { "kitty" in it }
        assertTrue("not installed" in missing)
        assertTrue("| - | - |" in missing)
        assertTrue("$INPUT_BYTES" in stub)
        assertFalse("| - | - |" in stub)
    }

    @Test
    fun windowsLooksForTheExecutable() {
        assertEquals("wezterm.exe", programFile("wezterm", windows = true))
        assertEquals("wezterm", programFile("wezterm", windows = false))
    }

    @Test
    fun theMedianSkipsTheWarmUpAndTakesTheMiddleRun() {
        var calls = 0
        val median = medianTime(5) { calls++ }
        assertEquals(6, calls)
        assertTrue(median >= Duration.ZERO)
    }

    // The writer ran inside a terminal, so what it left is read as untrusted text.
    @Test
    fun onlyAPositiveNanosecondCountIsATime() {
        val file = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "vesper-bench-test-time.txt"

        fun read(text: String?): Duration? {
            FileSystem.SYSTEM.delete(file)
            if (text != null) FileSystem.SYSTEM.write(file) { writeUtf8(text) }
            return readTime(file)
        }
        assertEquals(1_500_000.nanoseconds, read("1500000\n"))
        assertNull(read(null))
        assertNull(read(""))
        assertNull(read("0"))
        assertNull(read("-4"))
        assertNull(read("12ms"))
        FileSystem.SYSTEM.delete(file)
    }

    @Test
    fun durationsRenderWithFixedDecimals() {
        assertEquals("0.001422", fixed(1.422.milliseconds.inWholeMicroseconds / 1e6, 6))
        assertEquals("670.56", fixed(670.5612, 2))
        assertEquals("0.05", fixed(0.049, 2))
        assertEquals("12", fixed(12.4, 0))
    }
}
