package app.vesper.ui.terminal

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import app.vesper.ui.components.MenuDivider
import app.vesper.ui.components.MenuItem
import app.vesper.ui.components.MenuPopup
import app.vesper.terminal.FindMatch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import app.vesper.terminal.Block
import app.vesper.terminal.BlockStatus
import app.vesper.terminal.TerminalSession
import app.vesper.ui.components.Badge
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.Tone
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatClock
import app.vesper.util.formatDuration
import app.vesper.util.formatElapsed
import app.vesper.util.nowMillis
import kotlinx.coroutines.delay

/** Horizontal geometry of the blocks list, shared with the column computation. */
object BlockMetrics {
    val ListPaddingH = 18.dp
    val OutputStart = 42.dp
    val OutputEnd = 14.dp
    val Gap = 12.dp
}

private enum class Seg { Top, Middle, Bottom }

/** One row of the flattened list: a block header, an output line, or a block footer. */
private class Row(val block: Block, val line: Int) // line: -1 header, -2 footer

interface BlockActions {
    fun stop(block: Block)
    fun rerun(block: Block)
    fun copy(text: String)
    fun remove(block: Block)
}

/** Find-in-output matches to highlight, and which one is current (scrolled into view). */
class FindHighlight(val matches: List<FindMatch>, val current: Int)

private fun lineKey(blockId: Long, line: Int): Long = (blockId shl 24) or line.toLong()

/**
 * Commands and their output as cards. Uses a reversed lazy list so the view sticks to the
 * newest output unless the user scrolled up; each block is split into per-line items so
 * huge outputs stay cheap.
 */
