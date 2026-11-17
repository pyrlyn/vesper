package app.vesper.ui

import app.vesper.ui.components.RadiusLg
import androidx.compose.runtime.produceState
import app.vesper.app.actionFor
import app.vesper.app.ShortcutAction
import app.vesper.app.KeyCombo
import app.vesper.app.KeyNames
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import app.vesper.ui.components.Radius2xl
import app.vesper.ui.components.RadiusPill
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.app.Pane
import app.vesper.app.PaneKind
import app.vesper.app.PaneNode
import app.vesper.app.Tab
import app.vesper.platform.HostOs
import app.vesper.terminal.SessionMode
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.Kbd
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusMd
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.SearchField
import app.vesper.ui.components.StatusDot
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.components.popover
import app.vesper.ui.icons.Icons
import app.vesper.ui.terminal.TermPalette
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatAgo
import app.vesper.util.formatClock
import app.vesper.util.nowMillis
import kotlinx.coroutines.delay

/** Platform window decorations the common UI hosts. */
class WindowChrome(
    /** Space reserved for native controls on the left (macOS traffic lights). */
    val leadingInset: Dp = 0.dp,
    /** Title bar height; on macOS it matches the native unified title bar so the traffic lights are centred. */
    val titleBarHeight: Dp = 46.dp,
    /** Wraps the empty title-bar area so it moves the window. */
    val dragArea: @Composable (Modifier, @Composable () -> Unit) -> Unit = { m, content -> Box(m) { content() } },
    /** Minimize / maximize / close for undecorated windows. */
    val controls: (@Composable () -> Unit)? = null,
)

/** UI-level actions shared by nested views. */
interface UiActions {
    fun focusPane(pane: Pane)
    fun closePane(pane: Pane)
    fun copy(text: String)
    fun openPalette(query: String = "")
}

@Composable
fun VesperApp(state: AppState, chrome: WindowChrome, onQuit: () -> Unit) {
    val c = Vesper.colors
    val clipboard = LocalClipboardManager.current
    val focuses = remember { mutableStateMapOf<Pane, PaneFocus>() }
    val ui = remember(state) {
        object : UiActions {
            override fun focusPane(pane: Pane) {
                val tab = state.tabs.filterIsInstance<Tab.Terminal>().firstOrNull { t -> t.root.panes().any { it === pane } } ?: return
                state.activeTabId = tab.id
                tab.focused = pane
            }
            override fun closePane(pane: Pane) = state.closePane(pane)
            override fun copy(text: String) = clipboard.setText(AnnotatedString(text))
            override fun openPalette(query: String) {
                state.paletteQuery = query
                state.paletteOpen = true
            }
        }
    }
    LaunchedEffect(Unit) { if (state.tabs.isEmpty()) state.newTerminalTab() }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgWindow)
            .onPreviewKeyEvent { handleShortcut(it, state, ui, clipboard, onQuit) },
    ) {
        TitleBar(state, ui, chrome)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (state.settings.showSidebar && state.activeTab !is Tab.Settings) Sidebar(state, ui)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (val tab = state.activeTab) {
                    is Tab.Terminal -> TerminalTabView(state, ui, tab, focuses)
                    is Tab.Settings -> SettingsScreen(state, tab)
                    is Tab.Hosts -> HostsScreen(state, tab)
                    is Tab.History -> HistoryScreen(state, tab)
                    null -> Unit
                }
            }
            if (state.settings.showMonitor && state.activeTab is Tab.Terminal) MonitorPanel(state)
        }
        StatusBar(state)
    }
    if (state.paletteOpen) CommandPalette(state, ui)
    state.hostVerification?.let { HostKeyDialog(state, it) }
    if (state.aboutOpen) AboutDialog(state)
    if (!state.settings.onboarded && state.historyLoaded) Onboarding(state)
    if (state.quitRequested) QuitDialog(state, onQuit)
}

// ── Shortcuts ────────────────────────────────────────────────────────────

