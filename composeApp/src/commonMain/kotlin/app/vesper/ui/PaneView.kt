package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.app.Pane
import app.vesper.app.PaneKind
import app.vesper.terminal.Block
import app.vesper.terminal.SessionMode
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.MenuDivider
import app.vesper.ui.components.MenuItem
import app.vesper.ui.components.MenuPopup
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.StatusDot
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.terminal.BlockActions
import app.vesper.ui.terminal.BlockMetrics
import app.vesper.ui.terminal.BlocksView
import app.vesper.ui.terminal.GridView
import app.vesper.ui.terminal.PromptActions
import app.vesper.ui.terminal.PromptBar
import app.vesper.ui.terminal.Suggestion
import app.vesper.ui.terminal.TermPalette
import app.vesper.ui.terminal.rememberCellSize
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatAgo
import app.vesper.util.nowMillis

/** Focus handles for one pane, kept across recompositions. */
class PaneFocus {
    val prompt = FocusRequester()
    val grid = FocusRequester()
}

@Composable
fun PaneView(
    state: AppState,
    ui: UiActions,
    pane: Pane,
    focus: PaneFocus,
    isFocused: Boolean,
    split: Boolean,
    palette: TermPalette,
    modifier: Modifier = Modifier,
) {
    val c = Vesper.colors
    val session = pane.session
    @Suppress("UNUSED_VARIABLE")
    val rev = session.revision
    val fullscreen = session.fullscreenTerminal()
    val blocks = session.blocksSnapshot()
    val running = session.currentBlock()
    val empty = session.mode != SessionMode.Raw && running == null && blocks.none { it.command.isNotEmpty() }

    LaunchedEffect(session.cwd, session.running) {
        if (!session.running) state.refreshContext(pane)
    }
    LaunchedEffect(isFocused, fullscreen != null) {
        if (isFocused) runCatching { if (fullscreen != null) focus.grid.requestFocus() else focus.prompt.requestFocus() }
    }

    val frame = if (split) {
        Modifier
            .background(c.bgPanel, RadiusLg)
            .border(1.dp, if (isFocused) c.accentStrong else c.borderSubtle, RadiusLg)
    } else {
        Modifier
    }
    Column(
        modifier
            .then(frame)
            .pointerInput(pane) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press) ui.focusPane(pane)
                    }
                }
            },
    ) {
        PaneHeader(state, ui, pane, split, blocks.count { it.command.isNotEmpty() })
        val blockActions = remember(pane) {
            object : BlockActions {
                override fun stop(block: Block) = session.interrupt()
                override fun rerun(block: Block) = state.run(pane, block.command)
                override fun copy(text: String) = ui.copy(text)
                override fun remove(block: Block) = session.removeBlock(block)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                fullscreen != null -> GridView(session, fullscreen, palette, focus.grid, state.services.os == app.vesper.platform.HostOs.MacOS)
                empty -> EmptyState(state, ui, pane)
                else -> {
                    val listState = rememberLazyListState()
                    BlocksSizer(pane)
                    BlocksView(session, palette, listState, blockActions, Modifier.fillMaxSize())
                    LaunchedEffect(running?.id) { if (running != null) listState.scrollToItem(0) }
                }
            }
        }
        if (fullscreen == null) {
            PromptBar(pane, rememberPromptActions(state, ui, pane), focus.prompt, state.services.displayPath(session.cwd))
        }
    }
}

/** Keeps the PTY size in step with the blocks area. */
@Composable
private fun BlocksSizer(pane: Pane) {
    val style = Vesper.type.code
    val cell = rememberCellSize(style)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val usable = maxWidth - BlockMetrics.ListPaddingH * 2 - BlockMetrics.OutputStart - BlockMetrics.OutputEnd
        val cols = with(density) { (usable.toPx() / cell.width).toInt() }.coerceAtLeast(20)
        val rows = with(density) { (maxHeight.toPx() / cell.height).toInt() }.coerceAtLeast(5)
        LaunchedEffect(cols, rows) { pane.session.resize(cols, rows) }
    }
}

@Composable
private fun rememberPromptActions(state: AppState, ui: UiActions, pane: Pane): PromptActions = remember(pane) {
    object : PromptActions {
        override fun run(command: String) = state.run(pane, command)
        override fun interrupt() = pane.session.interrupt()
        override fun sendRaw(text: String) = pane.session.write(text)
        override fun suggestions(input: String): List<Suggestion> = suggestFor(state, pane, input)
        override fun history(): List<String> {
            val own = pane.session.commandHistory()
            return (state.shellHistory + state.history.map { it.command } + own).distinctLast()
        }
        override fun openPalette() = ui.openPalette()
        override fun chipCwd() = state.services.revealInFileManager(pane.session.cwd)
        override fun chipBranch() {
            pane.draft = "git checkout "
        }
        override val modLabel: String get() = state.services.os.mod
    }
}

