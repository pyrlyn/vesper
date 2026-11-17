package app.vesper.ui

import kotlin.math.roundToInt
import app.vesper.app.WindowMaterial
import app.vesper.ui.components.VSlider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.vesper.app.AppState
import app.vesper.app.DefaultWorkflows
import app.vesper.app.SettingsSection
import app.vesper.app.Tab
import app.vesper.app.Workflow
import app.vesper.platform.HostOs
import app.vesper.ui.components.Badge
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.Kbd
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.RadiusMd
import app.vesper.ui.components.RadiusSm
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.SearchField
import app.vesper.ui.components.Toggle
import app.vesper.ui.components.Tone
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.components.popover
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.AccentId
import app.vesper.ui.theme.ThemeId
import app.vesper.ui.theme.Vesper
import app.vesper.ui.theme.colorsFor

private val SectionIcons = mapOf(
    SettingsSection.General to Icons.Sliders,
    SettingsSection.Appearance to Icons.Palette,
    SettingsSection.Terminal to Icons.Terminal,
    SettingsSection.Keybindings to Icons.Keyboard,
    SettingsSection.Hosts to Icons.Server,
    SettingsSection.Workflows to Icons.Play,
    SettingsSection.Privacy to Icons.Lock,
    SettingsSection.About to Icons.Info,
)

@Composable
fun SettingsScreen(state: AppState, tab: Tab.Settings) {
    val c = Vesper.colors
    val type = Vesper.type
    var query by remember { mutableStateOf("") }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(232.dp).fillMaxHeight().background(c.bgSidebar).padding(horizontal = 10.dp, vertical = 16.dp)) {
            VText("Settings", type.heading, c.textPrimary, Modifier.padding(start = 6.dp, bottom = 14.dp))
            SearchField(query, { query = it }, "Search settings", Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            SettingsSection.entries
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || sectionKeywords(it).contains(query.lowercase()) }
                .forEach { s ->
                    val active = s == tab.section
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (active) c.bgActive else Color.Transparent, RadiusMd)
                            .clickable { tab.section = s }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VIcon(SectionIcons[s] ?: Icons.Settings, 14.dp, if (active) c.textPrimary else c.iconDefault)
                        VText(s.title, type.body, if (active) c.textPrimary else c.textSecondary)
                    }
                }
            Spacer(Modifier.weight(1f))
            VText("Vesper ${state.services.appVersion}", type.meta, c.textMuted, Modifier.padding(start = 6.dp))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp),
        ) {
            Column(Modifier.widthIn(max = 640.dp)) {
                when (tab.section) {
                    SettingsSection.General -> GeneralSection(state)
                    SettingsSection.Appearance -> AppearanceSection(state)
                    SettingsSection.Terminal -> TerminalSection(state)
                    SettingsSection.Keybindings -> KeybindingsSection(state)
                    SettingsSection.Hosts -> HostsSection(state)
                    SettingsSection.Workflows -> WorkflowsSection(state)
                    SettingsSection.Privacy -> PrivacySection(state)
                    SettingsSection.About -> AboutSection(state)
                }
            }
        }
        if (tab.section == SettingsSection.Appearance) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
            LivePreview(state)
        }
    }
}

private fun sectionKeywords(s: SettingsSection): String = when (s) {
    SettingsSection.Appearance -> "theme color accent font ligatures dark light"
    SettingsSection.Terminal -> "shell font size scrollback cursor"
    SettingsSection.General -> "quit confirm sidebar monitor"
    SettingsSection.Keybindings -> "shortcuts keys"
    SettingsSection.Hosts -> "ssh config"
    SettingsSection.Workflows -> "commands steps"
    SettingsSection.Privacy -> "history clear"
    SettingsSection.About -> "version license"
}

@Composable
private fun Header(title: String, subtitle: String) {
    val c = Vesper.colors
    val type = Vesper.type
    VText(title, type.display, c.textPrimary)
    Spacer(Modifier.height(6.dp))
    VText(subtitle, type.body, c.textMuted, maxLines = 2)
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Vesper.colors
    Overline(title, Modifier.padding(bottom = 8.dp))
    Column(
        Modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl),
        content = content,
    )
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun SettingRow(title: String, subtitle: String, last: Boolean = false, control: @Composable () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            VText(title, type.bodyMedium, c.textPrimary)
            Spacer(Modifier.height(2.dp))
            VText(subtitle, type.caption, c.textMuted, maxLines = 2)
        }
        control()
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
}