private fun handleShortcut(e: KeyEvent, state: AppState, ui: UiActions, clipboard: ClipboardManager, onQuit: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val mac = state.services.os == HostOs.MacOS
    val fullscreen = state.focusedPane?.session?.fullscreenTerminal() != null
    // On Windows/Linux, Ctrl+key belongs to the program in a full-screen terminal, so app shortcuts need Shift there.
    val mod = if (mac) e.isMetaPressed && !e.isCtrlPressed else e.isCtrlPressed && !e.isMetaPressed && (!fullscreen || e.isShiftPressed)
    if (state.recordingShortcut) return false
    if (!mod) {
        if (e.key == Key.Escape && state.paletteOpen) {
            state.paletteOpen = false
            return true
        }
        if (e.isCtrlPressed && e.key == Key.Tab) {
            state.cycleTab(if (e.isShiftPressed) -1 else 1)
            return true
        }
        return false
    }
    val shift = e.isShiftPressed && (mac || !fullscreen)
    val tab = state.activeTab as? Tab.Terminal
    val action = KeyNames[e.key]?.let { state.settings.actionFor(KeyCombo(it, shift, e.isAltPressed)) }
    if (action != null) return runShortcut(action, state, ui)
    when (e.key) {
        // ⌘\ and ⌘⇧\ keep splitting whatever the split shortcuts are bound to.
        Key.Backslash -> state.split(horizontal = !shift)
        Key.Q -> if (mac) requestQuit(state, onQuit) else return false
        Key.V -> {
            val pane = state.focusedPane ?: return false
            if (pane.session.fullscreenTerminal() == null) return false
            clipboard.getText()?.text?.let { pane.session.paste(it.replace("\r\n", "\n").replace('\n', '\r')) }
        }
        Key.C -> return false
        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
            if (!e.isAltPressed || tab == null) return false
            moveFocus(tab, e.key)
        }
        else -> {
            val digit = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(e.key)
            if (digit < 0) return false
            if (digit == 8) state.selectTab(state.tabs.lastIndex) else state.selectTab(digit)
        }
    }
    return true
}

private fun runShortcut(action: ShortcutAction, state: AppState, ui: UiActions): Boolean {
    val tab = state.activeTab as? Tab.Terminal
    when (action) {
        ShortcutAction.Palette -> {
            state.paletteQuery = ""
            state.paletteOpen = !state.paletteOpen
        }
        ShortcutAction.CommandsOnly -> ui.openPalette(">")
        ShortcutAction.Settings -> state.openSettings()
        ShortcutAction.Hosts -> state.openHosts()
        ShortcutAction.History -> state.openHistory()
        ShortcutAction.ToggleSidebar -> state.updateSettings { it.copy(showSidebar = !it.showSidebar) }
        ShortcutAction.ToggleMonitor -> state.updateSettings { it.copy(showMonitor = !it.showMonitor) }
        ShortcutAction.NewTab -> state.newTerminalTab()
        ShortcutAction.Close -> when (val t = state.activeTab) {
            is Tab.Terminal -> state.closePane(t.focused)
            null -> Unit
            else -> state.closeTab(t)
        }
        ShortcutAction.PreviousTab -> state.cycleTab(-1)
        ShortcutAction.NextTab -> state.cycleTab(1)
        ShortcutAction.SplitRight -> state.split(horizontal = true)
        ShortcutAction.SplitDown -> state.split(horizontal = false)
        ShortcutAction.Zoom -> tab?.let { state.toggleZoom(it.focused) } ?: return false
        ShortcutAction.Find -> {
            val pane = tab?.focused ?: return false
            if (pane.session.fullscreenTerminal() != null) return false
            pane.find.open = true
            pane.find.focusRequests++
        }
    }
    return true
}

/** Asks first when commands are running; asking again (a second ⌘Q) quits. */
fun requestQuit(state: AppState, onQuit: () -> Unit) {
    if (state.quitRequested || !state.settings.confirmQuit || state.hasRunningProcesses().isEmpty()) onQuit() else state.quitRequested = true
}

/** Moves focus to the nearest pane in the pressed direction (tree order approximation). */
private fun moveFocus(tab: Tab.Terminal, key: Key) {
    val panes = tab.root.panes()
    val i = panes.indexOf(tab.focused)
    val next = when (key) {
        Key.DirectionLeft, Key.DirectionUp -> i - 1
        else -> i + 1
    }
    panes.getOrNull(next)?.let { tab.focused = it }
}

// ── Title bar ────────────────────────────────────────────────────────────