@Composable
fun BlocksView(
    session: TerminalSession,
    palette: TermPalette,
    listState: LazyListState,
    actions: BlockActions,
    modifier: Modifier = Modifier,
    find: FindHighlight? = null,
) {
    val c = Vesper.colors
    val type = Vesper.type
    val codeStyle = type.code.copy(color = palette.defaultFg)
    val cell = rememberCellSize(codeStyle)
    @Suppress("UNUSED_VARIABLE")
    val rev = session.revision

    val blocks = session.blocksSnapshot()
    // Line counts are read under the session lock so they match what the reader thread wrote.
    val counts = synchronized(session.lock) { IntArray(blocks.size) { blocks[it].terminal.mainLineCount() } }
    val starts = IntArray(blocks.size + 1)
    for (i in blocks.indices) starts[i + 1] = starts[i] + counts[i] + 2
    val total = starts[blocks.size]

    fun rowAt(reversedIndex: Int): Row {
        val index = total - 1 - reversedIndex
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= index) lo = mid else hi = mid - 1
        }
        val local = index - starts[lo]
        val b = blocks[lo]
        return when (local) {
            0 -> Row(b, -1)
            counts[lo] + 1 -> Row(b, -2)
            else -> Row(b, local - 1)
        }
    }

    // Matches per output line: column ranges, flagged when it is the current one.
    val highlights = remember(find) {
        val map = HashMap<Long, MutableList<Pair<IntRange, Boolean>>>()
        find?.matches?.forEachIndexed { i, m ->
            map.getOrPut(lineKey(m.blockId, m.line)) { ArrayList() } += (m.start until m.end) to (i == find.current)
        }
        map
    }
    val target = find?.matches?.getOrNull(find.current)
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        val bi = blocks.indexOfFirst { it.id == target.blockId }
        if (bi < 0) return@LaunchedEffect
        val item = total - 1 - (starts[bi] + 1 + target.line)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == item }) listState.scrollToItem((item - 4).coerceAtLeast(0))
    }
    val matchBg = c.warning.copy(alpha = 0.28f)
    val currentBg = c.warning.copy(alpha = 0.7f)

    SelectionContainer(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = BlockMetrics.ListPaddingH, vertical = 14.dp),
        ) {
            items(
                count = total,
                key = { i ->
                    val r = rowAt(i)
                    val seg = when (r.line) {
                        -1 -> 0L
                        -2 -> 0xFFFFFFL
                        else -> r.line + 1L
                    }
                    (r.block.id shl 24) or seg
                },
                contentType = { i ->
                    when (rowAt(i).line) {
                        -1 -> 0
                        -2 -> 2
                        else -> 1
                    }
                },
            ) { i ->
                val r = rowAt(i)
                val running = r.block.isRunning
                val border = if (running) c.accentStrong else c.borderSubtle
                val untitled = r.block.command.isEmpty()
                val bg = if (untitled) Color.Transparent else c.bgSurface
                val edge = if (untitled) Color.Transparent else border
                when (r.line) {
                    -1 -> Box(Modifier.fillMaxWidth().cardSegment(Seg.Top, bg, edge, running)) {
                        if (untitled) Spacer(Modifier.height(4.dp)) else BlockHeader(r.block, actions, session.revision)
                    }
                    -2 -> Box(Modifier.fillMaxWidth().padding(bottom = BlockMetrics.Gap)) {
                        Spacer(Modifier.fillMaxWidth().height(if (untitled) 2.dp else 14.dp).cardSegment(Seg.Bottom, bg, edge, running))
                    }
                    else -> {
                        val render = synchronized(session.lock) {
                            val t = r.block.terminal
                            if (r.line < t.mainLineCount()) renderLine(t.mainLine(r.line), palette) else null
                        }
                        val marks = highlights[lineKey(r.block.id, r.line)]
                        Box(
                            Modifier.fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { cell.height.toDp() }.coerceAtLeast(20.dp))
                                .cardSegment(Seg.Middle, bg, edge, running)
                                .padding(start = BlockMetrics.OutputStart, end = BlockMetrics.OutputEnd)
                                .then(
                                    if (marks == null) Modifier else Modifier.drawBehind {
                                        for ((cols, current) in marks) {
                                            drawRect(
                                                if (current) currentBg else matchBg,
                                                Offset(cols.first * cell.width, 0f),
                                                Size((cols.last + 1 - cols.first) * cell.width, size.height),
                                            )
                                        }
                                    },
                                ),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (render != null) TermRow(render, codeStyle, cell.width)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.cardSegment(seg: Seg, bg: Color, border: Color, glow: Boolean): Modifier = drawBehind {
    if (bg == Color.Transparent && border == Color.Transparent) return@drawBehind
    val r = 10.dp.toPx()
    val sw = 1.dp.toPx()
    if (glow) drawGlow(seg, r, border)
    when (seg) {
        Seg.Middle -> {
            drawRect(bg)
            drawRect(border, Offset.Zero, Size(sw, size.height))
            drawRect(border, Offset(size.width - sw, 0f), Size(sw, size.height))
        }
        Seg.Top, Seg.Bottom -> {
            val top = seg == Seg.Top
            val rr = if (top) {
                RoundRect(0f, 0f, size.width, size.height + r, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r))
            } else {
                RoundRect(0f, -r, size.width, size.height, bottomLeftCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r))
            }
            val clip = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
            val fill = Path().apply { addRoundRect(rr) }
            val inner = Path().apply {
                addRoundRect(
                    RoundRect(
                        sw, if (top) sw else -r, size.width - sw, if (top) size.height + r else size.height - sw,
                        CornerRadius((r - sw).coerceAtLeast(0f)),
                    ),
                )
            }
            clipPath(clip) {
                drawPath(fill, border)
                drawPath(inner, bg)
            }
        }
    }
}

private fun DrawScope.drawGlow(seg: Seg, r: Float, color: Color) {
    val ring = 4.dp.toPx()
    val glow = color.copy(alpha = 0.12f)
    when (seg) {
        Seg.Middle -> {
            drawRect(glow, Offset(-ring, 0f), Size(ring, size.height))
            drawRect(glow, Offset(size.width, 0f), Size(ring, size.height))
        }
        Seg.Top, Seg.Bottom -> {
            val top = seg == Seg.Top
            val path = Path().apply {
                addRoundRect(
                    if (top) {
                        RoundRect(-ring / 2, -ring / 2, size.width + ring / 2, size.height + r + ring, CornerRadius(r + ring / 2))
                    } else {
                        RoundRect(-ring / 2, -r - ring, size.width + ring / 2, size.height + ring / 2, CornerRadius(r + ring / 2))
                    },
                )
            }
            val clip = Path().apply {
                addRect(
                    if (top) androidx.compose.ui.geometry.Rect(-ring, -ring, size.width + ring, size.height)
                    else androidx.compose.ui.geometry.Rect(-ring, 0f, size.width + ring, size.height + ring),
                )
            }
            clipPath(clip) { drawPath(path, glow, style = Stroke(ring)) }
        }
    }
}

@Composable
private fun BlockHeader(block: Block, actions: BlockActions, revision: Long) {
    val c = Vesper.colors
    val type = Vesper.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().hoverable(source)
            .pointerInput(block.id) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                            e.changes.forEach { it.consume() }
                            menu = true
                        }
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(block.status)
        VText("›", type.command, c.textMuted)
        VText(block.command, type.command, c.textPrimary, Modifier.weight(1f))
        if ((hovered || menu) && !block.isRunning) {
            IconButton(Icons.Copy, { actions.copy(outputOf(block)) }, size = 13.dp, padding = 4.dp)
            IconButton(Icons.RotateCcw, { actions.rerun(block) }, size = 13.dp, padding = 4.dp)
        }
        if (hovered || menu) {
            Box {
                IconButton(Icons.More, { menu = true }, size = 13.dp, padding = 4.dp)
                // The popup is a separate layout tree; selecting its text inside the SelectionContainer crashes.
                if (menu) DisableSelection { BlockMenu(block, actions) { menu = false } }
            }
        }
        when (block.status) {
            BlockStatus.Running -> RunningMeta(block, actions)
            BlockStatus.Failed -> Badge("exit ${block.exitCode ?: "?"}", Tone.Danger)
            BlockStatus.Interrupted -> Badge("interrupted", Tone.Warning)
            BlockStatus.Success -> Unit
        }
        if (!block.isRunning) VText(formatDuration(block.durationMs), type.meta, c.textMuted)
        VText(formatClock(block.startedAt), type.meta, c.textMuted)
    }
}

