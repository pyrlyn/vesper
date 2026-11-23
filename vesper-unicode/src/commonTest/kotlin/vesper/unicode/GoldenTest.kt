package vesper.unicode

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Golden segmentation and width results for the sequences a terminal meets most: emoji ZWJ
// sequences, flags, skin tones, variation selectors, combining marks, Hangul jamo, CJK and
// ambiguous width. Driven the way the grid will drive the module: one code point at a time.

private val NARROW = WidthOptions(AmbiguousWidth.Narrow)
private val WIDE = WidthOptions(AmbiguousWidth.Wide)
private const val MAX_WIDTH = 2
private const val SEED = 0x00C0FFEE
private const val CASES = 2000

/** Clusters of [text] with their widths under mode 2027. */
private fun layout(
    text: String,
    options: WidthOptions = NARROW,
): List<Pair<String, Int>> {
    val state = GraphemeState(options.version)
    val out = mutableListOf<Pair<StringBuilder, ClusterWidth>>()
    text.forEachCodePoint { cp ->
        if (state.next(cp) || out.isEmpty()) out += StringBuilder() to ClusterWidth(options, ClusterPolicy.Grapheme)
        val (cluster, acc) = out.last()
        cluster.appendCodePoint(cp)
        acc.push(cp)
    }
    return out.map { (cluster, acc) -> cluster.toString() to acc.width }
}

private fun one(
    cluster: String,
    cells: Int,
) = listOf(cluster to cells)

private fun cp(value: Int): String = StringBuilder().apply { appendCodePoint(value) }.toString()

class GoldenTest {
    @Test
    fun zwjFamilyIsOneWideCluster() {
        val family = cp(0x1F468) + "‍" + cp(0x1F469) + "‍" + cp(0x1F467)
        assertEquals(one(family, 2), layout(family))
    }

    @Test
    fun zwjJoinsATextDefaultBaseIntoAWideEmoji() {
        // HEART ON FIRE with and without the VS16 its RGI form carries.
        for (seq in listOf("❤️‍" + cp(0x1F525), "❤‍" + cp(0x1F525))) {
            assertEquals(one(seq, 2), layout(seq), seq)
        }
    }

    @Test
    fun flagsPairRegionalIndicators() {
        val us = cp(0x1F1FA) + cp(0x1F1F8)
        val de = cp(0x1F1E9) + cp(0x1F1EA)
        assertEquals(listOf(us to 2, de to 2), layout(us + de))
        assertEquals(one(cp(0x1F1FA), 2), layout(cp(0x1F1FA)))
    }

    @Test
    fun skinToneModifierJoinsItsBase() {
        val thumbs = cp(0x1F44D) + cp(0x1F3FD)
        assertEquals(one(thumbs, 2), layout(thumbs))
        assertEquals(2, width(0x1F3FD, NARROW))
    }

    @Test
    fun vs16WidensATextDefaultEmoji() {
        assertEquals(one("❤", 1), layout("❤"))
        assertEquals(one("❤️", 2), layout("❤️"))
        val keycap = "1️⃣"
        assertEquals(one(keycap, 2), layout(keycap))
    }

    @Test
    fun vs15KeepsTheWidthOfAnEmojiDefaultBase() {
        assertEquals(one("⌚", 2), layout("⌚"))
        assertEquals(one("⌚︎", 2), layout("⌚︎"))
    }

    @Test
    fun selectorsLeaveBasesWithoutVariationSequencesAlone() {
        assertEquals(one("a️", 1), layout("a️"))
        assertEquals(one("中︎", 2), layout("中︎"))
    }

    @Test
    fun mode2027ResetKeepsTheFirstCodePointWidth() {
        val heart = "❤️"
        assertEquals(1, clusterWidth(heart, NARROW, ClusterPolicy.FirstCodePoint))
        assertEquals(2, clusterWidth(heart, NARROW, ClusterPolicy.Grapheme))
        val heartOnFire = "❤‍" + cp(0x1F525)
        assertEquals(1, clusterWidth(heartOnFire, NARROW, ClusterPolicy.FirstCodePoint))
    }

    @Test
    fun combiningMarksAddNoWidth() {
        val e = "é̂"
        assertEquals(one(e, 1), layout(e))
        assertEquals(0, width(0x0301, NARROW))
        // A mark with no base is its own zero-width cluster; the grid places it.
        assertEquals(one("́", 0), layout("́"))
    }

    @Test
    fun indicConjunctIsOneCluster() {
        val ksha = "क्ष"
        assertEquals(one(ksha, 1), layout(ksha))
    }

    @Test
    fun hangulJamoComposeIntoOneWideCluster() {
        val han = "한"
        assertEquals(one(han, 2), layout(han))
        assertEquals(one("한", 2), layout("한"))
        assertEquals(0, width(0x1161, NARROW))
        assertEquals(0, width(0x11AB, NARROW))
    }

    @Test
    fun cjkAndFullwidthAreWideHalfwidthIsNarrow() {
        for (c in listOf(0x4E2D, 0x3042, 0xFF21, 0x20000, 0x3FFFD)) assertEquals(2, width(c, NARROW), c.toString(16))
        val voicedKa = "ｶﾞ"
        assertEquals(one(voicedKa, 1), layout(voicedKa))
    }

    @Test
    fun ambiguousCharactersFollowTheSetting() {
        for (c in listOf(0x00B1, 0x03B1, 0x2192, 0xE000)) {
            assertEquals(1, width(c, NARROW), c.toString(16))
            assertEquals(2, width(c, WIDE), c.toString(16))
        }
        assertEquals(1, width('a'.code, WIDE))
        assertEquals(2, width(0x4E2D, WIDE))
    }

    @Test
    fun controlsAreZeroWide() {
        for (c in listOf(0x00, 0x1B, 0x7F, 0x85, 0x9F)) assertEquals(0, width(c), c.toString(16))
    }

    @Test
    fun widthNeverExceedsTwo() {
        for (c in -1..MAX_CODE_POINT + 1) {
            assertTrue(width(c, NARROW) <= MAX_WIDTH && width(c, WIDE) <= MAX_WIDTH, c.toString(16))
        }
    }

    @Test
    fun segmentationAndClusterWidthAcceptAnyInput() {
        val random = Random(SEED)
        repeat(CASES) {
            val text = buildString { repeat(random.nextInt(64)) { appendCodePoint(random.nextInt(MAX_CODE_POINT + 1)) } }
            for ((_, cells) in layout(text, WIDE)) assertTrue(cells <= MAX_WIDTH, text)
        }
    }
}
