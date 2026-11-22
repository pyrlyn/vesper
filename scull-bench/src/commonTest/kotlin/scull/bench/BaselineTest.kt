package scull.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class BaselineTest {
    // FNV-1a over the payload; windows/Scull.Bench pins the same value for its C# copy.
    @Test
    fun fixedInputMatchesTheWindowsBenchCopy() {
        val input = fixedInput()
        assertEquals(INPUT_BYTES, input.size)
        var hash = 0xCBF29CE484222325uL
        for (byte in input) {
            hash = (hash xor (byte.toULong() and 0xFFu)) * 0x100000001B3uL
        }
        assertEquals(0xB9FFD623F56F0E6DuL, hash)
    }

    @Test
    fun tableEmitsAMissingTerminalAndATimedStub() {
        val input = fixedInput()
        val rows =
            listOf(
                Row.measured("scull stub", input.size, timeStub(input)),
                Row.notInstalled("kitty", input.size),
            )
        val table = render(rows, "2026-11-22", "arm64 / macosx").lines()
        val stub = table.first { "scull stub" in it }
        val missing = table.first { "kitty" in it }
        assertTrue("not installed" in missing)
        assertTrue("| - | - |" in missing)
        assertTrue("$INPUT_BYTES" in stub)
        assertFalse("| - | - |" in stub)
    }

    @Test
    fun durationsRenderWithFixedDecimals() {
        assertEquals("0.001422", fixed(1.422.milliseconds.inWholeMicroseconds / 1e6, 6))
        assertEquals("670.56", fixed(670.5612, 2))
        assertEquals("0.05", fixed(0.049, 2))
        assertEquals("12", fixed(12.4, 0))
    }
}
