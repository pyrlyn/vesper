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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.app.ShellSetup
import app.vesper.platform.HostOs
import app.vesper.ui.components.Badge
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.Checkbox
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.Tone
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.components.popover
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.AccentId
import app.vesper.ui.theme.ThemeId
import app.vesper.ui.theme.Vesper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Steps = listOf("Welcome", "Import shell setup", "Pick a theme", "Set up the agent")

/** First-launch setup over the window; Skip or finishing sets Settings.onboarded. */
@Composable
fun Onboarding(state: AppState) {
    val c = Vesper.colors
    var step by remember { mutableIntStateOf(0) }
    var setup by remember { mutableStateOf<ShellSetup?>(null) }
    var importHistory by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        setup = withContext(Dispatchers.IO) { runCatching { state.services.shellSetup() }.getOrNull() }
    }

    Box(
        Modifier.fillMaxSize().background(c.bgApp.copy(alpha = 0.92f)).clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.width(720.dp).height(440.dp).popover(c, RadiusXl)) {
            // Step rail
            Column(
                Modifier.width(214.dp).fillMaxHeight().background(c.bgSidebar).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(c.accentSoft, RadiusLg), contentAlignment = Alignment.Center) {
                        VIcon(Icons.Terminal, 14.dp, c.accentText)
                    }
                    VText("Welcome to Vesper", Vesper.type.bodyMedium, c.textPrimary)
                }
                Steps.forEachIndexed { i, title -> StepItem(i, title, current = step, onClick = { if (i < step) step = i }) }
            }
            // Step content
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 26.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        0 -> WelcomeStep()
                        1 -> ImportStep(state, setup, importHistory, imported) { importHistory = it }
                        2 -> ThemeStep(state)
                        else -> AgentStep(state)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) VButton("Back", { step-- }, kind = ButtonKind.Ghost)
                    Spacer(Modifier.weight(1f))
                    if (step < Steps.lastIndex) VButton("Skip", { state.finishOnboarding() }, kind = ButtonKind.Ghost)
                    when (step) {
                        0 -> VButton("Get started", { step++ }, icon = Icons.ArrowRightToLine, kind = ButtonKind.Accent)
                        1 -> {
                            val canImport = importHistory && imported == null && (setup?.historyCommands ?: 0) > 0
                            VButton(
                                when {
                                    importing -> "Importing…"
                                    canImport -> "Import & continue"
                                    else -> "Continue"
                                },
                                {
                                    if (!canImport) {
                                        step++
                                    } else {
                                        importing = true
                                        scope.launch {
                                            imported = state.importShellHistory()
                                            importing = false
                                            step++
                                        }
                                    }
                                },
                                icon = Icons.ArrowRightToLine,
                                kind = ButtonKind.Accent,
                                enabled = !importing,
                            )
                        }
                        2 -> VButton("Continue", { step++ }, icon = Icons.ArrowRightToLine, kind = ButtonKind.Accent)
                        else -> VButton("Start using Vesper", { state.finishOnboarding() }, icon = Icons.Check, kind = ButtonKind.Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(index: Int, title: String, current: Int, onClick: () -> Unit) {
    val c = Vesper.colors
    val done = index < current
    val active = index == current
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) c.bgActive else Color.Transparent, RadiusLg)
            .clickable(enabled = done, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).background(
                when {
                    done -> c.success.copy(alpha = 0.18f)
                    active -> c.accentStrong
                    else -> c.bgActive
                },
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) VIcon(Icons.Check, 11.dp, c.success)
            else VText("${index + 1}", Vesper.type.micro, if (active) Color.White else c.textMuted)
        }
        VText(title, Vesper.type.bodySmall, if (done || active) c.textPrimary else c.textMuted)
    }
}

@Composable
private fun StepTitle(title: String, text: String) {
    VText(title, Vesper.type.display, Vesper.colors.textPrimary)
    VText(text, Vesper.type.body, Vesper.colors.textSecondary, maxLines = 3)
}

@Composable
private fun WelcomeStep() {
    StepTitle("A terminal that keeps track", "Vesper runs your usual shell. A minute of setup and it feels like home.")
    Spacer(Modifier.height(4.dp))
    Feature(Icons.Terminal, "Command blocks", "Every command and its output is a block you can copy, rerun or search.")
    Feature(Icons.History, "History with context", "Exit codes, durations and directories for everything you run.")
    Feature(Icons.Server, "Hosts and monitor", "Your SSH hosts one click away, and the machine's vitals beside your work.")
}

