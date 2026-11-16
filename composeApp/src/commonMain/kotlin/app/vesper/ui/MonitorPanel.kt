package app.vesper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.app.MonitorTab
import app.vesper.app.PortInfo
import app.vesper.app.Tab
import app.vesper.terminal.BlockStatus
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.Segmented
import app.vesper.ui.components.StatusDot
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.terminal.StatusIcon
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatClock
import app.vesper.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun MonitorPanel(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(Modifier.fillMaxHeight()) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
        Column(Modifier.width(330.dp).fillMaxHeight().padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                VText("Monitor", type.title, c.textPrimary, Modifier.weight(1f))
                Segmented(MonitorTab.entries, state.monitorTab, { it.name }, { state.monitorTab = it })
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (state.monitorTab) {
                    MonitorTab.System -> SystemTab(state)
                    MonitorTab.Ports -> PortsTab(state)
                    MonitorTab.Logs -> LogsTab(state)
                }
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Vesper.colors
    Column(
        modifier.fillMaxWidth().background(c.bgPanel, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(14.dp),
        content = content,
    )
}

private fun gb(bytes: Long): String = ((bytes / 1e9) * 10).roundToInt().div(10.0).toString()

fun humanBytes(b: Double): String = when {
    b >= 1e9 -> "${((b / 1e9) * 10).roundToInt() / 10.0} GB"
    b >= 1e6 -> "${((b / 1e6) * 10).roundToInt() / 10.0} MB"
    b >= 1e3 -> "${(b / 1e3).roundToInt()} KB"
    else -> "${b.roundToInt()} B"
}

private fun shortBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "${((b / 1e9) * 10).roundToInt() / 10.0}G"
    b >= 1_000_000 -> "${(b / 1e6).roundToInt()}M"
    else -> "${(b / 1e3).roundToInt()}K"
}

@Composable
private fun SystemTab(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val snap by state.services.monitor.snapshot.collectAsState()

    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Overline("CPU", Modifier.weight(1f).padding(bottom = 3.dp))
            VText(snap.cpuTotal.roundToInt().toString(), type.metric, c.textPrimary)
            VText(" %  ·  ${snap.coreCount} cores", type.meta, c.textMuted, Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(12.dp))
        val bars = snap.cpuHistory.takeLast(40)
        val accent = c.accentStrong
        val track = c.bgActive
        Canvas(Modifier.fillMaxWidth().height(38.dp)) {
            val n = 40
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val offset = n - bars.size
            // Slots without a sample yet show as low stubs, so the chart reads as a chart from launch.
            val stub = 0.12f * size.height
            for (i in 0 until offset) {
                drawRoundRect(track, Offset(i * (w + gap), size.height - stub), Size(w, stub), CornerRadius(1.5.dp.toPx()))
            }
            bars.forEachIndexed { i, v ->
                val h = (v / 100.0).toFloat().coerceIn(0.04f, 1f) * size.height
                val x = (i + offset) * (w + gap)
                val alpha = 0.45f + 0.55f * (i + offset) / n
                drawRoundRect(accent.copy(alpha = alpha), Offset(x, size.height - h), Size(w, h), CornerRadius(1.5.dp.toPx()))
            }
        }
        if (snap.perCore.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val cores = snap.perCore.take(8)
            cores.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEachIndexed { col, v -> CoreBar("c${row * 2 + col}", v, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Overline("Memory", Modifier.weight(1f).padding(bottom = 3.dp))
            VText(gb(snap.memUsed), type.metric, c.textPrimary)
            VText(" / ${gb(snap.memTotal).substringBefore(".0")} GB", type.meta, c.textMuted, Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(12.dp))
        val total = snap.memTotal.coerceAtLeast(1).toFloat()
        val used = c.accentStrong
        val cache = c.info
        val swap = c.magenta
        val track = c.bgActive
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val r = CornerRadius(3.dp.toPx())
            drawRoundRect(track, cornerRadius = r)
            var x = 0f
            val gap = 2.dp.toPx()
            for ((value, color) in listOf(snap.memUsed to used, snap.memCached to cache, snap.swapUsed to swap)) {
                val w = (value / total * size.width).coerceAtMost(size.width - x)
                if (w <= 0f) continue
                drawRoundRect(color, Offset(x, 0f), Size(w, size.height), r)
                x += w + gap
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("used", used)
            Legend("cache", cache)
            Legend("swap", swap)
        }
    }

    Card {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Overline("Processes", Modifier.weight(1f))
            VText("sort: cpu", type.meta, c.textMuted)
        }
        ProcessRow("PID", "NAME", "CPU", "MEM", header = true)
        snap.processes.take(8).forEach { p ->
            ProcessRow(p.pid.toString(), p.name, "${(p.cpu * 10).roundToInt() / 10.0}%", shortBytes(p.memBytes), onKill = { state.services.monitor.kill(p.pid) })
        }
    }

    Card {
        Overline("Network")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VText("↓ ${humanBytes(snap.netDownBps)}/s   ↑ ${humanBytes(snap.netUpBps)}/s", type.codeSmall, c.textPrimary, Modifier.weight(1f))
            val hist = snap.netHistory.takeLast(30)
            val line = c.success
            Canvas(Modifier.width(96.dp).height(22.dp)) {
                if (hist.size < 2) return@Canvas
                val max = hist.max().coerceAtLeast(1.0)
                val step = size.width / (hist.size - 1)
                val path = Path()
                hist.forEachIndexed { i, v ->
                    val y = size.height - (v / max).toFloat() * size.height
                    if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
                }
                drawPath(path, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun CoreBar(label: String, value: Double, modifier: Modifier) {
    val c = Vesper.colors
    val type = Vesper.type
    val color = if (value > 80) c.warning else c.accentStrong
    val track = c.bgActive
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VText(label, type.meta, c.textMuted)
        Canvas(Modifier.weight(1f).height(4.dp)) {
            val r = CornerRadius(2.dp.toPx())
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(color, size = Size(size.width * (value / 100).toFloat().coerceIn(0f, 1f), size.height), cornerRadius = r)
        }
        VText(value.roundToInt().toString().padStart(2), type.meta, c.textSecondary)
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, app.vesper.ui.components.RadiusXs))
        VText(label, Vesper.type.caption, Vesper.colors.textSecondary)
    }
}

@Composable
private fun ProcessRow(pid: String, name: String, cpu: String, mem: String, header: Boolean = false, onKill: (() -> Unit)? = null) {
    val c = Vesper.colors
    val type = Vesper.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val style = if (header) type.meta else type.codeSmall
    val color = if (header) c.textMuted else c.textSecondary
    Row(Modifier.fillMaxWidth().hoverable(source).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        VText(pid, style, color, Modifier.width(56.dp))
        VText(name, style, if (header) color else c.textPrimary, Modifier.weight(1f))
        if (hovered && onKill != null) {
            Box(Modifier.clickable(onClick = onKill).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp)) {
                VIcon(Icons.X, 11.dp, c.danger)
            }
        }
        VText(cpu, style, color, Modifier.width(52.dp), align = androidx.compose.ui.text.style.TextAlign.End)
        VText(mem, style, color, Modifier.width(44.dp), align = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun PortsTab(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    var ports by remember { mutableStateOf<List<PortInfo>?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        while (true) {
            ports = withContext(Dispatchers.Default) { runCatching { state.services.monitor.listeningPorts() }.getOrDefault(emptyList()) }
            delay(4000)
        }
    }
    Card {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Overline("Listening ports", Modifier.weight(1f))
            IconButton(Icons.Refresh, { tick++ }, size = 12.dp, padding = 4.dp)
        }
        val list = ports
        when {
            list == null -> VText("Scanning…", type.caption, c.textMuted)
            list.isEmpty() -> VText("Nothing is listening on TCP right now.", type.caption, c.textMuted, maxLines = 2)
            else -> list.forEach { p -> PortRow(state, p) }
        }
    }
}

@Composable
private fun PortRow(state: AppState, p: PortInfo) {
    val c = Vesper.colors
    val type = Vesper.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().hoverable(source).padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c.success, 6.dp)
        Box(Modifier.clickable { state.services.openUrl("http://localhost:${p.port}") }.pointerHoverIcon(PointerIcon.Hand)) {
            VText(":${p.port}", type.codeSmall, c.accentText)
        }
        Column(Modifier.weight(1f)) {
            VText(p.process, type.codeSmall, c.textPrimary)
            VText("pid ${p.pid} · ${p.address}", type.meta, c.textMuted)
        }
        if (hovered) {
            IconButton(Icons.Globe, { state.services.openUrl("http://localhost:${p.port}") }, size = 12.dp, padding = 4.dp)
            IconButton(Icons.X, { state.services.monitor.kill(p.pid) }, size = 12.dp, padding = 4.dp, tint = c.danger)
        }
    }
}

@Composable
private fun LogsTab(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val panes = state.tabs.filterIsInstance<Tab.Terminal>().flatMap { it.root.panes() }
    panes.forEach {
        @Suppress("UNUSED_VARIABLE")
        val rev = it.session.revision
    }
    val entries = panes.flatMap { p -> p.session.blocksSnapshot().filter { it.command.isNotEmpty() }.map { p to it } }
        .sortedByDescending { it.second.finishedAt ?: Long.MAX_VALUE }
        .take(60)
    Card {
        Overline("Activity", Modifier.padding(bottom = 8.dp))
        if (entries.isEmpty()) VText("Commands you run show up here.", type.caption, c.textMuted)
        entries.forEach { (_, b) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (b.status == BlockStatus.Running) StatusDot(c.accent) else StatusIcon(b.status)
                }
                Column(Modifier.weight(1f)) {
                    VText(b.command, type.codeSmall, c.textPrimary)
                    val tail = when (b.status) {
                        BlockStatus.Running -> "running"
                        BlockStatus.Failed -> "exit ${b.exitCode} · ${formatDuration(b.durationMs)}"
                        BlockStatus.Interrupted -> "interrupted · ${formatDuration(b.durationMs)}"
                        BlockStatus.Success -> formatDuration(b.durationMs)
                    }
                    VText("${formatClock(b.startedAt)} · ${state.services.displayPath(b.cwd)} · $tail", type.meta, c.textMuted)
                }
            }
        }
    }
}