@Composable
private fun TitleBar(state: AppState, ui: UiActions, chrome: WindowChrome) {
    val c = Vesper.colors
    val os = state.services.os
    Row(
        Modifier
            .fillMaxWidth()
            .height(chrome.titleBarHeight)
            .background(if (c.glass) Color.Transparent else c.bgSidebar)
            .padding(start = 14.dp + chrome.leadingInset, end = if (chrome.controls != null) 6.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tabs: @Composable () -> Unit = {
            state.tabs.forEachIndexed { index, tab ->
                TabChip(state, tab, index, active = tab.id == state.activeTab?.id)
            }
            IconButton(Icons.Plus, { state.newTerminalTab() }, size = 15.dp, padding = 6.dp)
        }
        if (c.glass) {
            // Liquid Glass: the tabs sit in one capsule.
            Row(
                Modifier
                    .background((if (c.isDark) Color.White else Color.Black).copy(alpha = 0.05f), RadiusPill)
                    .border(1.dp, c.borderSubtle, RadiusPill)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { tabs() }
        } else {
            tabs()
        }
        chrome.dragArea(Modifier.weight(1f).fillMaxHeight()) { Spacer(Modifier.fillMaxSize()) }
        Box(Modifier.width(300.dp).clickable { ui.openPalette() }.pointerHoverIcon(PointerIcon.Hand)) {
            SearchField("", {}, "Search commands, history, hosts…", Modifier.fillMaxWidth(), shape = if (c.glass) RadiusPill else RadiusMd) {
                Kbd(if (os == HostOs.MacOS) "⌘K" else "Ctrl K")
            }
            // Transparent layer so clicks open the palette instead of focusing the field.
            Box(Modifier.matchParentSize().clickable { ui.openPalette() })
        }
        Spacer(Modifier.width(4.dp))
        IconButton(Icons.PanelRight, { state.updateSettings { it.copy(showMonitor = !it.showMonitor) } }, active = state.settings.showMonitor)
        IconButton(Icons.Settings, { state.openSettings() }, active = state.activeTab is Tab.Settings)
        chrome.controls?.let {
            Spacer(Modifier.width(6.dp))
            it()
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
}

@Composable
private fun TabChip(state: AppState, tab: Tab, index: Int, active: Boolean) {
    val c = Vesper.colors
    val type = Vesper.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = if (c.glass) RadiusPill else RadiusMd
    Row(
        Modifier
            .hoverable(source)
            .background(if (active) c.bgElevated else if (hovered) c.bgActive.copy(alpha = 0.5f) else Color.Transparent, shape)
            .border(1.dp, if (active) c.borderDefault else Color.Transparent, shape)
            .clickable(source, null) { state.activeTabId = tab.id }
            .padding(start = 13.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, dot, label) = tabLook(state, tab)
        if (dot != null) StatusDot(dot) else VIcon(icon, 14.dp, if (active) c.textPrimary else c.iconDefault)
        VText(label, type.bodySmall, if (active) c.textPrimary else c.textSecondary, Modifier.widthIn(max = 220.dp))
        Box(contentAlignment = Alignment.Center) {
            val shortcut = if (index < 9) "${state.services.os.mod}${index + 1}" else ""
            VText(shortcut, type.kbd.copy(fontFamily = type.mono), if (hovered) Color.Transparent else c.textMuted)
            if (hovered) {
                Box(Modifier.clickable { state.closeTab(tab) }.pointerHoverIcon(PointerIcon.Hand)) {
                    VIcon(Icons.X, 12.dp, c.textSecondary)
                }
            }
        }
    }
}

private data class TabLook(val icon: ImageVector, val dot: Color?, val label: String)

@Composable
private fun tabLook(state: AppState, tab: Tab): TabLook {
    val c = Vesper.colors
    return when (tab) {
        is Tab.Settings -> TabLook(Icons.Settings, null, "Settings")
        is Tab.Hosts -> TabLook(Icons.Globe, null, "Hosts")
        is Tab.History -> TabLook(Icons.History, null, "History")
        is Tab.Terminal -> {
            @Suppress("UNUSED_VARIABLE")
            val rev = tab.focused.session.revision
            val panes = tab.root.panes()
            val pane = tab.focused
            val kind = pane.kind()
            val base = pane.title(state.services::displayPath)
            val label = if (panes.size > 1) "${base.substringBefore(" — ")} · ${panes.size} panes" else base
            when (kind) {
                PaneKind.Ssh -> TabLook(Icons.Server, c.success, label)
                PaneKind.Database -> TabLook(Icons.Database, c.warning, label)
                PaneKind.Logs -> TabLook(Icons.Logs, c.info, label)
                PaneKind.Local -> TabLook(Icons.Terminal, null, label)
            }
        }
    }
}

// ── Sidebar ──────────────────────────────────────────────────────────────

@Composable
private fun Sidebar(state: AppState, ui: UiActions) {
    val c = Vesper.colors
    val type = Vesper.type
    Row {
        val frame = if (c.glass) {
            // Liquid Glass: the sidebar floats as a rounded pane with a light top edge. No drop shadow:
            // Compose would draw it under the translucent fill too.
            Modifier
                .padding(start = 8.dp, bottom = 8.dp)
                .width(224.dp)
                .fillMaxHeight()
                .background(c.bgSidebar, Radius2xl)
                .border(1.dp, c.borderDefault, Radius2xl)
                .drawBehind {
                    val r = 14.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(r, 1f), Offset(size.width - r, 1f), 1f)
                }
        } else {
            Modifier.width(232.dp).fillMaxHeight().background(c.bgSidebar)
        }
        Column(frame.padding(horizontal = 10.dp, vertical = 14.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val panes = state.tabs.filterIsInstance<Tab.Terminal>().flatMap { it.root.panes() }
                SidebarSection("Sessions", panes.size.toString()) {
                    panes.forEach { pane ->
                        @Suppress("UNUSED_VARIABLE")
                        val rev = pane.session.revision
                        val kind = pane.kind()
                        val (icon, trailing) = when (kind) {
                            PaneKind.Ssh -> Icons.Server to null
                            PaneKind.Database -> Icons.Database to null
                            PaneKind.Logs -> Icons.Logs to "tail"
                            PaneKind.Local -> Icons.Terminal to pane.session.shellName.substringAfterLast('/')
                        }
                        val dot = when (kind) {
                            PaneKind.Ssh -> c.success
                            PaneKind.Database -> c.warning
                            else -> null
                        }
                        val name = when (kind) {
                            PaneKind.Local -> state.services.displayPath(pane.session.cwd).substringAfterLast('/').ifEmpty { "~" }
                            else -> pane.title(state.services::displayPath).removePrefix("ssh ").substringBefore(' ')
                        }
                        val active = state.focusedPane === pane && state.activeTab is Tab.Terminal
                        SidebarItem(icon, name, active, onClick = { ui.focusPane(pane) }) {
                            when {
                                pane.session.exited -> VText("exited", type.meta, c.textMuted)
                                dot != null -> StatusDot(dot)
                                pane.session.currentBlock() != null -> StatusDot(c.accent)
                                trailing != null -> VText(trailing, type.meta, c.textMuted)
                            }
                        }
                    }
                }
                SidebarSection("Workflows", "+", onAction = { state.openSettings(app.vesper.app.SettingsSection.Workflows) }) {
                    state.settings.workflows.forEach { wf ->
                        SidebarItem(Icons.Play, wf.name, false, onClick = { state.runWorkflow(wf) }) {
                            if (wf.steps.size > 1) VText("${wf.steps.size} steps", type.meta, c.textMuted)
                        }
                    }
                }
                val history = state.history.asReversed().distinctBy { it.command }.take(6).map { it.command to it.at }
                    .ifEmpty { state.shellHistory.asReversed().distinct().take(4).map { it to -1L } }
                if (history.isNotEmpty()) {
                    var now by remember { mutableLongStateOf(nowMillis()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(30_000)
                            now = nowMillis()
                        }
                    }
                    SidebarSection("History", "all", onAction = { state.openHistory() }) {
                        history.forEach { (cmd, at) ->
                            SidebarItem(Icons.ChevronRight, cmd, false, mono = true, onClick = {
                                state.focusedPane?.draft = cmd
                            }) {
                                if (at > 0) VText(formatAgo(at, now), type.meta, c.textMuted)
                            }
                        }
                    }
                }
            }
            TipCard(state)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
    }
}

@Composable
private fun SidebarSection(title: String, trailing: String?, onAction: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Vesper.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Overline(title, Modifier.weight(1f))
            if (trailing != null) {
                val m = if (onAction != null) Modifier.clickable(onClick = onAction).pointerHoverIcon(PointerIcon.Hand) else Modifier
                VText(trailing, Vesper.type.meta, c.textMuted, m)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    mono: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val c = Vesper.colors
    val type = Vesper.type
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) c.bgActive else if (hovered) c.bgActive.copy(alpha = 0.45f) else Color.Transparent, RadiusMd)
            .clickable(source, null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(icon, if (mono) 11.dp else 14.dp, if (active) c.textPrimary else c.iconDefault)
        VText(
            label,
            if (mono) type.codeSmall else type.body,
            if (active) c.textPrimary else if (mono) c.textSecondary else c.textSecondary,
            Modifier.weight(1f),
        )
        trailing()
    }
}

private val Tips = listOf(
    "Press %K to search commands, history, hosts and workflows.",
    "Tab accepts the grey suggestion; ↑ walks back through history.",
    "%\\ splits the pane; %Enter zooms the focused one.",
    "Shift+Enter adds a line; multi-line commands run as one block.",
    "Full-screen apps like vim, htop and less get a classic grid automatically.",
)

@Composable
private fun TipCard(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val tipIndex = remember { (nowMillis() / 60_000 % Tips.size).toInt() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.accentSoft.copy(alpha = c.accentSoft.alpha * 0.8f), RadiusXl)
            .border(1.dp, c.accentStrong.copy(alpha = 0.55f), RadiusXl)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VIcon(Icons.Sparkles, 14.dp, c.accentText)
            VText("Tip", type.title, c.textPrimary)
        }
        VText(Tips[tipIndex].replace("%", state.services.os.mod), type.caption, c.textSecondary, maxLines = 4)
    }
}

// ── Terminal tab: split panes ────────────────────────────────────────────

@Composable
private fun TerminalTabView(state: AppState, ui: UiActions, tab: Tab.Terminal, focuses: MutableMap<Pane, PaneFocus>) {
    val c = Vesper.colors
    val palette = remember(c) { TermPalette(c) }
    val zoomed = tab.zoomed
    val panes = tab.root.panes()
    // Drop focus handles of closed panes.
    LaunchedEffect(panes.size) { focuses.keys.retainAll { p -> state.tabs.filterIsInstance<Tab.Terminal>().any { t -> t.root.panes().any { it === p } } } }
    val split = panes.size > 1 && zoomed == null
    Box(Modifier.fillMaxSize().padding(if (split) 8.dp else 0.dp)) {
        if (zoomed != null || panes.size == 1) {
            val pane = zoomed ?: panes.first()
            PaneView(state, ui, pane, focuses.getOrPut(pane) { PaneFocus() }, tab.focused === pane, false, palette, Modifier.fillMaxSize())
        } else {
            PaneTree(state, ui, tab, tab.root, focuses, palette, Modifier.fillMaxSize())
        }
    }
    val pane = tab.focused
    @Suppress("UNUSED_VARIABLE")
    val rev = pane.session.revision
    state.gridSize = pane.session.cols to pane.session.rows
}

@Composable
private fun PaneTree(
    state: AppState,
    ui: UiActions,
    tab: Tab.Terminal,
    node: PaneNode,
    focuses: MutableMap<Pane, PaneFocus>,
    palette: TermPalette,
    modifier: Modifier,
) {
    when (node) {
        is PaneNode.Leaf -> PaneView(state, ui, node.pane, focuses.getOrPut(node.pane) { PaneFocus() }, tab.focused === node.pane, true, palette, modifier)
        is PaneNode.Split -> BoxWithConstraints(modifier) {
            val total = if (node.horizontal) constraints.maxWidth.toFloat() else constraints.maxHeight.toFloat()
            val handle: @Composable () -> Unit = {
                Box(
                    Modifier
                        .then(if (node.horizontal) Modifier.width(8.dp).fillMaxHeight() else Modifier.height(8.dp).fillMaxWidth())
                        .pointerHoverIcon(if (node.horizontal) resizeHorizontalCursor() else resizeVerticalCursor())
                        .pointerInput(node) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val delta = if (node.horizontal) drag.x else drag.y
                                node.ratio = (node.ratio + delta / total).coerceIn(0.15f, 0.85f)
                            }
                        },
                )
            }
            if (node.horizontal) {
                Row(Modifier.fillMaxSize()) {
                    PaneTree(state, ui, tab, node.first, focuses, palette, Modifier.weight(node.ratio).fillMaxHeight())
                    handle()
                    PaneTree(state, ui, tab, node.second, focuses, palette, Modifier.weight(1f - node.ratio).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PaneTree(state, ui, tab, node.first, focuses, palette, Modifier.weight(node.ratio).fillMaxWidth())
                    handle()
                    PaneTree(state, ui, tab, node.second, focuses, palette, Modifier.weight(1f - node.ratio).fillMaxWidth())
                }
            }
        }
    }
}

