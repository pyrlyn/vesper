package scull.ucd

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

// Turns the pinned Unicode data files into the committed sources of scull-unicode. It runs in the
// build rather than in the library so the library never touches the network or the filesystem, and
// the tables are reviewed as committed source instead of being rebuilt on every compile.

internal const val UNICODE_VERSION = "18.0.0"

/** Data files under `https://www.unicode.org/Public/<version>/ucd/` with their SHA-256 (checked 2026-10-07). */
private val FILES =
    listOf(
        "EastAsianWidth.txt" to "a0cf29eacd00cfcaec4381c6b7c281685f18dbb4e7ff82b4076ccb342ca839aa",
        "extracted/DerivedGeneralCategory.txt" to "d6b151d2d40ee9b1876d26f417980f45ffae47b6055ccf7203cb31f07a030f94",
        "DerivedCoreProperties.txt" to "09c928886a178fcafd93c29e4bd59073a058e5a100b716d425cb563ab50f68c9",
        "auxiliary/GraphemeBreakProperty.txt" to "0839dcb79e4ac639ecd538b1abf7c9d22e3f9dd265b7e182d33627aa4d75b45a",
        "auxiliary/GraphemeBreakTest.txt" to "b0cf047ee94485bbdc846de2b902f5f8a815f6b674f9d04223cddadd91c9df31",
        "emoji/emoji-data.txt" to "80d00f8e616a0ef27fd6b8de3b758c06383b5d917e2977709578e68baf733bf1",
        "emoji/emoji-variation-sequences.txt" to "ff1707564aa1f1b2fcf4ec92d609d4cb26940bc0d4dcf07f5328ad6879e84da3",
    )

/** The largest pinned file is about 1.2 MB; anything far bigger is not it. */
private const val MAX_DOWNLOAD_BYTES = 8 shl 20
private const val SCALAR_END = 0x110000

// Code points per leaf block and leaf blocks per middle block, as bit counts: 4/5 gave the smallest
// total of every split from 4/3 to 8/8 (measured on the Rust generator this one ports).
private const val LOW_BITS = 4
private const val MID_BITS = 5
private const val CHARS_PER_LINE = 16

// The packed record layout and value orders below are also in scull-unicode's Props.kt; the two
// must agree, and its known-property tests fail when they do not.
private const val ZERO = 0
private const val NARROW = 1
private const val WIDE = 2
private const val AMBIGUOUS = 3

/** UCD `Grapheme_Cluster_Break` values; index 0 is the default. */
private val GCB_NAMES =
    listOf("Other", "CR", "LF", "Control", "Extend", "ZWJ", "Regional_Indicator", "Prepend", "SpacingMark", "L", "V", "T", "LV", "LVT")

/** UCD `InCB` values; index 0 is the default. */
private val INCB_NAMES = listOf("None", "Linker", "Consonant", "Extend")

/** General categories that take no cell: marks over their base, invisible format characters, controls. */
private val ZERO_WIDTH_CATEGORIES = setOf("Mn", "Me", "Cf", "Cc")

private const val GCB_SHIFT = 2
private const val INCB_SHIFT = 6
private const val EXT_PICT = 1 shl 8
private const val VS_BASE = 1 shl 9
private const val WIDTH_MASK = 0x3

/** A committed output: its path below the module and its full text. */
internal class Output(
    val path: String,
    val text: String,
)

internal object Ucd {
    const val TABLES_PATH = "src/commonMain/kotlin/scull/unicode/Tables.kt"
    const val CASES_PATH = "src/commonTest/kotlin/scull/unicode/GraphemeBreakCases.kt"

