package scull.ffi

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun exportReportsTheBuildVersion() {
        assertEquals(ABI_VERSION, abiVersion())
    }
}