private fun List<String>.distinctLast(): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (i in indices.reversed()) if (seen.add(this[i])) out += this[i]
    out.reverse()
    return out
}

/** History, package scripts and workflows matching [input], best first. */
fun suggestFor(state: AppState, pane: Pane, input: String): List<Suggestion> {
    val q = input.trimStart()
    if (q.isEmpty()) return emptyList()
    val out = LinkedHashMap<String, Suggestion>()
    val history = (state.shellHistory + state.history.map { it.command } + pane.session.commandHistory()).asReversed()
    val ctx = pane.context
    val scripts = ctx.packageScripts.map { s ->
        when (ctx.packageManager) {
            "npm", null -> "npm run $s"
            else -> "${ctx.packageManager} $s"
        }
    }
    for (h in history) if (h.startsWith(q) && h != q) out.getOrPut(h) { Suggestion(h, "history", Icons.History) }
    for (s in scripts) if (s.startsWith(q) && s != q) out.getOrPut(s) { Suggestion(s, "package.json", Icons.Play) }
    for (w in state.settings.workflows) {
        val cmd = w.steps.joinToString(" && ")
        if ((cmd.startsWith(q) || w.name.startsWith(q, ignoreCase = true)) && cmd != q) out.getOrPut(cmd) { Suggestion(cmd, w.name, Icons.Zap) }
    }
    if (out.size < 6 && q.length >= 2) {
        for (h in history) if (h.contains(q) && h != q) out.getOrPut(h) { Suggestion(h, "history", Icons.History) }
    }
    return out.values.take(6)
}

@Composable
private fun PaneHeader(state: AppState, ui: UiActions, pane: Pane, split: Boolean, blockCount: Int) {
    val c = Vesper.colors
    val type = Vesper.type
    val session = pane.session
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (split) 40.dp else 44.dp)
            .padding(start = if (split) 14.dp else 18.dp, end = if (split) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val kind = pane.kind()
        if (split && kind != PaneKind.Local) {
            StatusDot(if (kind == PaneKind.Ssh) c.success else if (kind == PaneKind.Database) c.warning else c.info)
        } else {
            VIcon(if (split) Icons.Terminal else Icons.Folder, 14.dp)
        }
        val path = state.services.displayPath(session.cwd)
        val title = if (split) {
            if (kind == PaneKind.Local) "$path — ${session.shellName}" else pane.title(state.services::displayPath)
        } else {
            path
        }
        VText(title, type.codeSmall, if (split) c.textPrimary else c.textSecondary, Modifier.weight(1f))
        if (split) {
            IconButton(Icons.Columns, { ui.focusPane(pane); state.split(horizontal = true) }, size = 14.dp, padding = 5.dp)
            IconButton(Icons.Maximize, { ui.focusPane(pane); state.toggleZoom(pane) }, size = 14.dp, padding = 5.dp)
            IconButton(Icons.X, { state.closePane(pane) }, size = 14.dp, padding = 5.dp)
        } else {
            val age = formatAgo(nowMillis() - session.startedAt).let { if (it == "now") "just started" else "session $it" }
            val label = when {
                session.exited -> "exited"
                session.mode == SessionMode.Raw -> "classic mode · $age"
                blockCount == 0 -> "new session · 0 blocks"
                else -> "$blockCount block${if (blockCount == 1) "" else "s"} · $age"
            }
            VText(label, type.meta, c.textMuted)
            Box {
                IconButton(Icons.More, { menu = true }, size = 15.dp, padding = 5.dp)
                if (menu) {
                    MenuPopup({ menu = false }) {
                        val mod = state.services.os.mod
                        val shift = state.services.os.shift
                        MenuItem("Split right", { menu = false; state.split(true) }, Icons.Columns, "$mod\\")
                        MenuItem("Split down", { menu = false; state.split(false) }, Icons.Rows, "$mod$shift\\")
                        MenuItem("Clear blocks", { menu = false; session.clearBlocks() }, Icons.Trash)
                        MenuItem("Reveal in file manager", { menu = false; state.services.revealInFileManager(session.cwd) }, Icons.Folder)
                        MenuDivider()
                        MenuItem("Close tab", { menu = false; ui.closePane(pane) }, Icons.X, "${mod}W", danger = true)
                    }
                }
            }
        }
    }
    if (!split) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
}

