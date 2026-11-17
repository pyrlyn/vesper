package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.app.AppState
import app.vesper.app.SettingsSection
import app.vesper.platform.HostOs
import app.vesper.ui.components.Kbd
import app.vesper.ui.components.KeyHint
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusMd
import app.vesper.ui.components.RadiusSm
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.components.popover
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.ThemeId
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatAgo
import app.vesper.util.nowMillis

enum class PaletteGroup(val title: String) { Hosts("Hosts"), Workflows("Workflows"), Commands("Commands"), History("History"), Directories("Directories") }

class PaletteItem(
    val group: PaletteGroup,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val shortcut: String? = null,
    /** Runs the item; `split` is true for ⌘⏎ (open in a split pane). */
    val action: (split: Boolean) -> Unit,
)

private fun buildItems(state: AppState, ui: UiActions): List<PaletteItem> {
    val os = state.services.os
    val mod = os.mod
    val shift = os.shift
    val items = ArrayList<PaletteItem>()
    for (h in state.allHosts()) {
        items += PaletteItem(PaletteGroup.Hosts, "Connect to ${h.alias}", "ssh ${h.address}" + (if (h.tag.isNotEmpty()) " · ${h.tag}" else ""), Icons.Server) { split ->
            if (split) state.split(true, "ssh ${h.alias}") else state.connect(h)
        }
    }
    for (w in state.settings.workflows) {
        val steps = "${w.steps.size} step${if (w.steps.size == 1) "" else "s"}" + (if (w.requiresApproval) " · requires approval" else "")
        items += PaletteItem(PaletteGroup.Workflows, w.name, w.description.ifEmpty { steps }, Icons.Play) { split ->
            val cmd = w.steps.joinToString(" && ")
            if (split) state.split(true, cmd) else state.runInFocused(cmd)
        }
    }
    fun cmd(title: String, subtitle: String, icon: ImageVector, shortcut: String? = null, run: () -> Unit) {
        items += PaletteItem(PaletteGroup.Commands, title, subtitle, icon, shortcut) { run() }
    }
    cmd("New tab", "Open a terminal in the current directory", Icons.Plus, "${mod}T") { state.newTerminalTab() }
    cmd("Split right", "Open a pane beside the focused one", Icons.Columns, "$mod\\") { state.split(true) }
    cmd("Split down", "Open a pane below the focused one", Icons.Rows, "$mod$shift\\") { state.split(false) }
    cmd("Zoom pane", "Toggle full size for the focused pane", Icons.Maximize, "${mod}⏎") { state.focusedPane?.let(state::toggleZoom) }
    cmd("Clear blocks", "Remove finished blocks from this session", Icons.Trash) { state.focusedPane?.session?.clearBlocks() }
    cmd("Interrupt", "Send Ctrl+C to the running command", Icons.Stop) { state.focusedPane?.session?.interrupt() }
    cmd("Toggle monitor", "Show or hide the system monitor", Icons.PanelRight, "${mod}J") {
        state.updateSettings { it.copy(showMonitor = !it.showMonitor) }
    }
    cmd("Toggle sidebar", "Show or hide sessions, workflows and history", Icons.PanelRight, "${mod}B") {
        state.updateSettings { it.copy(showSidebar = !it.showSidebar) }
    }
    cmd("Hosts", "Manage SSH hosts", Icons.Server, "$mod${shift}H") { state.openHosts() }
    cmd("History", "Search finished commands, failures and durations", Icons.History, "${mod}Y") { state.openHistory() }
    cmd("Find in output", "Search the focused pane's blocks", Icons.Search, "${mod}F") {
        state.focusedPane?.find?.let { it.open = true; it.focusRequests++ }
    }
    cmd("Settings", "Theme, fonts, shell and more", Icons.Settings, "$mod,") { state.openSettings() }
    cmd("Keyboard shortcuts", "All key bindings", Icons.Keyboard) { state.openSettings(SettingsSection.Keybindings) }
    for (t in ThemeId.entries) {
        cmd("Theme: ${t.title}", "Switch color theme", Icons.Palette) { state.updateSettings { it.copy(theme = t, followSystem = false) } }
    }
    cmd("Reveal current directory", "Open in ${if (os == HostOs.MacOS) "Finder" else "the file manager"}", Icons.Folder) {
        state.focusedPane?.let { state.services.revealInFileManager(it.session.cwd) }
    }
    cmd("Copy current directory", "Copy the path to the clipboard", Icons.Copy) { state.focusedPane?.let { ui.copy(it.session.cwd) } }
    val now = nowMillis()
    val seen = HashSet<String>()
    state.history.asReversed().forEach { h ->
        if (seen.add(h.command)) {
            items += PaletteItem(PaletteGroup.History, h.command, "${state.services.displayPath(h.cwd)} · ${formatAgo(h.at, now)}", Icons.History) { split ->
                if (split) state.split(true, h.command) else state.runInFocused(h.command)
            }
        }
    }
    state.shellHistory.asReversed().take(400).forEach { h ->
        if (seen.add(h)) {
            items += PaletteItem(PaletteGroup.History, h, "shell history", Icons.History) { split ->
                if (split) state.split(true, h) else state.runInFocused(h)
            }
        }
    }
    state.settings.recentDirs.forEach { d ->
        items += PaletteItem(PaletteGroup.Directories, state.services.displayPath(d.path), "Open in a new tab · ${formatAgo(d.lastUsed, now)}", Icons.Folder) { split ->
            if (split) state.split(true, "cd ${shellQuote(d.path)}") else state.newTerminalTab(cwd = d.path)
        }
    }
    return items
}

