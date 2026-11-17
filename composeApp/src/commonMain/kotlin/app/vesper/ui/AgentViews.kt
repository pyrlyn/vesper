package app.vesper.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.vesper.app.AgentModel
import app.vesper.app.AgentRunMode
import app.vesper.app.AgentTurn
import app.vesper.app.AppState
import app.vesper.app.ExplainState
import app.vesper.app.ExplainStatus
import app.vesper.app.Pane
import app.vesper.app.SettingsSection
import app.vesper.app.SidePanel
import app.vesper.app.StepState
import app.vesper.app.StepStatus
import app.vesper.app.TurnStatus
import app.vesper.app.planScript
import app.vesper.ui.components.Badge
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.Checkbox
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.RadiusMd
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.Toggle
import app.vesper.ui.components.Tone
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatClock

// ── "#" question and plan, above the prompt ──────────────────────────────

@Composable
fun AgentTurnView(state: AppState, pane: Pane, turn: AgentTurn, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(
        modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText("#", type.command, c.accentText)
            SelectionContainer(Modifier.weight(1f)) { VText(turn.ask, type.command, c.textPrimary, maxLines = 4) }
            VText(formatClock(turn.at), type.meta, c.textMuted)
        }
        PlanCard(state, pane, turn)
    }
}

