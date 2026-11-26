package vesper.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LaunchTest {
    @Test
    fun aMissingLocaleGetsUtf8() {
        assertEquals("en_US.UTF-8", launchEnvironment { null }["LANG"])
        assertFalse("LANG" in launchEnvironment { if (it == "LC_CTYPE") "UTF-8" else null })
        assertEquals("xterm-256color", launchEnvironment { null }["TERM"])
    }
}
