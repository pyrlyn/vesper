package app.vesper.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vesper.terminal.TerminalSession
import app.vesper.util.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a pane is running, derived from its command — drives icons, dots and titles. */
enum class PaneKind { Local, Ssh, Database, Logs }

class Pane(val session: TerminalSession, val launchCommand: String?) {
    var context by mutableStateOf(PromptContext())
    var draft by mutableStateOf("")
    val find = FindState()

    /** Kind of the current foreground command (or the command the pane was opened with). */
    fun kind(): PaneKind {
        val cmd = session.currentBlock()?.command ?: launchCommand ?: return PaneKind.Local
        return kindOf(cmd)
    }

    fun title(displayPath: (String) -> String): String {
        val running = session.currentBlock()?.command ?: launchCommand
        return when (running?.let(::kindOf)) {
            PaneKind.Ssh, PaneKind.Database, PaneKind.Logs -> running.substringBefore(" | ").take(40)
            else -> {
                val dir = displayPath(session.cwd).substringAfterLast('/').ifEmpty { displayPath(session.cwd) }
                "$dir — ${session.shellName.substringBefore(' ')}"
            }
        }
    }

    companion object {
        fun kindOf(cmd: String): PaneKind {
            val first = cmd.trim().substringBefore(' ')
            return when {
                first == "ssh" || first == "mosh" || first == "et" -> PaneKind.Ssh
                first in setOf("psql", "mysql", "mariadb", "redis-cli", "sqlite3", "mongosh", "pgcli", "mycli", "clickhouse") -> PaneKind.Database
                cmd.startsWith("tail -f") || cmd.startsWith("tail -F") || cmd.startsWith("journalctl") && "-f" in cmd ||
                    cmd.startsWith("kubectl logs") || cmd.startsWith("docker logs") -> PaneKind.Logs
                else -> PaneKind.Local
            }
        }
    }
}

/** Find in output (⌘F) for one pane. */
class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var regex by mutableStateOf(false)

    /** Index of the current match; moved by next/previous. */
    var index by mutableIntStateOf(0)

    /** Bumped to ask the find field to take focus (it may already be open). */
    var focusRequests by mutableIntStateOf(0)
}

sealed class PaneNode {
    class Leaf(val pane: Pane) : PaneNode()
    class Split(val horizontal: Boolean, first: PaneNode, second: PaneNode) : PaneNode() {
        var first by mutableStateOf(first)
        var second by mutableStateOf(second)
        var ratio by mutableFloatStateOf(0.5f)
    }

    fun panes(): List<Pane> = when (this) {
        is Leaf -> listOf(pane)
        is Split -> first.panes() + second.panes()
    }
}

sealed class Tab(val id: Int) {
    class Terminal(id: Int, root: PaneNode) : Tab(id) {
        var root by mutableStateOf(root)
        var focused by mutableStateOf(root.panes().first())
        var zoomed by mutableStateOf<Pane?>(null)
    }

    class Settings(id: Int) : Tab(id) {
        var section by mutableStateOf(SettingsSection.Appearance)
    }

    class Hosts(id: Int) : Tab(id) {
        var selected by mutableStateOf<String?>(null)
    }

    class History(id: Int) : Tab(id) {
        var query by mutableStateOf("")
        var thisDirectory by mutableStateOf<String?>(null)
        var failedOnly by mutableStateOf(false)
        var lastWeek by mutableStateOf(false)
        var selected by mutableStateOf<HistoryEntry?>(null)
    }
}

enum class SettingsSection(val title: String) {
    General("General"),
    Appearance("Appearance"),
    Terminal("Terminal"),
    Keybindings("Keybindings"),
    Hosts("Hosts & SSH"),
    Workflows("Workflows"),
    Privacy("Privacy"),
    About("About"),
}

enum class MonitorTab { System, Ports, Logs }

class AppState(val services: AppServices, private val scope: CoroutineScope) {
    val tabs = mutableStateListOf<Tab>()
    var activeTabId by mutableIntStateOf(-1)
    var settings by mutableStateOf(services.loadSettings())
        private set
    var paletteOpen by mutableStateOf(false)
    var paletteQuery by mutableStateOf("")
    var monitorTab by mutableStateOf(MonitorTab.System)
    var quitRequested by mutableStateOf(false)
    val history = mutableStateListOf<HistoryEntry>()
    var sshHosts by mutableStateOf<List<HostEntry>>(emptyList())
        private set
    var shellHistory by mutableStateOf<List<String>>(emptyList())
        private set

    /** Grid size of the focused pane, shown in the status bar. */
    var gridSize by mutableStateOf(0 to 0)

    private var nextId = 1
    private var historyLoaded = false

    val activeTab: Tab? get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()

    val focusedPane: Pane? get() = (activeTab as? Tab.Terminal)?.focused

