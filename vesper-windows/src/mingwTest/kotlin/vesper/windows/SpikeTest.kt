package vesper.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuTest {
    /** The renderer tests' footing: WARP draws into an offscreen texture that reads back exactly. */
    @Test
    fun warpClearsAnOffscreenTargetThatReadsBack() {
        Gpu.create(warp = true).use { gpu ->
            OffscreenTarget.create(gpu, 8, 4).use { target ->
                target.clear(0x285AA0)
                val pixels = target.read()
                assertEquals(32, pixels.size)
                for (pixel in pixels) assertEquals(0xFF285AA0.toInt(), pixel)
            }
        }
    }
}

class FontsTest {
    /** Consolas ships with every Windows, so the runner has it: DirectWrite is reached from Kotlin. */
    @Test
    fun aMonospacedSystemFaceIsMeasured() {
        FontSystem.create().use { fonts ->
            val face = assertNotNull(fonts.face("Consolas"))
            face.use {
                assertTrue(it.unitsPerEm > 0)
                assertTrue(it.ascent > 0 && it.descent > 0)
                val m = it.glyph('M'.code)
                val i = it.glyph('i'.code)
                assertTrue(m != 0 && i != 0 && m != i)
                assertTrue(it.advance(m) > 0)
                assertEquals(it.advance(m), it.advance(i))
                // A code point past the Basic Multilingual Plane goes through as one UTF-32 value.
                assertEquals(0, it.glyph(0x1F600))
            }
            assertNotNull(fonts.face("Consolas", weight = 700, italic = true)).close()
        }
    }

    @Test
    fun aMissingFamilyIsNull() {
        FontSystem.create().use { assertNull(it.face("No Such Family Vesper")) }
    }
}
