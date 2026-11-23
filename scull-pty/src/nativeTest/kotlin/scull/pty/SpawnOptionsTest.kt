package scull.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpawnOptionsTest {
    @Test
    fun extraVariablesReplaceInheritedOnesAndAreAdded() {
        val merged = mergedEnvironment(listOf("A=1", "B=2"), mapOf("B" to "3", "C" to "4"))
        assertEquals(listOf("A=1", "B=3", "C=4"), merged)
    }

    @Test
    fun namesCanCompareWithoutCase() {
        val merged = mergedEnvironment(listOf("Path=x", "A=1"), mapOf("PATH" to "y")) { a, b -> a.equals(b, ignoreCase = true) }
        assertEquals(listOf("A=1", "PATH=y"), merged)
    }

    @Test
    fun sizesOutsideTheSystemsRangeAreRefused() {
        assertFailsWith<IllegalArgumentException> { PtySize(rows = 0) }
        assertFailsWith<IllegalArgumentException> { PtySize(cols = 70000) }
        assertFailsWith<IllegalArgumentException> { PtySize(pixelWidth = -1) }
    }
}