@Composable
private fun EmptyState(state: AppState, ui: UiActions, pane: Pane) {
    val c = Vesper.colors
    val type = Vesper.type
    val ctx = pane.context
    val mod = state.services.os.mod
    val shift = state.services.os.shift
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).background(c.accentSoft, app.vesper.ui.components.RadiusXl)
                .border(1.dp, c.accentStrong.copy(alpha = 0.6f), app.vesper.ui.components.RadiusXl),
            contentAlignment = Alignment.Center,
        ) { VIcon(Icons.Terminal, 22.dp, c.accentText) }
        Box(Modifier.height(22.dp))
        VText("Ready when you are", type.display, c.textPrimary)
        Box(Modifier.height(8.dp))
        val sub = listOfNotNull(
            state.services.displayPath(pane.session.cwd),
            pane.session.shellName,
            ctx.nodeVersion?.let { "node $it" },
            ctx.gitBranch?.let { "git $it" },
        ).joinToString(" · ")
        VText(sub, type.codeSmall, c.textMuted)
        Box(Modifier.height(24.dp))
        Row(Modifier.widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val wf = state.settings.workflows
            ActionCard(Icons.Play, "Run a workflow", if (wf.isEmpty()) "Save multi-step commands" else wf.take(3).joinToString(", ") { it.name }, "${mod}K", Modifier.weight(1f)) {
                ui.openPalette(">")
            }
            ActionCard(Icons.Server, "Connect to a host", "${state.allHosts().size} known hosts", "$mod${shift}H", Modifier.weight(1f)) {
                state.openHosts()
            }
            ActionCard(Icons.Search, "Search history", "Commands, dirs, hosts", "${mod}K", Modifier.weight(1f)) { ui.openPalette() }
            ActionCard(Icons.Columns, "Split pane", "Work side by side", "$mod\\", Modifier.weight(1f)) { state.split(true) }
        }
        if (ctx.packageScripts.isNotEmpty()) {
            Box(Modifier.height(22.dp))
            app.vesper.ui.components.Overline("Detected in package.json")
            Box(Modifier.height(10.dp))
            FlowChips(ctx.packageScripts.take(8)) { s ->
                val cmd = if (ctx.packageManager == null || ctx.packageManager == "npm") "npm run $s" else "${ctx.packageManager} $s"
                app.vesper.ui.components.Chip(cmd, Icons.Play, onClick = { state.run(pane, cmd) })
            }
        }
        val recents = state.settings.recentDirs.filter { it.path != pane.session.cwd }.take(5)
        if (recents.isNotEmpty()) {
            Box(Modifier.height(22.dp))
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .background(c.bgSurface, app.vesper.ui.components.RadiusXl)
                    .border(1.dp, c.borderSubtle, app.vesper.ui.components.RadiusXl)
                    .padding(6.dp),
            ) {
                app.vesper.ui.components.Overline("Recent directories", Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                recents.forEach { dir ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { state.run(pane, "cd ${shellQuote(dir.path)}") }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VIcon(Icons.Folder, 14.dp)
                        VText(state.services.displayPath(dir.path), type.codeSmall, c.textPrimary, Modifier.weight(1f))
                        VText(formatAgo(nowMillis() - dir.lastUsed), type.meta, c.textMuted)
                    }
                }
            }
        }
    }
}

fun shellQuote(s: String): String = if (s.all { it.isLetterOrDigit() || it in "/._-~:" }) s else "'" + s.replace("'", "'\\''") + "'"

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    shortcut: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(
        modifier
            .background(c.bgSurface, app.vesper.ui.components.RadiusXl)
            .border(1.dp, c.borderSubtle, app.vesper.ui.components.RadiusXl)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(c.bgElevated, app.vesper.ui.components.RadiusMd)
                    .border(1.dp, c.borderDefault, app.vesper.ui.components.RadiusMd),
                contentAlignment = Alignment.Center,
            ) { VIcon(icon, 14.dp, c.textPrimary) }
            Box(Modifier.weight(1f))
            app.vesper.ui.components.Kbd(shortcut)
        }
        Box(Modifier.height(14.dp))
        VText(title, type.title, c.textPrimary)
        Box(Modifier.height(4.dp))
        VText(subtitle, type.caption, c.textMuted)
    }
}

@Composable
private fun FlowChips(items: List<String>, chip: @Composable (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { items.forEach { chip(it) } }
}