/** Fuzzy score: higher is better, null when [q] is not a subsequence of [text]. */
private fun score(text: String, q: String): Int? {
    if (q.isEmpty()) return 0
    val t = text.lowercase()
    val idx = t.indexOf(q)
    if (idx >= 0) return 1000 - idx * 2 - (t.length - q.length) / 4 + (if (idx == 0) 200 else 0)
    var ti = 0
    var gaps = 0
    for (ch in q) {
        val found = t.indexOf(ch, ti)
        if (found < 0) return null
        gaps += found - ti
        ti = found + 1
    }
    // Scattered letters across a long command are noise, not a match.
    if (gaps > 4 * q.length) return null
    return 300 - gaps
}

@Composable
fun CommandPalette(state: AppState, ui: UiActions) {
    val c = Vesper.colors
    val type = Vesper.type
    val all = remember(state.paletteOpen) { buildItems(state, ui) }
    var field by remember { mutableStateOf(TextFieldValue(state.paletteQuery, TextRange(state.paletteQuery.length))) }
    var filter by remember { mutableStateOf<PaletteGroup?>(if (state.paletteQuery.startsWith(">")) PaletteGroup.Commands else null) }
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val q = field.text.removePrefix(">").trim().lowercase()
    val matches = remember(q, all) {
        all.mapNotNull { item ->
            // Titles match fuzzily; subtitles only by substring, ranked below title hits.
            val inSubtitle = if (q.isNotEmpty() && item.subtitle.contains(q, ignoreCase = true)) 150 else null
            val s = listOfNotNull(score(item.title, q), inSubtitle).maxOrNull()
            if (s == null) null else item to s
        }.let { list -> if (q.isEmpty()) list else list.sortedByDescending { it.second } }.map { it.first }
    }
    val counts = PaletteGroup.entries.associateWith { g -> matches.count { it.group == g } }
    val perGroup = if (filter == null) 4 else 200
    val visible = PaletteGroup.entries
        .filter { filter == null || it == filter }
        .flatMap { g -> matches.filter { it.group == g }.take(if (q.isEmpty() && g == PaletteGroup.History) 3 else perGroup) }
    if (selected > visible.lastIndex) selected = visible.lastIndex.coerceAtLeast(0)

    fun close() {
        state.paletteOpen = false
    }

    fun runSelected(split: Boolean) {
        val item = visible.getOrNull(selected) ?: return
        close()
        item.action(split)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val info = listState.layoutInfo
        val target = rowIndexOf(visible, selected, filter == null)
        if (info.visibleItemsInfo.none { it.index == target && it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset }) {
            listState.animateScrollToItem((target - 2).coerceAtLeast(0))
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(interactionSource = null, indication = null) { close() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 74.dp)
                .width(680.dp)
                .popover(c, app.vesper.ui.components.RadiusXl)
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(Icons.Search, 18.dp, c.textSecondary)
                Box(Modifier.weight(1f)) {
                    val style = type.heading.copy(fontSize = 16.sp, color = c.textPrimary)
                    if (field.text.isEmpty()) VText("Search commands, history, hosts…", style, c.textMuted)
                    BasicTextField(
                        field,
                        {
                            field = it
                            selected = 0
                        },
                        singleLine = true,
                        textStyle = style,
                        cursorBrush = SolidColor(c.accentStrong),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> {
                                    selected = (selected + 1).coerceAtMost(visible.lastIndex)
                                    true
                                }
                                Key.DirectionUp -> {
                                    selected = (selected - 1).coerceAtLeast(0)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    runSelected(split = e.isMetaPressed || e.isCtrlPressed)
                                    true
                                }
                                Key.Tab -> {
                                    val order = listOf(null) + PaletteGroup.entries.filter { (counts[it] ?: 0) > 0 }
                                    val i = order.indexOf(filter).coerceAtLeast(0)
                                    filter = order[(i + (if (e.isShiftPressed) order.size - 1 else 1)) % order.size]
                                    selected = 0
                                    true
                                }
                                Key.Escape -> {
                                    close()
                                    true
                                }
                                else -> false
                            }
                        },
                    )
                }
                Kbd("esc")
            }
            Row(
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip("All", matches.size, filter == null) {
                    filter = null
                    selected = 0
                }
                PaletteGroup.entries.filter { (counts[it] ?: 0) > 0 }.forEach { g ->
                    FilterChip(g.title, counts[g] ?: 0, filter == g) {
                        filter = g
                        selected = 0
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height1().background(c.borderSubtle))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 8.dp, vertical = 6.dp), state = listState) {
                var last: PaletteGroup? = null
                visible.forEachIndexed { i, item ->
                    if (filter == null && item.group != last) {
                        val g = item.group
                        item(key = "h-${g.name}") { Overline(g.title, Modifier.padding(start = 10.dp, top = 10.dp, bottom = 6.dp)) }
                        last = g
                    }
                    item(key = "i-$i-${item.title}") {
                        PaletteRow(item, i == selected) {
                            selected = i
                            runSelected(false)
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item { VText("No matches", type.body, c.textMuted, Modifier.padding(16.dp)) }
                }
            }
            Box(Modifier.fillMaxWidth().height1().background(c.borderSubtle))
            Row(
                Modifier.fillMaxWidth().background(c.bgSurface.copy(alpha = 0.5f)).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyHint("↑↓", "navigate")
                KeyHint("⏎", "open")
                KeyHint("${state.services.os.mod}⏎", "open in split")
                KeyHint("⇥", "filter")
                Spacer(Modifier.weight(1f))
                VText("${if (filter == null) matches.size else counts[filter] ?: 0} results", type.meta, c.textMuted)
            }
        }
    }
}