    init {
        scope.launch(Dispatchers.Default) {
            val hosts = runCatching { services.sshHosts() }.getOrDefault(emptyList())
            val hist = runCatching { services.shellHistory(2000) }.getOrDefault(emptyList())
            val own = runCatching { services.loadHistory() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                sshHosts = hosts
                shellHistory = hist
                // Commands finished before the file was read stay after the loaded ones.
                history.addAll(0, own.takeLast(MaxHistory))
                historyLoaded = true
            }
        }
    }

    fun allHosts(): List<HostEntry> = settings.hosts.map {
        HostEntry(it.alias, it.host, it.user, it.port, it.identityFile, it.jumpHost, "vesper", it.tag, it.group)
    } + sshHosts.filter { h -> settings.hosts.none { it.alias == h.alias } }

    fun updateSettings(transform: (Settings) -> Settings) {
        val next = transform(settings)
        if (next == settings) return
        settings = next
        scope.launch(Dispatchers.Default) { runCatching { services.saveSettings(next) } }
    }

    // ── Tabs & panes ─────────────────────────────────────────────────────

    private fun newPane(cwd: String?, command: String?): Pane {
        val dir = cwd?.takeIf { services.exists(it) } ?: services.homeDir
        val session = services.createSession(nextId++, dir, settings.shell, scope, 120, 38) { which -> colorReply(which) }
        val pane = Pane(session, command)
        session.onBlockFinished = { block ->
            val entry = HistoryEntry(block.command, block.startedAt, block.cwd, block.exitCode, block.durationMs)
            scope.launch(Dispatchers.Main) { record(entry) }
        }
        if (command != null) launchWhenReady(pane, command)
        refreshContext(pane)
        rememberDir(dir)
        return pane
    }

    private fun colorReply(which: Int): String? {
        val c = app.vesper.ui.theme.colorsFor(settings.theme, settings.accent)
        val color = when (which) {
            10 -> c.textCode
            11 -> c.bgWindow
            else -> c.accentStrong
        }
        fun h(v: Float) = (v * 255).toInt().toString(16).padStart(2, '0').repeat(2)
        return "rgb:${h(color.red)}/${h(color.green)}/${h(color.blue)}"
    }

    private fun launchWhenReady(pane: Pane, command: String) {
        scope.launch {
            val end = nowMillis() + 8_000
            while (pane.session.mode == app.vesper.terminal.SessionMode.Starting && nowMillis() < end) kotlinx.coroutines.delay(30)
            run(pane, command)
        }
    }

    fun newTerminalTab(cwd: String? = focusedPane?.session?.cwd, command: String? = null): Tab.Terminal {
        val tab = Tab.Terminal(nextId++, PaneNode.Leaf(newPane(cwd, command)))
        tabs += tab
        activeTabId = tab.id
        return tab
    }

    fun openSettings(section: SettingsSection? = null) {
        val existing = tabs.filterIsInstance<Tab.Settings>().firstOrNull()
        val tab = existing ?: Tab.Settings(nextId++).also { tabs += it }
        if (section != null) tab.section = section
        activeTabId = tab.id
    }

    fun openHistory() {
        val tab = tabs.filterIsInstance<Tab.History>().firstOrNull() ?: Tab.History(nextId++).also { tabs += it }
        activeTabId = tab.id
    }

    fun openHosts(select: String? = null) {
        val existing = tabs.filterIsInstance<Tab.Hosts>().firstOrNull()
        val tab = existing ?: Tab.Hosts(nextId++).also { tabs += it }
        if (select != null) tab.selected = select
        activeTabId = tab.id
    }

    fun selectTab(index: Int) {
        tabs.getOrNull(index)?.let { activeTabId = it.id }
    }

    fun cycleTab(delta: Int) {
        if (tabs.isEmpty()) return
        val i = tabs.indexOfFirst { it.id == activeTab?.id }
        activeTabId = tabs[(i + delta + tabs.size) % tabs.size].id
    }

    fun closeTab(tab: Tab) {
        if (tab is Tab.Terminal) tab.root.panes().forEach { it.session.close() }
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (activeTabId == tab.id) activeTabId = tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.id ?: -1
    }

    fun split(horizontal: Boolean, command: String? = null) {
        val tab = activeTab as? Tab.Terminal ?: return
        val target = tab.focused
        val pane = newPane(target.session.cwd, command)
        tab.root = replace(tab.root, target) { PaneNode.Split(horizontal, it, PaneNode.Leaf(pane)) }
        tab.zoomed = null
        tab.focused = pane
    }