@Composable
private fun AppearanceSection(state: AppState) {
    val c = Vesper.colors
    val s = state.settings
    val os = state.services.os
    Header("Appearance", "Theme, accent and typography. Changes apply live.")
    Overline("Theme", Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeId.entries.forEach { t ->
            ThemeCard(t, s.accent, active = s.theme == t && !s.followSystem, Modifier.weight(1f)) {
                state.updateSettings { it.copy(theme = t, followSystem = false) }
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    Group("Color") {
        val sys = when (os) {
            HostOs.MacOS -> "macOS"
            HostOs.Windows -> "Windows"
            HostOs.Linux -> "your desktop"
        }
        SettingRow("Follow system appearance", "Switch between Vesper Dark and Vesper Light with $sys") {
            Toggle(s.followSystem, { v -> state.updateSettings { it.copy(followSystem = v) } })
        }
        SettingRow("Accent color", "Used for focus, running blocks and highlights", last = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentId.entries.forEach { a ->
                    val active = a == s.accent
                    Box(
                        Modifier.size(20.dp)
                            .border(if (active) 2.dp else 0.dp, if (active) a.swatch else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            .background(a.swatch, CircleShape)
                            .clickable { state.updateSettings { it.copy(accent = a) } }
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        }
    }
    if (os == HostOs.MacOS) WindowMaterialSection(state)
    Group("Typography") {
        SettingRow("Font family", "Monospace font for blocks and prompt") {
            val fonts = remember { listOf("JetBrains Mono") + state.services.installedMonoFonts().filter { it != "JetBrains Mono" } }
            Dropdown(s.fontFamily, fonts, Modifier.width(220.dp)) { f -> state.updateSettings { it.copy(fontFamily = f) } }
        }
        SettingRow("Font size", "Scales blocks, prompt and full-screen apps") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.Minus, { state.updateSettings { it.copy(fontScale = (it.fontScale - 0.1f).coerceAtLeast(0.7f)) } }, size = 12.dp, padding = 6.dp)
                VText("${(s.fontScale * 100).toInt()}%", Vesper.type.codeSmall, c.textPrimary, Modifier.width(44.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(Icons.Plus, { state.updateSettings { it.copy(fontScale = (it.fontScale + 0.1f).coerceAtMost(1.8f)) } }, size = 12.dp, padding = 6.dp)
            }
        }
        SettingRow("Ligatures", "Render programming ligatures like → and !=", last = true) {
            Toggle(s.ligatures, { v -> state.updateSettings { it.copy(ligatures = v) } })
        }
    }
}

/** Settings › Appearance › Window material (macOS Liquid Glass). */
@Composable
private fun WindowMaterialSection(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val s = state.settings
    val glass = s.material == WindowMaterial.Glass
    Overline("Window material", Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MaterialCard("Solid", "no transparency", glass = false, active = !glass, Modifier.weight(1f)) {
            state.updateSettings { it.copy(material = WindowMaterial.Solid) }
        }
        MaterialCard("Liquid Glass", "macOS", glass = true, active = glass, Modifier.weight(1f)) {
            state.updateSettings { it.copy(material = WindowMaterial.Glass) }
        }
        Spacer(Modifier.weight(2f))
    }
    Spacer(Modifier.height(22.dp))
    if (glass) {
        Group("Fine-tune") {
            SettingRow("Opacity", "How much of the desktop shows through") {
                SliderValue(s.glassOpacity, 0.2f..1f, "${(s.glassOpacity * 100).roundToInt()}%") { v ->
                    state.updateSettings { it.copy(glassOpacity = (v * 100).roundToInt() / 100f) }
                }
            }
            SettingRow("Blur", "Higher values make text easier to read") {
                SliderValue(s.glassBlur.toFloat(), 0f..100f, "${s.glassBlur} px") { v ->
                    state.updateSettings { it.copy(glassBlur = v.roundToInt()) }
                }
            }
            SettingRow("Tint with accent color", "Mix a little ${s.accent.name.lowercase()} into the glass") {
                Toggle(s.glassTint, { v -> state.updateSettings { it.copy(glassTint = v) } })
            }
            SettingRow("Reduce transparency", "Also follows macOS Accessibility › Display", last = true) {
                Toggle(s.reduceTransparency, { v -> state.updateSettings { it.copy(reduceTransparency = v) } })
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(c.accentSoft, RadiusXl)
                .border(1.dp, c.accentDeep, RadiusXl)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIcon(Icons.Zap, 16.dp, c.accent)
            Column {
                VText("Glass is GPU-accelerated", type.bodyMedium, c.textPrimary)
                VText("macOS composites the blur. Turn on Reduce transparency to save power on battery.", type.caption, c.textSecondary, maxLines = 2)
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun SliderValue(value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        VSlider(value, range, onChange)
        VText(label, Vesper.type.codeSmall, Vesper.colors.textCode, Modifier.width(44.dp), align = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun MaterialCard(title: String, subtitle: String, glass: Boolean, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    val accent = c.accentStrong
    Column(
        modifier
            .background(if (active) c.accentSoft else c.bgSurface, RadiusLg)
            .border(1.dp, if (active) accent else c.borderSubtle, RadiusLg)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(7.dp),
    ) {
        // Thumbnail: a window over a coloured desktop; the glass one lets the colour through.
        Box(
            Modifier.fillMaxWidth().height(84.dp)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF2A1F5C), accent.copy(alpha = 0.55f))), RadiusMd)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(if (glass) Color(0xFF0D0F15).copy(alpha = 0.45f) else Color(0xFF15171F), RadiusMd)
                    .border(1.dp, Color.White.copy(alpha = if (glass) 0.18f else 0.08f), RadiusMd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.75f).height(3.dp).background(accent, CircleShape))
                Box(Modifier.fillMaxWidth(0.55f).height(3.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
                Box(Modifier.fillMaxWidth(0.65f).height(3.dp).background(c.success, CircleShape))
            }
        }
        Column(Modifier.padding(start = 4.dp, top = 9.dp, bottom = 2.dp)) {
            VText(title, type.bodySmall, if (active) c.textPrimary else c.textSecondary)
            VText(subtitle, type.codeSmall, if (active) c.textSecondary else c.textMuted)
        }
    }
}

@Composable
private fun ThemeCard(theme: ThemeId, accent: AccentId, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    val t = colorsFor(theme, accent)
    Column(
        modifier
            .background(c.bgSurface, RadiusLg)
            .border(1.dp, if (active) c.accentStrong else c.borderSubtle, RadiusLg)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(7.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().height(58.dp).background(t.bgWindow, RadiusMd).border(1.dp, t.borderSubtle, RadiusMd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEach { Box(Modifier.size(4.dp).background(it, CircleShape)) }
            }
            Box(Modifier.fillMaxWidth(0.55f).height(3.dp).background(t.accent, CircleShape))
            Box(Modifier.fillMaxWidth(0.85f).height(3.dp).background(t.textPrimary.copy(alpha = 0.85f), CircleShape))
            Box(Modifier.fillMaxWidth(0.7f).height(3.dp).background(t.success, CircleShape))
            Box(Modifier.fillMaxWidth(0.4f).height(3.dp).background(t.textMuted, CircleShape))
        }
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 9.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            VText(theme.title, type.bodySmall, if (active) c.textPrimary else c.textSecondary, Modifier.weight(1f))
            if (active) VIcon(Icons.CheckCircle, 14.dp, c.accent)
        }
    }
}

@Composable
private fun LivePreview(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(Modifier.width(260.dp).fillMaxHeight().padding(18.dp)) {
        Overline("Live preview", Modifier.padding(bottom = 10.dp))
        Column(
            Modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VIcon(Icons.CheckCircle, 14.dp, c.success)
                VText("› npm test", type.codeSmall, c.textPrimary, Modifier.weight(1f))
                Badge("12 passed", Tone.Success)
            }
            Spacer(Modifier.height(4.dp))
            VText("✓ invoice.test.ts      312ms", type.codeSmall, c.success)
            VText("✓ pdf-export.test.ts   488ms", type.codeSmall, c.success)
            VText("→ http://localhost:5173/", type.codeSmall, c.info)
            VText("const total = items.reduce(…)", type.codeSmall, c.textCode)
            VText("warn: 1 deprecated API", type.codeSmall, c.warning)
        }
        Spacer(Modifier.height(18.dp))
        Overline("ANSI palette", Modifier.padding(bottom = 10.dp))
        c.ansi.chunked(8).forEach { row ->
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { color ->
                    Box(Modifier.size(22.dp).background(color, RadiusSm).border(1.dp, c.borderSubtle, RadiusSm))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VButton("Reset", {
                state.updateSettings {
                    it.copy(theme = ThemeId.VesperDark, accent = AccentId.Violet, fontFamily = "JetBrains Mono", fontScale = 1f, ligatures = false, followSystem = false)
                }
            }, icon = Icons.RotateCcw, kind = ButtonKind.Ghost)
            VButton("Open file", { state.services.revealInFileManager(state.services.configPath) }, icon = Icons.Upload)
        }
    }
}

@Composable
private fun GeneralSection(state: AppState) {
    val s = state.settings
    Header("General", "Window, startup and quitting.")
    Group("Window") {
        SettingRow("Show sidebar", "Sessions, workflows and recent history") {
            Toggle(s.showSidebar, { v -> state.updateSettings { it.copy(showSidebar = v) } })
        }
        SettingRow("Show monitor", "CPU, memory, processes and ports on the right", last = true) {
            Toggle(s.showMonitor, { v -> state.updateSettings { it.copy(showMonitor = v) } })
        }
    }
    Group("Quitting") {
        SettingRow("Confirm before quitting", "Ask when commands are still running", last = true) {
            Toggle(s.confirmQuit, { v -> state.updateSettings { it.copy(confirmQuit = v) } })
        }
    }
}

@Composable
private fun TerminalSection(state: AppState) {
    val s = state.settings
    Header("Terminal", "Shell and input behaviour. A new shell applies to new tabs.")
    Group("Shell") {
        SettingRow("Shell", "Leave empty to use your login shell") {
            TextInput(s.shell, "default", Modifier.width(220.dp)) { v -> state.updateSettings { it.copy(shell = v.trim()) } }
        }
        SettingRow("Blinking cursor", "In full-screen programs like vim", last = true) {
            Toggle(s.cursorBlink, { v -> state.updateSettings { it.copy(cursorBlink = v) } })
        }
    }
    Group("Shell integration") {
        SettingRow(
            "Blocks",
            "zsh, bash, fish and PowerShell report prompts and exit codes so output is grouped into blocks. Other shells run as a classic terminal.",
            last = true,
        ) { Badge("automatic", Tone.Success) }
    }
}

private fun keybindings(os: HostOs): List<Pair<String, String>> {
    val m = os.mod
    val s = os.shift
    return listOf(
        "Command palette" to "${m}K",
        "New tab" to "${m}T",
        "Close pane / tab" to "${m}W",
        "Go to tab 1–9" to "${m}1 … ${m}9",
        "Previous / next tab" to "$m[  $m]",
        "Split right" to "$m\\  or  ${m}D",
        "Split down" to "$m$s\\  or  $m${s}D",
        "Zoom pane" to "$m⏎",
        "Move focus between panes" to "$m${os.alt}←→",
        "Toggle sidebar" to "${m}B",
        "Toggle monitor" to "${m}J",
        "Hosts" to "$m${s}H",
        "History" to "${m}Y",
        "Find in output" to "${m}F",
        "Settings" to "$m,",
        "Accept suggestion" to "⇥",
        "Previous command" to "↑",
        "New line in prompt" to "${s}⏎",
        "Interrupt" to "Ctrl+C",
    )
}

@Composable
private fun KeybindingsSection(state: AppState) {
    val c = Vesper.colors
    Header("Keybindings", if (state.services.os == HostOs.MacOS) "Shortcuts use ⌘." else "Shortcuts use Ctrl; add Shift while a full-screen program (vim, htop) has focus.")
    Group("Shortcuts") {
        val list = keybindings(state.services.os)
        list.forEachIndexed { i, (name, key) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                VText(name, Vesper.type.body, c.textPrimary, Modifier.weight(1f))
                Kbd(key)
            }
            if (i < list.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
        }
    }
}

@Composable
private fun HostsSection(state: AppState) {
    Header("Hosts & SSH", "Hosts come from ~/.ssh/config plus the ones you save in Vesper.")
    Group("Sources") {
        SettingRow("SSH config", "${state.sshHosts.size} hosts in ~/.ssh/config") {
            VButton("Reveal", { state.services.revealInFileManager(state.services.homeDir + "/.ssh/config") }, icon = Icons.Folder)
        }
        SettingRow("Saved in Vesper", "${state.settings.hosts.size} hosts", last = true) {
            VButton("Open hosts", { state.openHosts() }, icon = Icons.Server, kind = ButtonKind.Accent)
        }
    }
}

@Composable
private fun WorkflowsSection(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    Header("Workflows", "Saved multi-step commands. Steps run in order and stop at the first failure.")
    state.settings.workflows.forEachIndexed { index, wf ->
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(wf.name, "Name", Modifier.weight(1f)) { v -> updateWorkflow(state, index) { it.copy(name = v) } }
                VButton("Run", { state.runWorkflow(wf) }, icon = Icons.Play, kind = ButtonKind.Accent)
                IconButton(Icons.Trash, {
                    state.updateSettings { s -> s.copy(workflows = s.workflows.filterIndexed { i, _ -> i != index }) }
                }, size = 14.dp, tint = c.danger)
            }
            TextInput(wf.description, "Description", Modifier.fillMaxWidth()) { v -> updateWorkflow(state, index) { it.copy(description = v) } }
            VText("Steps, one per line", type.caption, c.textMuted)
            TextInput(wf.steps.joinToString("\n"), "git pull --rebase", Modifier.fillMaxWidth(), mono = true, singleLine = false) { v ->
                updateWorkflow(state, index) { it.copy(steps = v.lines().filter { l -> l.isNotBlank() }) }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VButton("New workflow", {
            state.updateSettings { it.copy(workflows = it.workflows + Workflow("New workflow", listOf("echo hello"))) }
        }, icon = Icons.Plus, kind = ButtonKind.Accent)
        VButton("Restore defaults", { state.updateSettings { it.copy(workflows = DefaultWorkflows) } }, icon = Icons.RotateCcw, kind = ButtonKind.Ghost)
    }
}

private fun updateWorkflow(state: AppState, index: Int, f: (Workflow) -> Workflow) {
    state.updateSettings { s -> s.copy(workflows = s.workflows.mapIndexed { i, w -> if (i == index) f(w) else w }) }
}

@Composable
private fun PrivacySection(state: AppState) {
    Header("Privacy", "Everything stays on this machine. Vesper sends nothing anywhere.")
    Group("History") {
        SettingRow("Vesper history", "${state.history.size} commands saved for History and suggestions") {
            VButton("Clear", { state.clearHistory() }, icon = Icons.Trash, kind = ButtonKind.Danger)
        }
        SettingRow("Recent directories", "${state.settings.recentDirs.size} directories", last = true) {
            VButton("Clear", { state.updateSettings { it.copy(recentDirs = emptyList()) } }, icon = Icons.Trash, kind = ButtonKind.Danger)
        }
    }
}

@Composable
private fun AboutSection(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    Header("About", "A modern terminal with command blocks, built with Kotlin Multiplatform and Compose.")
    Group("Vesper") {
        SettingRow("Version", state.services.appVersion) { Badge(state.services.os.name, Tone.Neutral) }
        SettingRow("Settings file", state.services.displayPath(state.services.configPath)) {
            VButton("Reveal", { state.services.revealInFileManager(state.services.configPath) }, icon = Icons.Folder)
        }
        SettingRow("Fonts", "JetBrains Mono and Inter, SIL Open Font License 1.1", last = true) {}
    }
    VText("Terminal emulation: xterm-256color with truecolor.", type.caption, c.textMuted)
}

@Composable
fun TextInput(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    val c = Vesper.colors
    val style: TextStyle = if (mono) Vesper.type.codeSmall else Vesper.type.bodySmall
    Box(
        modifier
            .background(c.bgInset, RadiusMd)
            .border(1.dp, c.borderDefault, RadiusMd)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (value.isEmpty()) VText(placeholder, style, c.textMuted)
        BasicTextField(
            value,
            onChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            textStyle = style.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accentStrong),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun Dropdown(value: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .background(c.bgInset, RadiusMd)
                .border(1.dp, c.borderDefault, RadiusMd)
                .clickable { open = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText(value, type.bodySmall, c.textPrimary, Modifier.weight(1f))
            VIcon(Icons.ChevronDown, 14.dp)
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = androidx.compose.ui.unit.IntOffset(0, 80),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                LazyColumn(Modifier.width(260.dp).heightIn(max = 320.dp).popover(c).padding(5.dp)) {
                    items(options) { o ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (o == value) c.bgActive else Color.Transparent, RadiusSm)
                                .clickable {
                                    open = false
                                    onSelect(o)
                                }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VText(o, type.bodySmall, c.textPrimary, Modifier.weight(1f))
                            if (o == value) VIcon(Icons.Check, 13.dp, c.accent)
                        }
                    }
                }
            }
        }
    }
}
