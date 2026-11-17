package app.vesper.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.app.HistoryEntry
import app.vesper.app.Tab
import app.vesper.app.historyInsights
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusSm
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.SearchField
import app.vesper.ui.components.StatusDot
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatClock
import app.vesper.util.formatDay
import app.vesper.util.formatDuration
import app.vesper.util.localDay
import app.vesper.util.nowMillis

private const val WeekMs = 7L * 24 * 60 * 60 * 1000

/** History: every command Vesper ran, with where, how long and how it ended. */
@Composable
fun HistoryScreen(state: AppState, tab: Tab.History) {
    val c = Vesper.colors
    @Suppress("UNUSED_VARIABLE")
    val size = state.history.size
    val now = remember(size) { nowMillis() }
    val results = remember(size, tab.query, tab.thisDirectory, tab.failedOnly, tab.lastWeek) {
        state.history.asReversed().filter { e ->
            (tab.query.isBlank() || e.command.contains(tab.query, ignoreCase = true)) &&
                (tab.thisDirectory == null || e.cwd == tab.thisDirectory) &&
                (!tab.failedOnly || e.failed) &&
                (!tab.lastWeek || e.at >= now - WeekMs)
        }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Header(state, results)
            Spacer(Modifier.height(16.dp))
            Filters(state, tab, results.size)
            Spacer(Modifier.height(14.dp))
            Table(state, tab, results, now, Modifier.weight(1f))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
        Insights(state, now)
    }
}

@Composable
private fun Header(state: AppState, results: List<HistoryEntry>) {
    val c = Vesper.colors
    val type = Vesper.type
    var confirmClear by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VIcon(Icons.History, 20.dp, c.textPrimary)
        VText("History", type.display, c.textPrimary, Modifier.weight(1f))
        exported?.let { VText("Saved to ${state.services.displayPath(it)}", type.meta, c.textMuted) }
        VButton("Export", {
            val text = buildString {
                appendLine("time\tdirectory\texit\tduration_ms\tcommand")
                for (e in results.asReversed()) {
                    append(formatDay(e.at)).append(' ').append(formatClock(e.at)).append('\t')
                    append(e.cwd).append('\t').append(e.exitCode?.toString() ?: "").append('\t')
                    append(e.durationMs).append('\t').appendLine(e.command.replace('\n', ' ').replace('\t', ' '))
                }
            }
            exported = state.services.exportFile("vesper-history.tsv", text)?.also { state.services.revealInFileManager(it) }
        }, icon = Icons.Download, kind = ButtonKind.Ghost, enabled = results.isNotEmpty())
        if (confirmClear) {
            VText("Clear ${state.history.size} commands?", type.bodySmall, c.textSecondary)
            VButton("Clear", { state.clearHistory(); confirmClear = false }, icon = Icons.Trash, kind = ButtonKind.Danger)
            VButton("Cancel", { confirmClear = false }, kind = ButtonKind.Ghost)
        } else {
            VButton("Clear…", { confirmClear = true }, icon = Icons.Trash, enabled = state.history.isNotEmpty())
        }
    }
}

