package app.vesper.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.vesper.terminal.TerminalSession
import app.vesper.terminal.vt.Terminal
import app.vesper.ui.theme.Vesper
import kotlin.math.abs

/**
 * Classic character grid for full-screen programs (vim, htop, less) and shells without
 * integration. Keys go straight to the PTY.
 */
@Composable
fun GridView(
    session: TerminalSession,
    term: Terminal,
    palette: TermPalette,
    focusRequester: FocusRequester,
    macOs: Boolean,
    modifier: Modifier = Modifier,
) {
    val codeStyle = Vesper.type.code.copy(color = palette.defaultFg)
    val cell = rememberCellSize(codeStyle)
    val density = LocalDensity.current
    @Suppress("UNUSED_VARIABLE")
    val rev = session.revision
    var focused by remember { mutableStateOf(false) }
    var scrollOffset by remember(term) { mutableIntStateOf(0) }
    var wheelAccum by remember { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onFocusChanged { focused = it.hasFocus }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { e ->
                val seq = KeyEncoder.encode(e, term.applicationCursorKeys, macOs) ?: return@onKeyEvent false
                scrollOffset = 0
                session.write(seq)
                true
            }
            .pointerInput(term) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Press -> focusRequester.requestFocus()
                            PointerEventType.Scroll -> {
                                val dy = event.changes.first().scrollDelta.y
                                wheelAccum += dy
                                val steps = wheelAccum.toInt()
                                if (steps == 0) continue
                                wheelAccum -= steps
                                val (alt, tracking, appCursor) = synchronized(session.lock) {
                                    Triple(term.isAltScreen, term.mouseTracking, term.applicationCursorKeys)
                                }
                                when {
                                    tracking != 0 -> {
                                        val pos = event.changes.first().position
                                        val col = (pos.x / cell.width).toInt() + 1
                                        val row = (pos.y / cell.height).toInt() + 1
                                        val btn = if (steps < 0) 64 else 65
                                        repeat(abs(steps).coerceAtMost(5)) { session.write("\u001b[<$btn;$col;${row}M") }
                                    }
                                    alt -> {
                                        val seq = if (steps < 0) (if (appCursor) "\u001bOA" else "\u001b[A") else (if (appCursor) "\u001bOB" else "\u001b[B")
                                        repeat(abs(steps) * 3) { session.write(seq) }
                                    }
                                    else -> {
                                        val max = synchronized(session.lock) { term.scrollback.size }
                                        scrollOffset = (scrollOffset - steps * 3).coerceIn(0, max)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val cols = with(density) { (maxWidth.toPx() / cell.width).toInt() }.coerceAtLeast(10)
        val rows = with(density) { (maxHeight.toPx() / cell.height).toInt() }.coerceAtLeast(3)
        LaunchedEffect(cols, rows) { session.resize(cols, rows) }

        val snapshot = synchronized(session.lock) {
            val n = term.rows
            val sb = term.scrollback
            val off = if (term.isAltScreen) 0 else scrollOffset.coerceAtMost(sb.size)
            val lines = List(n) { r ->
                val idx = r - off
                val line = if (idx < 0) sb.elementAtOrNull(sb.size + idx) else term.screenLine(idx)
                line?.let { renderLine(it, palette) }
            }
            GridSnapshot(lines, term.cursorX, term.cursorY + off, term.cursorVisible && off == 0)
        }
        val rowHeight = with(density) { cell.height.toDp() }
        SelectionContainer {
            Column {
                snapshot.lines.forEach { render ->
                    Box(Modifier.height(rowHeight)) { if (render != null) TermRow(render, codeStyle, cell.width) }
                }
            }
        }
        if (snapshot.cursorVisible) {
            val cursorColor = palette.cursor
            Canvas(Modifier.fillMaxSize()) {
                val x = snapshot.cursorX.coerceAtMost(cols - 1) * cell.width
                val y = snapshot.cursorY * cell.height
                if (focused) {
                    drawRect(cursorColor.copy(alpha = 0.55f), Offset(x, y), Size(cell.width, cell.height))
                } else {
                    drawRect(cursorColor, Offset(x + 0.5f, y + 0.5f), Size(cell.width - 1f, cell.height - 1f), style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
    LaunchedEffect(term) { focusRequester.requestFocus() }
}

private class GridSnapshot(val lines: List<LineRender?>, val cursorX: Int, val cursorY: Int, val cursorVisible: Boolean)
