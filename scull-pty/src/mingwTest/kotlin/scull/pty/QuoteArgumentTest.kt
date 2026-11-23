package scull.pty

import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteArgumentTest {
    @Test
    fun argumentsAreQuotedTheWayTheCRuntimeSplitsThem() {
        assertEquals("plain", quoteArgument("plain"))
        assertEquals("\"\"", quoteArgument(""))
        assertEquals("\"two words\"", quoteArgument("two words"))
        assertEquals("\"say \\\"hi\\\"\"", quoteArgument("say \"hi\""))
        assertEquals("\"C:\\dir with space\\\\\"", quoteArgument("C:\\dir with space\\"))
        assertEquals("a\\b", quoteArgument("a\\b"))
    }
}