@Composable
private fun PlanCard(state: AppState, pane: Pane, turn: AgentTurn) {
    val c = Vesper.colors
    val type = Vesper.type
    val clipboard = LocalClipboardManager.current
    var copied by remember(turn) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(c.accentSoft, RadiusXl).border(1.dp, c.accentDeep, RadiusXl).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (turn.status == TurnStatus.Thinking) Spinner() else VIcon(Icons.Sparkles, 15.dp, c.accentText)
            VText("Agent", type.title, c.textPrimary)
            if (turn.status == TurnStatus.Ready && turn.steps.isNotEmpty()) {
                val risky = turn.steps.count { it.destructive }
                val n = turn.steps.size
                Badge("$n step${if (n == 1) "" else "s"} · ${if (risky == 0) "safe" else "$risky destructive"}", if (risky == 0) Tone.Accent else Tone.Warning)
            }
            Spacer(Modifier.weight(1f))
            if (turn.used.isNotEmpty()) VText("used: ${turn.used}", type.micro, c.textMuted, maxLines = 1)
        }
        when (turn.status) {
            TurnStatus.Thinking -> VText("Reading the session and drafting a plan…", type.body, c.textSecondary)
            TurnStatus.Failed -> {
                VText(turn.error ?: "The agent couldn't answer.", type.body, c.danger, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (turn.needsKey) VButton("Open AI agent settings", { state.openSettings(SettingsSection.Agent) }, icon = Icons.Settings, kind = ButtonKind.Accent)
                    VButton("Try again", { state.agent.ask(pane, turn.ask) }, icon = Icons.RotateCcw)
                    VButton("Dismiss", { state.agent.cancel(pane) }, kind = ButtonKind.Ghost)
                }
            }
            TurnStatus.Ready -> {
                if (turn.explanation.isNotBlank()) SelectionContainer { VText(turn.explanation, type.body, c.textSecondary, maxLines = 12) }
                turn.steps.forEachIndexed { i, step -> StepRow(state, pane, turn, step, i + 1) }
                turn.notice?.let { VText(it, type.caption, c.warning) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (turn.steps.isNotEmpty()) {
                        VButton(
                            if (copied) "Copied" else "Copy as script",
                            {
                                clipboard.setText(AnnotatedString(planScript(turn.ask, turn.steps.map { it.command })))
                                copied = true
                            },
                            icon = if (copied) Icons.Check else Icons.Copy,
                            kind = ButtonKind.Ghost,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    VButton(if (turn.steps.all { it.status == StepStatus.Done }) "Close" else "Cancel", { state.agent.cancel(pane) }, kind = ButtonKind.Ghost)
                    val pending = turn.steps.any { it.status == StepStatus.Pending || it.status == StepStatus.Failed }
                    if (pending) {
                        VButton(
                            if (turn.running) "Running…" else "Run remaining",
                            { state.agent.runRemaining(pane, turn) },
                            icon = Icons.Play,
                            kind = ButtonKind.Primary,
                            enabled = !turn.running,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(state: AppState, pane: Pane, turn: AgentTurn, step: StepState, number: Int) {
    val c = Vesper.colors
    val type = Vesper.type
    val blocked = step.destructive && !state.settings.agentAllowDestructive
    Row(
        Modifier.fillMaxWidth().background(c.bgSurface, RadiusLg).border(1.dp, c.borderSubtle, RadiusLg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val done = step.status == StepStatus.Done
        Box(
            Modifier.size(22.dp).background(if (done) c.successSoft else c.bgActive, RadiusMd),
            contentAlignment = Alignment.Center,
        ) { VText(number.toString(), type.meta, if (done) c.success else c.textSecondary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (step.editing) {
                CommandField(step.command, { step.command = it }, Modifier.fillMaxWidth())
            } else {
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    SelectionContainer { VText(step.command, type.command, c.textPrimary, maxLines = 3) }
                }
            }
            if (step.description.isNotBlank()) VText(step.description, type.caption, c.textMuted, maxLines = 2)
        }
        when (step.status) {
            StepStatus.Done -> Badge("done", Tone.Success)
            StepStatus.Running -> Badge("running", Tone.Accent)
            StepStatus.Pending, StepStatus.Failed -> {
                if (step.status == StepStatus.Failed) Badge("failed", Tone.Danger)
                if (blocked) Badge("destructive", Tone.Warning)
                VButton(if (step.editing) "Done" else "Edit", { step.editing = !step.editing }, icon = Icons.Pencil, kind = ButtonKind.Ghost)
                if (blocked) {
                    VButton("To prompt", { pane.draft = step.command }, icon = Icons.CornerDownLeft)
                } else {
                    VButton(
                        if (step.status == StepStatus.Failed) "Retry" else "Run step",
                        { state.agent.runStep(pane, turn, step) },
                        icon = Icons.Play,
                        enabled = !turn.running,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val c = Vesper.colors
    BasicTextField(
        value,
        onChange,
        singleLine = true,
        textStyle = Vesper.type.command.copy(color = c.textPrimary),
        cursorBrush = SolidColor(c.accentStrong),
        modifier = modifier.background(c.bgInset, RadiusMd).border(1.dp, c.borderFocus, RadiusMd).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun Spinner() {
    val t = rememberInfiniteTransition()
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
    VIcon(Icons.Loader, 15.dp, Vesper.colors.accentText, Modifier.rotate(angle))
}

// ── Right panel ──────────────────────────────────────────────────────────

@Composable
fun AgentPanel(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val explain = state.agent.explain
    Row(Modifier.fillMaxHeight()) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderSubtle))
        Column(Modifier.width(332.dp).fillMaxHeight().background(c.bgSidebar).padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VIcon(Icons.Sparkles, 15.dp, c.accentText)
                VText(if (explain != null) "Explain error" else "Agent", type.title, c.textPrimary, Modifier.weight(1f))
                if (explain != null) IconButton(Icons.ChevronRight, { state.agent.closeExplain() }, size = 13.dp, padding = 5.dp)
                IconButton(Icons.X, { state.agent.closeExplain(); state.sidePanel = SidePanel.Monitor }, size = 13.dp, padding = 5.dp)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (explain != null) ExplainView(state, explain) else AgentOverview(state)
            }
        }
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Vesper.colors
    Column(
        modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl)
            .then(if (padding) Modifier.padding(14.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun AgentOverview(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val s = state.settings
    if (state.agent.keyLoaded && state.agent.keyHint == null) {
        PanelCard {
            Overline("Set up")
            VText("Add an Anthropic API key to ask in plain language and explain failed commands.", type.bodySmall, c.textSecondary, maxLines = 3)
            ApiKeyField(state)
        }
    }
    PanelCard {
        Overline("Context sent to the agent")
        val pane = state.focusedPane
        ContextRow("Working directory", pane?.let { state.services.displayPath(it.session.cwd) } ?: "", true, onChange = null)
        ContextRow("Git status", pane?.context?.let { if (it.gitBranch == null) "no repo" else "${it.changes} files" } ?: "", s.agentSendGit) { v ->
            state.updateSettings { it.copy(agentSendGit = v) }
        }
        ContextRow("Last 3 blocks", "trimmed", s.agentSendOutput) { v -> state.updateSettings { it.copy(agentSendOutput = v) } }
        ContextRow("Monitor (ports, processes)", "live", s.agentSendMonitor) { v -> state.updateSettings { it.copy(agentSendMonitor = v) } }
        ContextRow("Environment variables", "never", false, disabled = true, onChange = null)
    }
    PanelCard(padding = false) {
        ToggleRow("Ask before running", "Show the plan, run on confirm", s.agentRunMode == AgentRunMode.Ask) { v ->
            state.updateSettings { it.copy(agentRunMode = if (v) AgentRunMode.Ask else AgentRunMode.SafeSteps) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).background(c.borderSubtle).heightIn(min = 1.dp, max = 1.dp))
        ToggleRow("Allow destructive", "rm, kill -9, DROP, force-push", s.agentAllowDestructive) { v ->
            state.updateSettings { it.copy(agentAllowDestructive = v) }
        }
    }
    PanelCard {
        Overline("Model")
        Dropdown(AgentModel.of(s.agentModel).title, AgentModel.entries.map { it.title }, Modifier.fillMaxWidth()) { title ->
            val m = AgentModel.entries.first { it.title == title }
            state.updateSettings { it.copy(agentModel = m.id) }
        }
    }
    if (s.agentRecent.isNotEmpty()) {
        PanelCard {
            Overline("Recent asks")
            s.agentRecent.forEach { ask ->
                Row(
                    Modifier.fillMaxWidth().clickable { state.focusedPane?.draft = "# $ask" }.pointerHoverIcon(PointerIcon.Hand),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VText("#", type.bodySmall, c.textMuted)
                    VText(ask, type.bodySmall, c.textSecondary, Modifier.weight(1f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ContextRow(label: String, trailing: String, checked: Boolean, disabled: Boolean = false, onChange: ((Boolean) -> Unit)?) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onChange?.invoke(it) }, enabled = onChange != null)
        VText(label, type.bodySmall, if (disabled) c.textMuted else c.textPrimary, Modifier.weight(1f), maxLines = 1)
        VText(trailing, type.meta, c.textMuted, maxLines = 1)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, type.bodyMedium, c.textPrimary)
            VText(subtitle, type.caption, c.textMuted)
        }
        Toggle(checked, onChange)
    }
}

@Composable
private fun ExplainView(state: AppState, ex: ExplainState) {
    val c = Vesper.colors
    val type = Vesper.type
    val clipboard = LocalClipboardManager.current
    when (ex.status) {
        ExplainStatus.Thinking -> PanelCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                VText("Reading the output of", type.bodySmall, c.textSecondary)
            }
            VText(ex.command, type.codeSmall, c.textPrimary, maxLines = 3)
        }
        ExplainStatus.Failed -> PanelCard {
            VText(ex.error ?: "The agent couldn't answer.", type.bodySmall, c.danger, maxLines = 5)
            if (ex.needsKey) ApiKeyField(state)
            VButton("Try again", {
                val block = ex.pane.session.blocksSnapshot().firstOrNull { it.id == ex.blockId }
                if (block != null) state.agent.explain(ex.pane, block)
            }, icon = Icons.RotateCcw)
        }
        ExplainStatus.Ready -> {
            val r = ex.result ?: return
            PanelCard {
                Overline("What happened")
                SelectionContainer { VText(r.summary, type.bodySmall, c.textPrimary, maxLines = 12) }
            }
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Overline("Suggested fix", Modifier.weight(1f))
                    if (r.diff.isNotBlank()) {
                        val lines = r.diff.lines()
                        Badge("${if (r.file.isNotBlank()) "1 file · " else ""}+${lines.count { it.startsWith("+") }} -${lines.count { it.startsWith("-") }}", Tone.Accent)
                    }
                }
                if (r.fix.isNotBlank()) SelectionContainer { VText(r.fix, type.bodySmall, c.textSecondary, maxLines = 8) }
                if (r.file.isNotBlank()) VText(r.file, type.codeSmall, c.textMuted, maxLines = 1)
                if (r.diff.isNotBlank()) DiffView(r.diff)
                var notice by remember(ex) { mutableStateOf<String?>(null) }
                r.commands.forEach { step ->
                    Row(
                        Modifier.fillMaxWidth().background(c.bgInset, RadiusMd).border(1.dp, c.borderSubtle, RadiusMd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            VText(step.command, type.codeSmall, c.textPrimary, maxLines = 3)
                            if (step.description.isNotBlank()) VText(step.description, type.micro, c.textMuted, maxLines = 2)
                        }
                        IconButton(Icons.Play, { notice = state.agent.runFix(step) }, size = 12.dp, padding = 5.dp)
                    }
                }
                notice?.let { VText(it, type.caption, c.warning) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val copyText = r.diff.ifBlank { r.commands.joinToString("\n") { it.command } }
                    if (copyText.isNotBlank()) VButton("Copy", { clipboard.setText(AnnotatedString(copyText)) }, icon = Icons.Copy, kind = ButtonKind.Ghost)
                    Spacer(Modifier.weight(1f))
                    VButton("Retry command", { state.run(ex.pane, ex.command) }, icon = Icons.RotateCcw, kind = ButtonKind.Primary)
                }
            }
            if (r.alternatives.isNotEmpty()) {
                PanelCard {
                    Overline("Other options")
                    r.alternatives.forEach { o ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            VText(o.title, type.bodyMedium, c.textPrimary, maxLines = 2)
                            if (o.detail.isNotBlank()) VText(o.detail, type.caption, c.textMuted, maxLines = 3)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VText(AgentModel.of(state.settings.agentExplainModel).title, type.caption, c.textMuted, Modifier.weight(1f))
                VButton("Ask follow-up", { ex.pane.draft = "# " }, icon = Icons.Sparkles, kind = ButtonKind.Ghost)
            }
        }
    }
}

@Composable
private fun DiffView(diff: String) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(
        Modifier.fillMaxWidth().background(c.bgInset, RadiusMd).border(1.dp, c.borderSubtle, RadiusMd)
            .horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        diff.trimEnd().lines().take(60).forEach { line ->
            val (bg, fg) = when {
                line.startsWith("+") -> c.success.copy(alpha = 0.14f) to c.success
                line.startsWith("-") -> c.danger.copy(alpha = 0.14f) to c.danger
                else -> Color.Transparent to c.textSecondary
            }
            VText(line.ifEmpty { " " }, type.codeSmall, fg, Modifier.background(bg).padding(horizontal = 10.dp, vertical = 1.dp), maxLines = 1)
        }
    }
}

// ── API key ──────────────────────────────────────────────────────────────

/** Paste-and-save field for the Anthropic API key; the key is masked and never shown again. */
@Composable
fun ApiKeyField(state: AppState, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    val type = Vesper.type
    var value by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).background(c.bgInset, RadiusMd).border(1.dp, c.borderDefault, RadiusMd)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                if (value.isEmpty()) VText("sk-ant-…", type.codeSmall, c.textMuted)
                BasicTextField(
                    value,
                    { value = it.trim(); error = null },
                    singleLine = true,
                    textStyle = type.codeSmall.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accentStrong),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            VButton(
                if (saving) "Saving…" else "Save",
                {
                    saving = true
                    state.agent.saveKey(value) { ok ->
                        saving = false
                        if (ok) value = "" else error = "That doesn't look like an API key, or it couldn't be stored."
                    }
                },
                icon = Icons.Key,
                kind = ButtonKind.Primary,
                enabled = value.length >= 20 && !saving,
            )
        }
        VText(
            error ?: if (state.services.os == app.vesper.platform.HostOs.MacOS) "Stored in your macOS Keychain." else "Stored in a file only you can read.",
            type.caption,
            if (error != null) c.danger else c.textMuted,
            maxLines = 2,
        )
    }
}

// ── Sidebar ──────────────────────────────────────────────────────────────

@Composable
fun AgentSidebarCard(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val ready = state.agent.keyHint != null
    Column(
        Modifier.fillMaxWidth()
            .background(c.accentSoft.copy(alpha = c.accentSoft.alpha * 0.8f), RadiusXl)
            .border(1.dp, c.accentStrong.copy(alpha = 0.55f), RadiusXl)
            .clickable { if (ready) state.sidePanel = SidePanel.Agent else state.openSettings(SettingsSection.Agent) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VIcon(Icons.Sparkles, 14.dp, c.accentText)
            VText(if (ready) "Agent ready" else "Set up the agent", type.title, c.textPrimary)
        }
        VText(
            if (ready) "Start a line with # to ask in plain language — it drafts the command for you."
            else "Add an Anthropic API key to ask in plain language and explain failed commands.",
            type.caption,
            c.textSecondary,
            maxLines = 4,
        )
    }
}
