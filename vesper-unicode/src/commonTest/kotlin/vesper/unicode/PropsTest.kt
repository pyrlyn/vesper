package vesper.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropsTest {
    @Test
    fun everyCodePointHasProps() {
        val v = UnicodeVersion.LATEST
        for (cp in 0..MAX_CODE_POINT) assertTrue(lookup(cp, v).toChar() in PROPS, "U+${cp.toString(16)}")
    }

    @Test
    fun latestVersionNamesTheGeneratedTables() {
        val v = UnicodeVersion.LATEST
        assertEquals(Triple(18, 0, 0), Triple(v.major, v.minor, v.update))
    }

    // These also catch a generator whose value order or bit layout drifted from Props.kt.
    @Test
    fun lookupReadsKnownProperties() {
        val v = UnicodeVersion.LATEST
        assertEquals(INCB_LINKER, incb(lookup(0x094D, v)))
        assertEquals(INCB_CONSONANT, incb(lookup(0x0915, v)))
        assertEquals(INCB_EXTEND, incb(lookup(0x0301, v)))
        assertEquals(GCB_ZWJ, gcb(lookup(0x200D, v)))
        assertEquals(GCB_REGIONAL_INDICATOR, gcb(lookup(0x1F1E6, v)))
        assertEquals(GCB_LVT, gcb(lookup(0xD55C, v)))
        assertEquals(GCB_SPACING_MARK, gcb(lookup(0x0903, v)))
        assertEquals(GCB_PREPEND, gcb(lookup(0x0600, v)))
        assertEquals(WIDTH_AMBIGUOUS, widthClass(lookup(0x00B1, v)))
        assertTrue(isExtendedPictographic(lookup(0x1F600, v)))
        assertTrue(isVariationBase(lookup(0x2764, v)))
        assertFalse(isVariationBase(lookup('a'.code, v)))
    }

    @Test
    fun valuesOutsideTheCodeSpaceReadAsTheReplacementCharacter() {
        val v = UnicodeVersion.LATEST
        assertEquals(lookup(0xFFFD, v), lookup(-1, v))
        assertEquals(lookup(0xFFFD, v), lookup(MAX_CODE_POINT + 1, v))
    }
}