    /** Downloads every pinned file missing from [cache]; each is checked when it is read. */
    fun fetch(cache: File) {
        cache.mkdirs()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
        for ((path, _) in FILES) {
            val dest = File(cache, path.substringAfterLast('/'))
            if (dest.exists()) continue
            val url = "https://www.unicode.org/Public/$UNICODE_VERSION/ucd/$path"
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(2)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == HTTP_OK) { "GET $url: HTTP ${response.statusCode()}" }
            val bytes = response.body().use { it.readNBytes(MAX_DOWNLOAD_BYTES + 1) }
            check(bytes.size <= MAX_DOWNLOAD_BYTES) { "$url is larger than $MAX_DOWNLOAD_BYTES bytes" }
            val partial = File(cache, "${dest.name}.part")
            partial.writeBytes(bytes)
            check(partial.renameTo(dest)) { "cannot rename $partial to $dest" }
        }
    }

    /** Every committed output, rendered from the files in [cache]. */
    fun render(cache: File): List<Output> {
        val text = FILES.associate { (path, sha) -> path.substringAfterLast('/') to load(cache, path, sha) }
        return listOf(
            Output(TABLES_PATH, renderTables(buildProps(text))),
            Output(CASES_PATH, renderCases(text.getValue("GraphemeBreakTest.txt"))),
        )
    }

    /** Reads a cached file and refuses it unless it is the pinned release. */
    private fun load(
        cache: File,
        path: String,
        sha256: String,
    ): String {
        val file = File(cache, path.substringAfterLast('/'))
        val bytes = file.readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == sha256) { "$file has SHA-256 $actual, expected $sha256; delete it to download it again" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    /** `(first, last, fields)` per data line; a sequence such as `0023 FE0F` yields its first code point. */
    fun records(text: String): List<Triple<Int, Int, List<String>>> =
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { line ->
            val fields = line.split(';').map(String::trim)
            val range = fields[0].split(' ').first()
            val lo = range.substringBefore("..").toInt(HEX)
            val hi = range.substringAfter("..", range).toInt(HEX)
            require(lo <= hi && hi < SCALAR_END) { "bad range in \"$line\"" }
            Triple(lo, hi, fields.drop(1))
        }.toList()

    private fun index(
        names: List<String>,
        name: String?,
    ): Int = names.indexOf(name).also { require(it >= 0) { "unknown property value \"$name\"" } }

    private fun buildProps(text: Map<String, String>): IntArray {
        val width = IntArray(SCALAR_END) { NARROW }
        val gcb = IntArray(SCALAR_END)
        val incb = IntArray(SCALAR_END)
        val flags = IntArray(SCALAR_END)
        for ((lo, hi, f) in records(text.getValue("EastAsianWidth.txt"))) {
            val value =
                when (f.firstOrNull()) {
                    "W", "F" -> WIDE
                    "A" -> AMBIGUOUS
                    else -> NARROW
                }
            width.fill(value, lo, hi + 1)
        }
        for ((lo, hi, f) in records(text.getValue("GraphemeBreakProperty.txt"))) {
            gcb.fill(index(GCB_NAMES, f.firstOrNull()), lo, hi + 1)
        }
        for ((lo, hi, f) in records(text.getValue("DerivedCoreProperties.txt"))) {
            if (f.size == 2 && f[0] == "InCB") incb.fill(index(INCB_NAMES, f[1]), lo, hi + 1)
        }
        for ((lo, hi, f) in records(text.getValue("emoji-data.txt"))) {
            if (f.firstOrNull() == "Extended_Pictographic") (lo..hi).forEach { flags[it] = flags[it] or EXT_PICT }
        }
        for ((lo, _, _) in records(text.getValue("emoji-variation-sequences.txt"))) {
            flags[lo] = flags[lo] or VS_BASE
        }
        for ((lo, hi, f) in records(text.getValue("DerivedGeneralCategory.txt"))) {
            if (f.firstOrNull() in ZERO_WIDTH_CATEGORIES) width.fill(ZERO, lo, hi + 1)
        }
        val v = GCB_NAMES.indexOf("V")
        val t = GCB_NAMES.indexOf("T")
        val ri = GCB_NAMES.indexOf("Regional_Indicator")
        return IntArray(SCALAR_END) { cp ->
            val cells =
                when (gcb[cp]) {
                    // Medial vowels and final consonants draw inside the leading consonant's cells.
                    v, t -> ZERO
                    // Fonts draw a lone regional indicator wide, and a flag must not grow when its
                    // second half arrives.
                    ri -> WIDE
                    else -> width[cp]
                }
            (cells and WIDTH_MASK) or (gcb[cp] shl GCB_SHIFT) or (incb[cp] shl INCB_SHIFT) or flags[cp]
        }
    }

    /** Splits [items] into blocks of [block] entries, stores each distinct block once, and returns the stored entries and each block's index. */
    fun dedupe(
        items: IntArray,
        block: Int,
    ): Pair<IntArray, IntArray> {
        val seen = HashMap<List<Int>, Int>()
        val stored = ArrayList<Int>()
        val ids =
            IntArray(items.size / block) { i ->
                val chunk = items.slice(i * block until (i + 1) * block)
                seen.getOrPut(chunk) { seen.size.also { stored += chunk } }
            }
        return stored.toIntArray() to ids
    }

    private fun renderTables(props: IntArray): String {
        val distinct = LinkedHashMap<Int, Int>()
        val perCodePoint = IntArray(props.size) { distinct.getOrPut(props[it]) { distinct.size } }
        val (leaf, leafIds) = dedupe(perCodePoint, 1 shl LOW_BITS)
        val (mid, top) = dedupe(leafIds, 1 shl MID_BITS)
        val (major, minor, update) = UNICODE_VERSION.split('.')
        return buildString {
            appendLine(header("Three-stage property lookup for Props.kt, kept in its own file so regeneration never touches hand-written code."))
            appendLine()
            appendLine("package scull.unicode")
            appendLine()
            appendLine("internal const val UNICODE_MAJOR: Int = $major")
            appendLine("internal const val UNICODE_MINOR: Int = $minor")
            appendLine("internal const val UNICODE_UPDATE: Int = $update")
            appendLine("internal const val LOW_BITS: Int = $LOW_BITS")
            appendLine("internal const val MID_BITS: Int = $MID_BITS")
            appendLine()
            appendLine("// Each table is a string of UTF-16 units read with `code`: string constants are static data,")
            appendLine("// so the lookup needs no initialisation when the library loads.")
            table("PROPS", distinct.keys.toIntArray())
            table("TOP", top)
            table("MID", mid)
            table("LEAF", leaf)
        }
    }

    private fun StringBuilder.table(
        name: String,
        values: IntArray,
    ) {
        require(values.all { it in 0 until SURROGATE_FIRST }) { "$name holds a value that is not a single UTF-16 unit" }
        appendLine()
        appendLine("internal const val $name: String =")
        val lines = values.toList().chunked(CHARS_PER_LINE)
        lines.forEachIndexed { i, line ->
            val indent = if (i == 0) "    " else "        "
            val chars = line.joinToString("") { "\\u%04x".format(it) }
            appendLine("$indent\"$chars\"${if (i < lines.size - 1) " +" else ""}")
        }
    }

    private fun renderCases(text: String): String =
        buildString {
            appendLine(header("Every test line of GraphemeBreakTest.txt without its comment, for GraphemeBreakTest."))
            appendLine()
            appendLine("package scull.unicode")
            appendLine()
            appendLine("internal const val GRAPHEME_BREAK_CASES: String =")
            appendLine("    \"\"\"")
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach(::appendLine)
            appendLine("\"\"\"")
        }

    private fun header(purpose: String): String =
        """
        // @generated by `./gradlew :scull-unicode:generateUcd` from the Unicode $UNICODE_VERSION data files; do not edit.
        // $purpose
        // Derived from the Unicode Character Database, (c) Unicode, Inc., under the Unicode License v3
        // (https://www.unicode.org/license.txt).
        """.trimIndent()
}

private const val HEX = 16
private const val HTTP_OK = 200
private const val SURROGATE_FIRST = 0xD800