private fun outputOf(block: Block): String = block.terminal.let { t -> synchronized(t.lock) { t.plainText() } }.trimEnd()

@Composable
private fun BlockMenu(block: Block, actions: BlockActions, dismiss: () -> Unit) {
    MenuPopup(dismiss) {
        MenuItem("Copy command", { dismiss(); actions.copy(block.command) }, Icons.Copy)
        MenuItem("Copy output", { dismiss(); actions.copy(outputOf(block)) }, Icons.Copy)
        if (!block.isRunning) MenuItem("Rerun", { dismiss(); actions.rerun(block) }, Icons.RotateCcw)
        if (block.isRunning) MenuItem("Stop", { dismiss(); actions.stop(block) }, Icons.Stop)
        if (!block.isRunning) {
            MenuDivider()
            MenuItem("Delete block", { dismiss(); actions.remove(block) }, Icons.Trash, danger = true)
        }
    }
}

@Composable
private fun RunningMeta(block: Block, actions: BlockActions) {
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(block.id) {
        while (true) {
            delay(1000)
            now = nowMillis()
        }
    }
    Badge("running · ${formatElapsed(now - block.startedAt)}", Tone.Accent)
    VButton("Stop", { actions.stop(block) }, icon = Icons.Stop, kind = ButtonKind.Secondary)
}

@Composable
fun StatusIcon(status: BlockStatus) {
    val c = Vesper.colors
    when (status) {
        BlockStatus.Success -> VIcon(Icons.CheckCircle, 18.dp, c.success)
        BlockStatus.Failed -> VIcon(Icons.XCircle, 18.dp, c.danger)
        BlockStatus.Interrupted -> VIcon(Icons.XCircle, 18.dp, c.warning)
        BlockStatus.Running -> {
            val t = rememberInfiniteTransition()
            val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
            VIcon(Icons.Loader, 18.dp, c.accent, Modifier.rotate(angle))
        }
    }
}