@Composable
private fun AgentStep(state: AppState) {
    val c = Vesper.colors
    StepTitle("Set up the agent", "Optional. Start a line with # to ask in plain language; failed commands get an Explain button.")
    Spacer(Modifier.height(4.dp))
    Feature(Icons.Sparkles, "Plans you approve", "The agent drafts commands; nothing runs until you press Run.")
    Feature(Icons.Lock, "Secrets stay local", "Keys, tokens and .env files are redacted before anything is sent.")
    Spacer(Modifier.height(4.dp))
    val hint = state.agent.keyHint
    if (hint != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VIcon(Icons.CheckCircle, 15.dp, c.success)
            VText(if (hint == "ANTHROPIC_API_KEY") "Using ANTHROPIC_API_KEY from your environment" else "API key saved", Vesper.type.bodySmall, c.textPrimary)
        }
    } else {
        VText("Anthropic API key", Vesper.type.bodyMedium, c.textPrimary)
        ApiKeyField(state)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    val c = Vesper.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(30.dp).background(c.bgSurface, RadiusLg).border(1.dp, c.borderSubtle, RadiusLg), contentAlignment = Alignment.Center) {
            VIcon(icon, 15.dp, c.accentText)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, Vesper.type.bodyMedium, c.textPrimary)
            VText(text, Vesper.type.caption, c.textMuted, maxLines = 2)
        }
    }
}

@Composable
private fun ImportStep(state: AppState, setup: ShellSetup?, importHistory: Boolean, imported: Int?, onImportHistory: (Boolean) -> Unit) {
    val c = Vesper.colors
    val computer = if (state.services.os == HostOs.MacOS) "Mac" else "computer"
    StepTitle("Bring your shell with you", "We found these on your $computer. Nothing is changed in the original files — Vesper reads them on startup.")
    if (setup == null) {
        VText("Looking at your shell setup…", Vesper.type.caption, c.textMuted)
        return
    }
    val shellName = setup.shell.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe")
    Column(Modifier.fillMaxWidth().background(c.bgSurface, RadiusLg).border(1.dp, c.borderSubtle, RadiusLg)) {
        val rows = buildList<@Composable () -> Unit> {
            setup.rcFile?.let { rc ->
                add { SetupRow(Icons.Terminal, rc, "${setup.aliases} aliases · ${setup.functions} functions") { Badge("loaded by $shellName", Tone.Neutral) } }
            }
            val hosts = state.sshHosts.size
            add { SetupRow(Icons.Server, "~/.ssh/config", if (hosts == 0) "no hosts yet" else "$hosts host${if (hosts == 1) "" else "s"}") { Badge("shown in Hosts", Tone.Neutral) } }
            if (setup.historyFile != null && setup.historyCommands > 0) {
                add {
                    SetupRow(Icons.History, "Shell history", "${"%,d".format(setup.historyCommands)} commands from ${setup.historyFile}") {
                        if (imported != null) Badge("$imported imported", Tone.Success)
                        else Checkbox(importHistory, onImportHistory)
                    }
                }
            }
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            row()
        }
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        VText("Default shell", Vesper.type.body, c.textSecondary, Modifier.weight(1f))
        val current = state.settings.shell.ifEmpty { setup.shell }
        Dropdown(current, (listOf(current) + setup.shells).distinct(), Modifier.width(220.dp)) { picked ->
            state.updateSettings { it.copy(shell = if (picked == setup.shell) "" else picked) }
        }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    val c = Vesper.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(icon, 15.dp, c.iconDefault)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, Vesper.type.codeSmall, c.textPrimary)
            VText(subtitle, Vesper.type.meta, c.textMuted)
        }
        trailing()
    }
}

@Composable
private fun ThemeStep(state: AppState) {
    val c = Vesper.colors
    val s = state.settings
    StepTitle("Pick a theme", "You can change it any time in Settings › Appearance.")
    ThemeId.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { t ->
                ThemeCard(t, s.accent, active = s.theme == t && !s.followSystem, Modifier.weight(1f)) {
                    state.updateSettings { it.copy(theme = t, followSystem = false) }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VText("Accent", Vesper.type.body, c.textSecondary, Modifier.weight(1f))
        AccentId.entries.forEach { a ->
            Box(
                Modifier.size(20.dp)
                    .border(2.dp, if (s.accent == a) c.textPrimary else Color.Transparent, CircleShape)
                    .padding(3.dp)
                    .background(a.swatch, CircleShape)
                    .clickable { state.updateSettings { it.copy(accent = a) } },
            )
        }
    }
}