    fun closePane(pane: Pane) {
        val tab = tabs.filterIsInstance<Tab.Terminal>().firstOrNull { t -> t.root.panes().any { it === pane } } ?: return
        if (tab.root is PaneNode.Leaf) {
            closeTab(tab)
            return
        }
        pane.session.close()
        tab.root = remove(tab.root, pane) ?: return closeTab(tab)
        if (tab.zoomed === pane) tab.zoomed = null
        if (tab.focused === pane) tab.focused = tab.root.panes().first()
    }

    fun toggleZoom(pane: Pane) {
        val tab = activeTab as? Tab.Terminal ?: return
        tab.zoomed = if (tab.zoomed === pane) null else pane
    }

    private fun replace(node: PaneNode, target: Pane, f: (PaneNode) -> PaneNode): PaneNode = when (node) {
        is PaneNode.Leaf -> if (node.pane === target) f(node) else node
        is PaneNode.Split -> node.also {
            it.first = replace(it.first, target, f)
            it.second = replace(it.second, target, f)
        }
    }

    private fun remove(node: PaneNode, target: Pane): PaneNode? = when (node) {
        is PaneNode.Leaf -> if (node.pane === target) null else node
        is PaneNode.Split -> {
            val a = remove(node.first, target)
            val b = remove(node.second, target)
            when {
                a == null -> b
                b == null -> a
                else -> node.also {
                    it.first = a
                    it.second = b
                }
            }
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────

    fun run(pane: Pane, command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        pane.session.submit(trimmed)
    }

    /** Adds a finished command to the history and saves it. */
    private fun record(entry: HistoryEntry) {
        history.add(entry)
        if (history.size > MaxHistory) history.removeRange(0, history.size - MaxHistory)
        saveHistory()
    }

    /** The terminal tab used last, made active (a new one in [cwd] if there is none). */
    private fun terminalForHistory(cwd: String): Pane {
        val tab = tabs.filterIsInstance<Tab.Terminal>().lastOrNull() ?: return newTerminalTab(cwd).focused
        activeTabId = tab.id
        return tab.focused
    }

    fun runAgain(entry: HistoryEntry) = run(terminalForHistory(entry.cwd), entry.command)

    fun insertIntoPrompt(command: String) {
        terminalForHistory(services.homeDir).draft = command
    }

    /** Saves [command] as a one-step workflow; false when one with the same steps exists. */
    fun saveAsWorkflow(command: String): Boolean {
        if (settings.workflows.any { it.steps == listOf(command) }) return false
        updateSettings { it.copy(workflows = it.workflows + Workflow(commandKey(command), listOf(command))) }
        return true
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    private fun saveHistory() {
        if (!historyLoaded) return // would overwrite the file with a partial list
        val snapshot = history.toList()
        scope.launch(Dispatchers.Default) { runCatching { services.saveHistory(snapshot) } }
    }

    fun runInFocused(command: String) {
        val pane = focusedPane ?: newTerminalTab().focused
        run(pane, command)
    }

    fun runWorkflow(workflow: Workflow) {
        val cmd = workflow.steps.joinToString(" && ")
        runInFocused(cmd)
    }

    fun connect(host: HostEntry) {
        val saved = settings.hosts.firstOrNull { it.alias == host.alias }
        val target = if (host.source == "ssh-config") host.alias else host.address.substringBefore(':')
        val cmd = buildString {
            append("ssh ")
            if (saved?.forwardAgent == true) append("-A ")
            if (host.source != "ssh-config" && host.port != 22) append("-p ${host.port} ")
            if (host.source != "ssh-config" && host.proxyJump.isNotBlank()) append("-J ${host.proxyJump} ")
            if (host.source != "ssh-config" && host.identityFile.isNotBlank()) append("-i ${host.identityFile} ")
            append(target)
            if (!saved?.startupCommand.isNullOrBlank()) append(" -t '${saved!!.startupCommand}; exec \$SHELL -l'")
        }
        newTerminalTab(command = cmd)
    }

    fun refreshContext(pane: Pane) {
        val cwd = pane.session.cwd
        scope.launch(Dispatchers.Default) {
            val ctx = runCatching { services.probeContext(cwd) }.getOrDefault(PromptContext())
            withContext(Dispatchers.Main) { pane.context = ctx }
        }
    }

    fun rememberDir(path: String) {
        val now = nowMillis()
        updateSettings { s ->
            s.copy(recentDirs = (listOf(RecentDir(path, now)) + s.recentDirs.filter { it.path != path }).take(12))
        }
    }

    fun hasRunningProcesses(): List<Pair<Pane, String>> = tabs.filterIsInstance<Tab.Terminal>()
        .flatMap { it.root.panes() }
        .mapNotNull { p -> p.session.currentBlock()?.let { p to it.command } }

    companion object {
        const val MaxHistory = 5000
    }

    fun shutdown() {
        tabs.filterIsInstance<Tab.Terminal>().flatMap { it.root.panes() }.forEach { it.session.close() }
    }
}
