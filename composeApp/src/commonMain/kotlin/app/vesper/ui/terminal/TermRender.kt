package app.vesper.ui.terminal

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import app.vesper.terminal.vt.Attr
import app.vesper.terminal.vt.TermLine
import app.vesper.terminal.vt.WIDE_TAIL
import app.vesper.terminal.vt.codePointToString
import app.vesper.ui.theme.VesperColors

/** Maps emulator colors to theme colors. */
@Immutable
class TermPalette(val colors: VesperColors) {
    val defaultFg: Color = colors.textCode
    val cursor: Color = colors.accentStrong
    private val table: Array<Color> = Array(256) { i ->
        when {
            i < 16 -> colors.ansi.getOrElse(i) { defaultFg }
            i < 232 -> {
                val n = i - 16
                fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
                Color(level(n / 36), level((n / 6) % 6), level(n % 6))
            }
            else -> (8 + (i - 232) * 10).let { Color(it, it, it) }
        }
    }

    /** Identity used to invalidate render caches when the theme changes. */
    val key: Long = (colors.textCode.value.toLong() * 31) xor colors.bgWindow.value.toLong() xor colors.ansi.hashCode().toLong()

    fun color(c: Int, fallback: Color): Color = when {
        c == 0 -> fallback
        c and Attr.RGB_FLAG != 0 -> Color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        else -> table.getOrElse(c - 1) { fallback }
    }
}

/** A background run in cell coordinates. */
class BgRun(val start: Int, val end: Int, val color: Color)

class LineRender(val text: AnnotatedString, val bgRuns: List<BgRun>)

private val EmptyRender = LineRender(AnnotatedString(""), emptyList())

/** Builds (or returns the cached) styled text for [line]. Call while holding the owning lock. */
fun renderLine(line: TermLine, palette: TermPalette, showHidden: Boolean = false): LineRender {
    val key = line.version.toLong() * 1_000_003L + palette.key
    (line.renderCache as? LineRender)?.let { if (line.renderCacheKey == key) return it }
    val n = line.contentLength()
    val result = if (n == 0) {
        val bgOnly = backgroundsOnly(line, palette)
        if (bgOnly.isEmpty()) EmptyRender else LineRender(AnnotatedString(""), bgOnly)
    } else {
        buildRender(line, n, palette, showHidden)
    }
    line.renderCache = result
    line.renderCacheKey = key
    return result
}

private fun backgroundsOnly(line: TermLine, palette: TermPalette): List<BgRun> {
    val runs = ArrayList<BgRun>(0)
    var i = 0
    while (i < line.size) {
        val bg = Attr.bg(line.attrs[i])
        val inverse = Attr.flags(line.attrs[i]) and Attr.INVERSE != 0
        if (bg == 0 && !inverse) {
            i++
            continue
        }
        val start = i
        val a = line.attrs[i]
        while (i < line.size && line.attrs[i] == a) i++
        val color = if (inverse) palette.color(Attr.fg(a), palette.defaultFg) else palette.color(bg, Color.Transparent)
        runs += BgRun(start, i, color)
    }
    return runs
}

private fun buildRender(line: TermLine, n: Int, palette: TermPalette, showHidden: Boolean): LineRender {
    val sb = AnnotatedString.Builder(n + 8)
    val runs = ArrayList<BgRun>(0)
    var col = 0
    val cps = line.cps
    val attrs = line.attrs
    // Background runs extend across the whole line (e.g. full-width status bars).
    var bgStart = -1
    var bgColor = Color.Transparent
    fun flushBg(end: Int) {
        if (bgStart >= 0 && bgColor != Color.Transparent) runs += BgRun(bgStart, end, bgColor)
        bgStart = -1
    }
    for (i in 0 until line.size) {
        val a = attrs[i]
        val flags = Attr.flags(a)
        val inverse = flags and Attr.INVERSE != 0
        val bg = if (inverse) palette.color(Attr.fg(a), palette.defaultFg) else palette.color(Attr.bg(a), Color.Transparent)
        if (bg != bgColor || bgStart < 0) {
            flushBg(i)
            bgStart = i
            bgColor = bg
        }
    }
    flushBg(line.size)

    var segStart = 0
    var segAttr = Long.MIN_VALUE
    fun pushStyle(a: Long, from: Int, to: Int) {
        if (from >= to) return
        val flags = Attr.flags(a)
        val inverse = flags and Attr.INVERSE != 0
        var fg = if (inverse) palette.color(Attr.bg(a), palette.colors.bgWindow) else palette.color(Attr.fg(a), palette.defaultFg)
        // Bold text in one of the 8 base colors uses the bright variant, like most terminals.
        val fgIdx = Attr.fg(a)
        if (!inverse && flags and Attr.BOLD != 0 && fgIdx in 1..8) fg = palette.color(fgIdx + 8, fg)
        if (flags and Attr.DIM != 0) fg = fg.copy(alpha = fg.alpha * 0.6f)
        if (flags and Attr.HIDDEN != 0 && !showHidden) fg = Color.Transparent
        val decoration = when {
            flags and Attr.UNDERLINE != 0 && flags and Attr.STRIKE != 0 -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            flags and Attr.UNDERLINE != 0 -> TextDecoration.Underline
            flags and Attr.STRIKE != 0 -> TextDecoration.LineThrough
            else -> null
        }
        if (fg == palette.defaultFg && flags and (Attr.BOLD or Attr.ITALIC) == 0 && decoration == null) return
        sb.addStyle(
            SpanStyle(
                color = fg,
                fontWeight = if (flags and Attr.BOLD != 0) FontWeight.Bold else null,
                fontStyle = if (flags and Attr.ITALIC != 0) FontStyle.Italic else null,
                textDecoration = decoration,
            ),
            from,
            to,
        )
    }
    while (col < n) {
        val cp = cps[col]
        val a = attrs[col]
        if (cp == WIDE_TAIL) {
            col++
            continue
        }
        if (a != segAttr) {
            pushStyle(segAttr, segStart, sb.length)
            segStart = sb.length
            segAttr = a
        }
        sb.append(if (cp == 0) " " else codePointToString(cp))
        col++
    }
    pushStyle(segAttr, segStart, sb.length)
    return LineRender(sb.toAnnotatedString(), runs)
}

/** Width of one monospace cell in pixels for [style]. */
@Composable
fun rememberCellSize(style: TextStyle): Size {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    return remember(style) {
        val sample = "M".repeat(64)
        val r = measurer.measure(sample, style, constraints = Constraints(), softWrap = false, maxLines = 1)
        Size(r.size.width / 64f, r.size.height.toFloat())
    }
}

/** One terminal row: backgrounds drawn behind, styled text on top. */
@Composable
fun TermRow(render: LineRender, style: TextStyle, cellWidth: Float, modifier: Modifier = Modifier) {
    val bg = render.bgRuns
    val m = if (bg.isEmpty()) modifier else modifier.drawBehind {
        for (run in bg) {
            drawRect(run.color, Offset(run.start * cellWidth, 0f), Size((run.end - run.start) * cellWidth, size.height))
        }
    }
    BasicText(render.text, m, style = style, softWrap = false, maxLines = 1)
}