@Composable
private fun Filters(state: AppState, tab: Tab.History, count: Int) {
    val c = Vesper.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchField(tab.query, { tab.query = it }, "Search commands", Modifier.weight(1f).widthIn(max = 360.dp), focusRequester = focus) {
            VText("$count", Vesper.type.meta, c.textMuted, Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.width(4.dp))
        val dir = state.tabs.filterIsInstance<Tab.Terminal>().lastOrNull()?.focused?.session?.cwd
        FilterChip("All directories", tab.thisDirectory == null) { tab.thisDirectory = null }
        if (dir != null) FilterChip("This directory", tab.thisDirectory != null) { tab.thisDirectory = if (tab.thisDirectory == null) dir else null }
        FilterChip("Failed only", tab.failedOnly, danger = true) { tab.failedOnly = !tab.failedOnly }
        FilterChip("Last 7 days", tab.lastWeek) { tab.lastWeek = !tab.lastWeek }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = Vesper.colors
    val (bg, fg) = when {
        !active -> c.bgActive to c.textSecondary
        danger -> c.dangerSoft to c.danger
        else -> c.accentSoft to c.accentText
    }
    Box(
        Modifier.background(bg, RadiusSm).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) { VText(label, Vesper.type.codeSmall, fg) }
}

private val WhereWidth = 170.dp
private val DurationWidth = 90.dp
private val WhenWidth = 64.dp

@Composable
private fun Table(state: AppState, tab: Tab.History, results: List<HistoryEntry>, now: Long, modifier: Modifier) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl)) {
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)) {
            Overline("Command", Modifier.weight(1f))
            Overline("Where", Modifier.width(WhereWidth))
            Overline("Duration", Modifier.width(DurationWidth))
            Box(Modifier.width(WhenWidth), contentAlignment = Alignment.CenterEnd) { Overline("When") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                val text = if (state.history.isEmpty()) "Commands you run in Vesper show up here with exit codes and durations." else "No commands match."
                VText(text, type.body, c.textMuted, align = TextAlign.Center)
            }
            return@Column
        }
        val today = localDay(now)
        val groups = remember(results) { results.groupBy { localDay(it.at) } }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            groups.forEach { (day, entries) ->
                item(key = "day-$day") {
                    val label = when (day) {
                        today -> "Today"
                        today - 1 -> "Yesterday"
                        else -> formatDay(entries.first().at)
                    }
                    Overline(label, Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                }
                items(entries, key = { "${it.at}-${it.command.hashCode()}-${it.cwd.hashCode()}" }) { e ->
                    HistoryRow(state, e, selected = tab.selected == e) { tab.selected = if (tab.selected == e) null else e }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(state: AppState, e: HistoryEntry, selected: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(Modifier.fillMaxWidth().background(if (selected) c.accentSoft else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(
                when {
                    e.exitCode == null -> c.textMuted
                    e.failed -> c.danger
                    else -> c.success
                },
            )
            Spacer(Modifier.width(15.dp))
            VText(e.command, type.codeSmall, c.textPrimary, Modifier.weight(1f).padding(end = 12.dp))
            Row(Modifier.width(WhereWidth), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VIcon(Icons.Folder, 13.dp, c.iconDefault)
                val path = state.services.displayPath(e.cwd)
                VText(path.substringAfterLast('/').ifEmpty { path }, type.meta, c.textSecondary)
            }
            val (duration, color) = if (e.failed) "exit ${e.exitCode}" to c.danger else formatDuration(e.durationMs) to c.textMuted
            VText(duration, type.meta, color, Modifier.width(DurationWidth))
            VText(formatClock(e.at, seconds = false), type.meta, c.textMuted, Modifier.width(WhenWidth), align = TextAlign.End)
        }
        if (selected) RowActions(state, e)
    }
}

@Composable
private fun RowActions(state: AppState, e: HistoryEntry) {
    val clipboard = LocalClipboardManager.current
    var saved by remember(e) { mutableStateOf(false) }
    Row(
        Modifier.padding(start = 36.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VButton("Run again", { state.runAgain(e) }, icon = Icons.Play)
        VButton("Insert into prompt", { state.insertIntoPrompt(e.command) }, icon = Icons.CornerDownLeft, kind = ButtonKind.Ghost)
        VButton("Copy", { clipboard.setText(AnnotatedString(e.command)) }, icon = Icons.Copy, kind = ButtonKind.Ghost)
        VButton(
            if (saved) "Saved to workflows" else "Save as workflow",
            { saved = state.saveAsWorkflow(e.command) || saved },
            icon = if (saved) Icons.Check else Icons.Sparkles,
            kind = ButtonKind.Accent,
            enabled = !saved,
        )
    }
}

@Composable
private fun Insights(state: AppState, now: Long) {
    val c = Vesper.colors
    val type = Vesper.type
    val insights = remember(state.history.size) { historyInsights(state.history, now) }
    Column(
        Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Overline("Insights · 7 days")
        StatCard("Commands", "%,d".format(insights.commands)) {
            insights.changePercent?.let { p ->
                VText("${if (p >= 0) "↑" else "↓"} ${kotlin.math.abs(p)}% vs last week", type.meta, if (p >= 0) c.success else c.textMuted)
            }
        }
        StatCard("Failed", insights.failed.toString()) {
            VText("${"%.1f".format(insights.failureRate)}% failure rate", type.meta, if (insights.failed > 0) c.danger else c.textMuted)
        }
        StatCard("Time in commands", formatTotal(insights.totalMs)) {
            insights.longest?.let { (cmd, avg) -> VText("longest: $cmd (${formatDuration(avg)} avg)", type.meta, c.textMuted) }
        }
        if (insights.top.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(14.dp)) {
                Overline("Top commands", Modifier.padding(bottom = 10.dp))
                insights.top.forEach { (cmd, n) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        VText(cmd, type.codeSmall, c.textPrimary, Modifier.weight(1f))
                        VText(n.toString(), type.meta, c.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, footer: @Composable () -> Unit) {
    val c = Vesper.colors
    Column(Modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(14.dp)) {
        Overline(title)
        Spacer(Modifier.height(6.dp))
        VText(value, Vesper.type.display.copy(fontFamily = Vesper.type.mono), c.textPrimary)
        Spacer(Modifier.height(6.dp))
        footer()
    }
}

private fun formatTotal(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${(ms / 1000) % 60}s"
        else -> formatDuration(ms)
    }
}
