package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.vesper.app.AgentModel
import app.vesper.app.AgentRunMode
import app.vesper.app.AgentUsage
import app.vesper.app.AppState
import app.vesper.app.DefaultRedactPatterns
import app.vesper.app.monthKey
import app.vesper.app.nextMonthStart
import app.vesper.ui.components.Badge
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.Toggle
import app.vesper.ui.components.Tone
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.icons.Icons
import app.vesper.ui.components.VText
import app.vesper.ui.theme.Vesper
import app.vesper.util.localDay
import app.vesper.util.nowMillis

@Composable
internal fun AgentSection(state: AppState) {
    val s = state.settings
    val hint = state.agent.keyHint
    Header("AI agent", "What the agent can see and do. Plans always show before anything runs.")
    Group("API key") {
        SettingRow(
            "Anthropic API key",
            when {
                !state.agent.keyLoaded -> "Checking…"
                hint == null -> "Needed for # questions and Explain. Requests go straight to the Claude API with this key."
                hint == "ANTHROPIC_API_KEY" -> "From the ANTHROPIC_API_KEY environment variable"
                else -> "Saved, ends in ${hint.removePrefix("…")}"
            },
        ) {
            if (hint != null && hint != "ANTHROPIC_API_KEY") {
                VButton("Remove", { state.agent.clearKey() }, icon = Icons.Trash, kind = ButtonKind.Danger)
            } else {
                VButton("Get a key", { state.services.openUrl("https://console.anthropic.com/settings/keys") }, icon = Icons.Link, kind = ButtonKind.Ghost)
            }
        }
        ApiKeyField(state, Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
    }
    val models = AgentModel.entries.map { it.title }
    Group("Models") {
        SettingRow("Default model", "Used for # questions") {
            Dropdown(AgentModel.of(s.agentModel).title, models, Modifier.width(220.dp)) { t ->
                state.updateSettings { it.copy(agentModel = AgentModel.entries.first { m -> m.title == t }.id) }
            }
        }
        SettingRow("Complex tasks", "Error explanations", last = true) {
            Dropdown(AgentModel.of(s.agentExplainModel).title, models, Modifier.width(220.dp)) { t ->
                state.updateSettings { it.copy(agentExplainModel = AgentModel.entries.first { m -> m.title == t }.id) }
            }
        }
    }
    Group("Behaviour") {
        SettingRow("Explain button on failed commands", "Adds Explain to blocks that exit non-zero. ${state.services.os.mod}E explains the last one.") {
            Toggle(s.agentExplainButton, { v -> state.updateSettings { it.copy(agentExplainButton = v) } })
        }
        SettingRow("Running agent plans", "How steps are executed") {
            Dropdown(s.agentRunMode.title, AgentRunMode.entries.map { it.title }, Modifier.width(220.dp)) { t ->
                state.updateSettings { it.copy(agentRunMode = AgentRunMode.entries.first { m -> m.title == t }) }
            }
        }
        SettingRow("Allow destructive steps", "rm, kill, DROP, force-push and the like. When off, they only go to the prompt.", last = true) {
            Toggle(s.agentAllowDestructive, { v -> state.updateSettings { it.copy(agentAllowDestructive = v) } })
        }
    }
    Group("Context & privacy") {
        SettingRow("Send command output", "Last 3 blocks, trimmed to about 4k tokens and redacted") {
            Toggle(s.agentSendOutput, { v -> state.updateSettings { it.copy(agentSendOutput = v) } })
        }
        SettingRow("Send git status", "Branch, ahead/behind and number of changed files") {
            Toggle(s.agentSendGit, { v -> state.updateSettings { it.copy(agentSendGit = v) } })
        }
        SettingRow("Send listening ports and busy processes", "From Monitor, for questions like \"what's on port 3000\"") {
            Toggle(s.agentSendMonitor, { v -> state.updateSettings { it.copy(agentSendMonitor = v) } })
        }
        SettingRow("Send environment variables", "Never sent", last = true) { Badge("never", Tone.Neutral) }
    }
}

/** Right column of Settings › AI agent: this month's usage and the redaction patterns. */
@Composable
internal fun AgentUsagePanel(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val month = monthKey(localDay(nowMillis()))
    val usage = state.settings.agentUsage.takeIf { it.month == month } ?: AgentUsage(month)
    Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Overline("Usage this month")
        Column(
            Modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Overline("Agent requests")
            VText(usage.requests.toString(), type.metric, c.textPrimary)
            VText("${tokens(usage.inputTokens)} in · ${tokens(usage.outputTokens)} out tokens", type.meta, c.textMuted)
            VText("resets ${nextMonthStart(month)}", type.meta, c.textMuted)
        }
        Row(
            Modifier.fillMaxWidth().background(c.accentSoft, RadiusXl).border(1.dp, c.accentStrong, RadiusXl).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VIcon(Icons.Zap, 16.dp, c.accentText, Modifier.padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                VText("Secrets stay local", type.bodyMedium, c.textPrimary)
                VText(
                    "Values of names matching these patterns, common token formats and the output of commands that read these files are redacted before sending.",
                    type.caption,
                    c.textSecondary,
                    maxLines = 5,
                )
            }
        }
        RedactionPatterns(state)
    }
}

@Composable
private fun RedactionPatterns(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val patterns = state.settings.redactPatterns
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    fun add() {
        val p = draft.trim()
        if (p.isNotEmpty() && p !in patterns) state.updateSettings { it.copy(redactPatterns = it.redactPatterns + p) }
        draft = ""
        adding = false
    }
    Column(
        Modifier.fillMaxWidth().background(c.bgSurface, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline("Redaction patterns", Modifier.weight(1f))
            if (patterns != DefaultRedactPatterns) {
                VText(
                    "reset",
                    type.meta,
                    c.textMuted,
                    Modifier.padding(start = 6.dp).clickable { state.updateSettings { it.copy(redactPatterns = DefaultRedactPatterns) } },
                )
            }
        }
        patterns.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VIcon(Icons.Lock, 12.dp, c.iconDefault)
                VText(p, type.codeSmall, c.textSecondary, Modifier.weight(1f))
                IconButton(Icons.X, { state.updateSettings { it.copy(redactPatterns = it.redactPatterns - p) } }, size = 11.dp, padding = 3.dp)
            }
        }
        Spacer(Modifier.height(2.dp))
        if (adding) {
            TextInput(
                draft,
                "NAME_*, *_KEY or ~/path",
                Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                        add()
                        true
                    } else {
                        false
                    }
                },
                mono = true,
            ) { draft = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VButton("Add", ::add, kind = ButtonKind.Primary, enabled = draft.isNotBlank())
                VButton("Cancel", { adding = false; draft = "" }, kind = ButtonKind.Ghost)
            }
        } else {
            VButton("Add pattern", { adding = true }, icon = Icons.Plus, kind = ButtonKind.Ghost)
        }
    }
}

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
    n >= 1_000 -> "${(n / 100) / 10.0}k"
    else -> n.toString()
}
