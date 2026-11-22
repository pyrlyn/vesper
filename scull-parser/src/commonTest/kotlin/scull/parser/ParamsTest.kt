package scull.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun params(text: String): Params {
    val params = Params()
    for (char in text) {
        when (char) {
            in '0'..'9' -> params.digit(char.code)
            ';', ':' -> params.separator(char == ':')
            else -> error("not a parameter byte: $char")
        }
    }
    params.finish()
    return params
}

private fun groups(text: String) = params(text).groups().map { it.toList() }

class ParamsTest {
    @Test
    fun noDigitsMeansNoParameters() {
        assertTrue(params("").isEmpty())
    }

    @Test
    fun emptyParametersReadAsZero() {
        assertEquals(listOf(listOf(0), listOf(0)), groups(";"))
        assertEquals(listOf(listOf(1), listOf(0)), groups("1;"))
    }

    @Test
    fun colonGroupsSubParametersWithTheirParameter() {
        assertEquals(listOf(listOf(1), listOf(38, 2, 0, 10, 20, 30), listOf(4)), groups("1;38:2::10:20:30;4"))
        val p = params("38:5")
        assertFalse(p.isSubParameter(0))
        assertTrue(p.isSubParameter(1))
        assertFalse(p.isSubParameter(2))
    }

    @Test
    fun hugeValuesSaturate() {
        assertEquals(listOf(listOf(MAX_PARAM_VALUE)), groups("99999999999"))
    }

    @Test
    fun parametersPastTheCapAreDroppedAndFlagged() {
        val p = params(List(MAX_PARAMS + 10) { "7" }.joinToString(";"))
        assertEquals(MAX_PARAMS, p.size)
        assertTrue(p.truncated)
        assertTrue(p.groups().all { it.toList() == listOf(7) })
    }

    @Test
    fun subParametersCountAgainstTheSameCap() {
        val p = params("38" + ":1".repeat(MAX_PARAMS * 2))
        assertEquals(MAX_PARAMS, p.size)
        assertTrue(p.truncated)
        assertEquals(1, p.groups().size)
        assertTrue(p.isSubParameter(MAX_PARAMS - 1))
    }
}