// ── Status bar ───────────────────────────────────────────────────────────

@Composable
private fun StatusBar(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = nowMillis()
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
    Row(
        Modifier.fillMaxWidth().background(if (c.glass) Color.Transparent else c.bgSidebar).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pane = state.focusedPane
        val tab = state.activeTab as? Tab.Terminal
        if (pane != null) {
            @Suppress("UNUSED_VARIABLE")
            val rev = pane.session.revision
            val (dot, label) = when {
                pane.session.exited -> c.danger to "exited"
                pane.session.mode == SessionMode.Starting -> c.warning to "starting"
                pane.session.currentBlock() != null -> c.accent to "running"
                pane.session.mode == SessionMode.Raw -> c.success to "classic"
                else -> c.success to "ready"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dot, 6.dp)
                val panes = tab?.root?.panes()?.size ?: 1
                VText(if (panes > 1) "$panes panes · $label" else label, type.meta, c.textMuted)
            }
            VText(pane.session.shellName, type.meta, c.textMuted)
            pane.context.gitBranch?.let { VText(it, type.meta, c.textMuted) }
        } else if (state.activeTab is Tab.Hosts) {
            val connected = state.tabs.filterIsInstance<Tab.Terminal>()
                .flatMap { it.root.panes() }
                .count { it.kind() == PaneKind.Ssh && !it.session.exited }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (connected > 0) c.success else c.textMuted, 6.dp)
                VText("${state.allHosts().size} hosts · $connected connected", type.meta, c.textMuted)
            }
            VText("ssh config", type.meta, c.textMuted)
            VText("~/.ssh/config", type.meta, c.textMuted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(c.success, 6.dp)
                VText("settings saved", type.meta, c.textMuted)
            }
            VText(state.services.displayPath(state.services.configPath), type.meta, c.textMuted)
        }
        Spacer(Modifier.weight(1f))
        VText("UTF-8", type.meta, c.textMuted)
        if (pane != null) VText("${state.gridSize.first}×${state.gridSize.second}", type.meta, c.textMuted)
        Row(
            Modifier.clickable { state.paletteOpen = true }.pointerHoverIcon(PointerIcon.Hand),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Kbd(if (state.services.os == HostOs.MacOS) "⌘K" else "Ctrl K")
            VText("commands", type.meta, c.textMuted)
        }
        VText(formatClock(now, seconds = false), type.meta, c.textMuted)
    }
}

