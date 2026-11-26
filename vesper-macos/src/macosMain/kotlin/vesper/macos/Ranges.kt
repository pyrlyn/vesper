package vesper.macos

import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSMarkedClauseSegmentAttributeName
import platform.AppKit.NSUnderlineStyleAttributeName
import platform.AppKit.NSUnderlineStyleThick
import platform.Foundation.NSAttributedString
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNotFound
import platform.Foundation.NSNumber
import platform.Foundation.NSRange
import platform.Foundation.enumerateAttributesInRange
import platform.Foundation.length
import vesper.term.TextRange

// Between AppKit's NSRange, whose NSNotFound location means "none", and the core's offsets.

/** AppKit's "no range". */
internal val NO_RANGE: CValue<NSRange> get() = NSMakeRange(NSNotFound.toULong(), 0u)

internal fun TextRange.toNSRange(): CValue<NSRange> = NSMakeRange(start.toULong(), length.toULong())

/** The range as offsets; null for NSNotFound or a range past what an Int holds. */
internal fun CValue<NSRange>.toTextRange(): TextRange? =
    useContents {
        if (location >= Int.MAX_VALUE.toULong() || length > Int.MAX_VALUE.toULong() - location) return null
        TextRange(location.toInt(), (location + length).toInt())
    }

/** The text AppKit hands a text input client: an NSString (bridged) or an NSAttributedString. */
internal fun plainText(string: Any?): String =
    when (string) {
        is NSAttributedString -> string.string
        is String -> string
        else -> ""
    }

/** The runs of [string]'s attributes that say what an input method's clauses are. */
internal fun markedRuns(string: Any?): List<MarkedRun> {
    if (string !is NSAttributedString) return emptyList()
    val runs = ArrayList<MarkedRun>()
    string.enumerateAttributesInRange(NSMakeRange(0u, string.length), 0u) { attributes, range, _ ->
        val style = (attributes?.get(NSUnderlineStyleAttributeName) as? NSNumber)?.longValue ?: 0L
        val tagged = attributes?.get(NSMarkedClauseSegmentAttributeName) != null
        range.toTextRange()?.let { runs += MarkedRun(it.start, it.end, style and NSUnderlineStyleThick != 0L, tagged) }
    }
    return runs
}
