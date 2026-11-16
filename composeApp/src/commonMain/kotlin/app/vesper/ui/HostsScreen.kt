package app.vesper.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.app.HostEntry
import app.vesper.app.PaneKind
import app.vesper.app.SavedHost
import app.vesper.app.Tab
import app.vesper.ui.components.Badge
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.RadiusMd
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.SearchField
import app.vesper.ui.components.StatusDot
import app.vesper.ui.components.Toggle
import app.vesper.ui.components.Tone
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper

private fun connectedAliases(state: AppState): Set<String> = state.tabs.filterIsInstance<Tab.Terminal>()
    .flatMap { it.root.panes() }
    .mapNotNull { p ->
        val cmd = p.session.currentBlock()?.command ?: return@mapNotNull null
        if (p.kind() != PaneKind.Ssh) return@mapNotNull null
        cmd.split(' ').lastOrNull { it.isNotBlank() && !it.startsWith("-") && !it.startsWith("'") }
    }.toSet()

private fun tagTone(tag: String): Tone = when {
    tag.contains("prod", ignoreCase = true) -> Tone.Danger
    tag.contains("stag", ignoreCase = true) -> Tone.Warning
    tag.isEmpty() -> Tone.Neutral
    else -> Tone.Info
}

@Composable
fun HostsScreen(state: AppState, tab: Tab.Hosts) {
    val c = Vesper.colors
    val type = Vesper.type
    var query by remember { mutableStateOf("") }
    val hosts = state.allHosts()
    val connected = connectedAliases(state)
    val filtered = hosts.filter {
        query.isBlank() || listOf(it.alias, it.hostName, it.user, it.tag, it.group).any { f -> f.contains(query, ignoreCase = true) }
    }
    val selected = hosts.firstOrNull { it.alias == tab.selected } ?: hosts.firstOrNull()

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(266.dp).fillMaxHeight().padding(horizontal = 10.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                VText("Hosts", type.heading, c.textPrimary, Modifier.weight(1f))
                VButton("New host", {
                    val alias = generateSequence(1) { it + 1 }.map { if (it == 1) "new-host" else "new-host-$it" }.first { a -> hosts.none { it.alias == a } }
                    state.updateSettings { it.copy(hosts = it.hosts + SavedHost(alias, "")) }
                    tab.selected = alias
                }, icon = Icons.Plus, kind = ButtonKind.Accent)
            }
            SearchField(query, { query = it }, "Search hosts, tags, IPs…", Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (hosts.isEmpty()) {
                    VText("No hosts yet. Add one, or define them in ~/.ssh/config.", type.caption, c.textMuted, Modifier.padding(8.dp), maxLines = 3)
                }
                filtered.groupBy { it.group.ifEmpty { "Saved" } }.forEach { (group, list) ->
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
                        Overline(group, Modifier.weight(1f))
                        VText(list.size.toString(), type.meta, c.textMuted)
                    }
                    list.forEach { h ->
                        HostRow(h, active = h.alias == selected?.alias, connected = h.alias in connected) { tab.selected = h.alias }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (selected != null) HostDetail(state, tab, selected, selected.alias in connected)
        }
    }
}

@Composable
private fun HostRow(h: HostEntry, active: Boolean, connected: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .background(if (active) c.bgActive else Color.Transparent, RadiusLg)
            .border(1.dp, if (active) c.borderDefault else Color.Transparent, RadiusLg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).background(c.bgSurface, RadiusMd).border(1.dp, c.borderSubtle, RadiusMd),
            contentAlignment = Alignment.Center,
        ) { VIcon(Icons.Server, 14.dp) }
        Column(Modifier.weight(1f)) {
            VText(h.alias, type.bodyMedium, c.textPrimary)
            VText(h.address, type.meta, c.textMuted)
        }
        if (h.tag.isNotEmpty()) Badge(h.tag, tagTone(h.tag))
        StatusDot(if (connected) c.success else c.borderStrong, 6.dp)
    }
}