private fun rowIndexOf(visible: List<PaletteItem>, selected: Int, headers: Boolean): Int {
    if (!headers) return selected
    var rows = 0
    var last: PaletteGroup? = null
    for (i in 0..selected.coerceAtMost(visible.lastIndex)) {
        if (visible[i].group != last) {
            rows++
            last = visible[i].group
        }
        if (i < selected) rows++
    }
    return rows
}

private fun Modifier.height1() = this.height(1.dp)

@Composable
private fun FilterChip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(
        Modifier
            .background(if (active) c.accentSoft else Color.Transparent, RadiusSm)
            .border(1.dp, if (active) Color.Transparent else c.borderSubtle, RadiusSm)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VText(label, type.codeSmall.copy(fontSize = 11.sp), if (active) c.accentText else c.textSecondary)
        VText(count.toString(), type.codeSmall.copy(fontSize = 11.sp), if (active) c.accentText else c.textMuted)
    }
}

@Composable
private fun PaletteRow(item: PaletteItem, active: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) c.bgActive else Color.Transparent, RadiusMd)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(if (active) c.accentSoft else c.bgSurface, RadiusMd)
                .border(1.dp, if (active) c.accentStrong.copy(alpha = 0.5f) else c.borderSubtle, RadiusMd),
            contentAlignment = Alignment.Center,
        ) { VIcon(item.icon, 14.dp, if (active) c.accentText else c.iconDefault) }
        Column(Modifier.weight(1f)) {
            VText(item.title, if (item.group == PaletteGroup.History) type.code else type.body, c.textPrimary)
            VText(item.subtitle, type.caption, c.textMuted)
        }
        item.shortcut?.let { Kbd(it) }
    }
}