@Composable
private fun QuitDialog(state: AppState, onQuit: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    val running = state.hasRunningProcesses()
    val mac = state.services.os == HostOs.MacOS
    val now by produceState(nowMillis()) { while (true) { delay(1000); value = nowMillis() } }
    app.vesper.ui.components.VDialog(
        "Quit Vesper?",
        onDismiss = { state.quitRequested = false },
        icon = Icons.AlertTriangle,
        iconTint = c.warning,
        footer = {
            VText(if (mac) "⌘Q again to force" else "Close again to force", type.meta, c.textMuted, Modifier.weight(1f))
            app.vesper.ui.components.VButton("Cancel", { state.quitRequested = false }, kind = app.vesper.ui.components.ButtonKind.Ghost)
            app.vesper.ui.components.VButton("Quit anyway", onQuit, icon = Icons.X, kind = app.vesper.ui.components.ButtonKind.Danger)
        },
    ) {
        VText(
            if (running.isEmpty()) "Nothing is running any more." else "These are still running and will be stopped:",
            type.body,
            c.textSecondary,
        )
        if (running.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(c.bgSurface, RadiusLg).border(1.dp, c.borderSubtle, RadiusLg)) {
                running.take(6).forEachIndexed { i, (pane, command) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
                    val ssh = command.trimStart().startsWith("ssh ")
                    val started = pane.session.currentBlock()?.startedAt ?: now
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VIcon(if (ssh) Icons.Server else Icons.Terminal, 15.dp, c.iconDefault)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            VText(command.lineSequence().first(), type.codeSmall, c.textPrimary)
                            VText(
                                "${state.services.displayPath(pane.session.cwd)} · running ${formatElapsed(now - started)}",
                                type.meta,
                                c.textMuted,
                            )
                        }
                        app.vesper.ui.components.StatusDot(if (ssh) c.success else c.accentStrong)
                    }
                }
                if (running.size > 6) VText("and ${running.size - 6} more", type.meta, c.textMuted, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/** 42s → "00:42", 1h 2m → "1:02:00". */
private fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${two(m)}:${two(sec)}"
}

expect fun resizeHorizontalCursor(): PointerIcon
expect fun resizeVerticalCursor(): PointerIcon