@Composable
private fun HostDetail(state: AppState, tab: Tab.Hosts, h: HostEntry, connected: Boolean) {
    val c = Vesper.colors
    val type = Vesper.type
    val savedIndex = state.settings.hosts.indexOfFirst { it.alias == h.alias }
    val saved = state.settings.hosts.getOrNull(savedIndex)
    fun update(f: (SavedHost) -> SavedHost) {
        if (savedIndex < 0) return
        state.updateSettings { s -> s.copy(hosts = s.hosts.mapIndexed { i, x -> if (i == savedIndex) f(x) else x }) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Column(Modifier.widthIn(max = 720.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(c.accentSoft, RadiusLg).border(1.dp, c.accentStrong.copy(alpha = 0.5f), RadiusLg),
                    contentAlignment = Alignment.Center,
                ) { VIcon(Icons.Server, 18.dp, c.accentText) }
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VText(h.alias, type.display, c.textPrimary)
                        if (h.tag.isNotEmpty()) Badge(h.tag, tagTone(h.tag))
                        if (connected) Badge("connected", Tone.Success)
                    }
                    VText(if (saved != null) "Saved in Vesper" else "From ~/.ssh/config", type.caption, c.textMuted)
                }
                if (saved == null) {
                    VButton("Save copy", {
                        state.updateSettings {
                            it.copy(hosts = it.hosts + SavedHost(h.alias + "-copy", h.hostName.ifEmpty { h.alias }, h.user, h.port, h.tag, "Saved", h.identityFile, h.proxyJump))
                        }
                        tab.selected = h.alias + "-copy"
                    }, icon = Icons.Copy)
                } else {
                    VButton("Duplicate", {
                        state.updateSettings { it.copy(hosts = it.hosts + saved.copy(alias = saved.alias + "-copy")) }
                        tab.selected = saved.alias + "-copy"
                    }, icon = Icons.Copy, kind = ButtonKind.Ghost)
                    VButton("Delete", {
                        state.updateSettings { s -> s.copy(hosts = s.hosts.filterIndexed { i, _ -> i != savedIndex }) }
                        tab.selected = null
                    }, icon = Icons.Trash, kind = ButtonKind.Danger)
                }
                VButton("Connect", { state.connect(h) }, icon = Icons.Zap, kind = ButtonKind.Primary, enabled = h.hostName.isNotBlank() || saved == null)
            }
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.fillMaxWidth().background(c.bgPanel, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VText("Connection", type.title, c.textPrimary)
                val readOnly = saved == null
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Name", h.alias, readOnly, Modifier.weight(1f)) { v ->
                        update { it.copy(alias = v) }
                        tab.selected = v
                    }
                    Field("Host", h.hostName, readOnly, Modifier.weight(1f)) { v -> update { it.copy(host = v) } }
                    Field("Port", h.port.toString(), readOnly, Modifier.width(80.dp)) { v -> v.toIntOrNull()?.let { p -> update { it.copy(port = p) } } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("User", h.user, readOnly, Modifier.weight(1f)) { v -> update { it.copy(user = v) } }
                    Field("Identity file", h.identityFile, readOnly, Modifier.weight(1f)) { v -> update { it.copy(identityFile = v) } }
                    Field("Jump host", h.proxyJump, readOnly, Modifier.weight(1f)) { v -> update { it.copy(jumpHost = v) } }
                }
                if (saved != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Tag", saved.tag, false, Modifier.weight(1f)) { v -> update { it.copy(tag = v) } }
                        Field("Group", saved.group, false, Modifier.weight(1f)) { v -> update { it.copy(group = v) } }
                    }
                }
            }
            if (saved != null) {
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().background(c.bgPanel, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl)) {
                    OptionRow("Confirm destructive commands", "Ask before rm, DROP, kubectl delete on this host") {
                        Toggle(saved.confirmDestructive, { v -> update { it.copy(confirmDestructive = v) } })
                    }
                    OptionRow("Forward SSH agent", "Lets git on the host use your local keys") {
                        Toggle(saved.forwardAgent, { v -> update { it.copy(forwardAgent = v) } })
                    }
                    OptionRow("Startup command", "Runs after the remote shell is ready", last = true) {
                        TextInput(saved.startupCommand, "cd ~/app", Modifier.width(220.dp), mono = true) { v -> update { it.copy(startupCommand = v) } }
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                VText("Hosts from ~/.ssh/config are read-only here; edit the file or save a copy.", type.caption, c.textMuted, maxLines = 2)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, readOnly: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    val c = Vesper.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        VText(label, Vesper.type.caption, c.textSecondary)
        if (readOnly) {
            Box(Modifier.fillMaxWidth().background(c.bgInset, RadiusMd).border(1.dp, c.borderSubtle, RadiusMd).padding(horizontal = 10.dp, vertical = 7.dp)) {
                VText(value.ifEmpty { "—" }, Vesper.type.codeSmall, c.textSecondary)
            }
        } else {
            TextInput(value, "", Modifier.fillMaxWidth(), mono = true, onChange = onChange)
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, last: Boolean = false, control: @Composable () -> Unit) {
    val c = Vesper.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VText(title, Vesper.type.bodyMedium, c.textPrimary)
            VText(subtitle, Vesper.type.caption, c.textMuted)
        }
        control()
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
}
