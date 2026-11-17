package app.vesper.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import app.vesper.app.Pane
import app.vesper.app.PromptContext
import app.vesper.terminal.SessionMode
import app.vesper.ui.components.Chip
import app.vesper.ui.components.KeyHint
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.components.popover
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper

data class Suggestion(val text: String, val source: String, val icon: ImageVector)

/** Callbacks from the prompt to the app. */
interface PromptActions {
    fun run(command: String)
    /** A "# …" line: a question for the agent. */
    fun ask(text: String)
    fun interrupt()
    fun sendRaw(text: String)
    fun suggestions(input: String): List<Suggestion>
    fun history(): List<String>
    fun openPalette()
    fun chipCwd()
    fun chipBranch()
    val modLabel: String
}

@Composable
fun PromptBar(
    pane: Pane,
    actions: PromptActions,
    focusRequester: FocusRequester,
    displayCwd: String,
    modifier: Modifier = Modifier,
) {
    val c = Vesper.colors
    val type = Vesper.type
    val session = pane.session
    @Suppress("UNUSED_VARIABLE")
    val rev = session.revision
    val running = session.currentBlock()
    val starting = session.mode == SessionMode.Starting

    var value by remember(pane) { mutableStateOf(TextFieldValue(pane.draft, TextRange(pane.draft.length))) }
    LaunchedEffect(pane.draft) {
        if (pane.draft != value.text) value = TextFieldValue(pane.draft, TextRange(pane.draft.length))
    }
    fun set(v: TextFieldValue) {
        value = v
        pane.draft = v.text
    }

    var focused by remember { mutableStateOf(false) }
    var dismissed by remember(pane) { mutableStateOf(false) }
    var selected by remember(pane) { mutableIntStateOf(-1) }
    var historyIndex by remember(pane) { mutableIntStateOf(-1) }

    val input = value.text
    val agentMode = running == null && !starting && input.trimStart().startsWith("#")
    val suggestions = remember(input, running == null) {
        if (input.isBlank() || running != null || agentMode || input.contains('\n')) emptyList() else actions.suggestions(input).take(6)
    }
    val ghost = suggestions.firstOrNull { it.text.startsWith(input) && it.text.length > input.length }?.text?.substring(input.length)
        ?.takeIf { value.selection.collapsed && value.selection.end == input.length }
    val showPopup = focused && !dismissed && suggestions.isNotEmpty() && !(suggestions.size == 1 && suggestions[0].text == input)

    val secret = running != null && run {
        val last = synchronized(session.lock) {
            val t = running.terminal
            (t.mainLineCount() - 1 downTo maxOf(0, t.mainLineCount() - 2)).joinToString(" ") { t.mainLine(it).text() }
        }.lowercase().trimEnd()
        last.endsWith("password:") || last.endsWith("passphrase:") || last.contains("password for") || last.endsWith("pin:")
    }

    fun accept(text: String) {
        set(TextFieldValue(text, TextRange(text.length)))
        selected = -1
    }

    fun submit() {
        val cmd = if (selected in suggestions.indices) suggestions[selected].text else value.text
        if (running == null && cmd.isBlank()) return
        if (agentMode) {
            if (cmd.trim().removePrefix("#").isBlank()) return
            actions.ask(cmd)
        } else {
            actions.run(cmd)
        }
        set(TextFieldValue(""))
        selected = -1
        dismissed = false
        historyIndex = -1
    }

    Column(modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp)) {
        ContextChips(pane.context, displayCwd, actions)
        Box(Modifier.padding(top = 10.dp)) {
            val ring = c.accentStrong.copy(alpha = 0.14f)
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (focused || agentMode) {
                            val w = 3.dp.toPx()
                            drawRoundRect(
                                ring,
                                Offset(-w / 2, -w / 2),
                                Size(size.width + w, size.height + w),
                                CornerRadius(8.dp.toPx() + w / 2),
                                style = Stroke(w),
                            )
                        }
                    }
                    .background(c.bgSurface, RadiusLg)
                    .border(1.dp, if (focused || agentMode) c.borderFocus else c.borderDefault, RadiusLg)
                    .padding(start = 15.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!agentMode) VText("›", type.prompt, if (focused) c.accentText else c.textMuted)
                Box(Modifier.weight(1f)) {
                    val placeholder = when {
                        starting -> "Starting ${session.shellName}…"
                        running != null && secret -> "Password for ${running.command.substringBefore(' ')} — input is hidden"
                        running != null -> "Send input to ${running.command.take(40)} · Ctrl+C to stop"
                        else -> "Type a command, or # to ask the agent"
                    }
                    if (input.isEmpty()) VText(placeholder, type.prompt, c.textMuted)
                    if (ghost != null && !secret) {
                        androidx.compose.foundation.text.BasicText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.Transparent)) { append(input) }
                                withStyle(SpanStyle(color = c.textMuted)) { append(ghost) }
                            },
                            style = type.prompt,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            if (it.text != value.text) {
                                dismissed = false
                                selected = -1
                                historyIndex = -1
                            }
                            set(it)
                        },
                        textStyle = type.prompt.copy(color = c.textPrimary),
                        visualTransformation = if (secret) PasswordVisualTransformation() else if (agentMode) AgentHash(c.accentText) else VisualTransformation.None,
                        cursorBrush = SolidColor(c.accentStrong),
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    e.key == Key.Enter && e.isShiftPressed -> {
                                        val t = value.text.replaceRange(value.selection.min, value.selection.max, "\n")
                                        set(TextFieldValue(t, TextRange(value.selection.min + 1)))
                                        true
                                    }
                                    e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                                        submit()
                                        true
                                    }
                                    e.key == Key.Tab && !e.isShiftPressed -> {
                                        when {
                                            selected in suggestions.indices -> accept(suggestions[selected].text)
                                            ghost != null -> accept(input + ghost)
                                            running != null && input.isEmpty() -> actions.sendRaw("\t")
                                        }
                                        true
                                    }
                                    e.key == Key.Escape -> {
                                        if (showPopup) dismissed = true else if (running != null) actions.sendRaw("\u001b")
                                        true
                                    }
                                    e.key == Key.DirectionUp && showPopup -> {
                                        selected = if (selected <= 0) suggestions.lastIndex else selected - 1
                                        true
                                    }
                                    e.key == Key.DirectionDown && showPopup -> {
                                        selected = if (selected >= suggestions.lastIndex) 0 else selected + 1
                                        true
                                    }
                                    e.key == Key.DirectionUp && !value.text.contains('\n') && running == null -> {
                                        val h = actions.history()
                                        if (h.isEmpty()) return@onPreviewKeyEvent true
                                        historyIndex = (historyIndex + 1).coerceAtMost(h.lastIndex)
                                        val t = h[h.lastIndex - historyIndex]
                                        set(TextFieldValue(t, TextRange(t.length)))
                                        dismissed = true
                                        true
                                    }
                                    e.key == Key.DirectionDown && historyIndex >= 0 && running == null -> {
                                        val h = actions.history()
                                        historyIndex--
                                        val t = if (historyIndex < 0) "" else h[h.lastIndex - historyIndex]
                                        set(TextFieldValue(t, TextRange(t.length)))
                                        true
                                    }
                                    e.isCtrlPressed && !e.isMetaPressed && e.key == Key.C && value.selection.collapsed -> {
                                        if (running != null) actions.interrupt() else set(TextFieldValue(""))
                                        true
                                    }
                                    e.isCtrlPressed && e.key == Key.D && input.isEmpty() && running != null -> {
                                        actions.sendRaw("\u0004")
                                        true
                                    }
                                    e.isCtrlPressed && e.key == Key.L && running == null -> {
                                        actions.run("clear")
                                        true
                                    }
                                    running != null && input.isEmpty() && e.key in RawKeys -> {
                                        actions.sendRaw(RawKeys.getValue(e.key))
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
                when {
                    agentMode -> {
                        app.vesper.ui.components.Badge("agent", app.vesper.ui.components.Tone.Accent)
                        KeyHint("⏎", "ask")
                    }
                    ghost != null || showPopup -> {
                        KeyHint("⇥", "accept")
                        KeyHint("⏎", "run")
                    }
                    else -> KeyHint("${actions.modLabel}K", "palette")
                }
            }
            if (showPopup) {
                Popup(popupPositionProvider = AbovePosition, onDismissRequest = { dismissed = true }) {
                    SuggestionList(suggestions, selected, actions.modLabel) { accept(it.text) }
                }
            }
        }
    }
}

/** Colours the leading "#" of an agent question. */
private class AgentHash(private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val i = text.text.indexOf('#')
        val styled = buildAnnotatedString {
            append(text)
            if (i >= 0) addStyle(SpanStyle(color = color), i, i + 1)
        }
        return androidx.compose.ui.text.input.TransformedText(styled, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

private val RawKeys = mapOf(
    Key.DirectionUp to "\u001b[A",
    Key.DirectionDown to "\u001b[B",
    Key.DirectionRight to "\u001b[C",
    Key.DirectionLeft to "\u001b[D",
    Key.Backspace to "\u007f",
)

/** Places the popup right above its anchor, aligned to the left edge. */
private object AbovePosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: androidx.compose.ui.unit.IntSize,
    ): IntOffset = IntOffset(anchorBounds.left, (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(0))
}

@Composable
private fun SuggestionList(items: List<Suggestion>, selected: Int, mod: String, onPick: (Suggestion) -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(Modifier.width(600.dp).padding(8.dp).popover(c).padding(7.dp)) {
        items.forEachIndexed { i, s ->
            val active = i == selected || (selected < 0 && i == 0)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (active) c.bgActive else Color.Transparent, app.vesper.ui.components.RadiusMd)
                    .clickable { onPick(s) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(s.icon, 14.dp, if (active) c.accent else c.iconDefault)
                VText(s.text, type.command, c.textPrimary, Modifier.weight(1f))
                VText(s.source, type.meta, c.textMuted)
            }
        }
        Row(
            Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyHint("↑↓", "navigate")
            KeyHint("⇥", "accept")
            KeyHint("esc", "dismiss")
        }
    }
}

@Composable
private fun ContextChips(ctx: PromptContext, cwd: String, actions: PromptActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val dir = cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { cwd }
        Chip(dir, Icons.Folder, onClick = actions::chipCwd)
        ctx.gitBranch?.let { branch ->
            val extra = buildString {
                if (ctx.ahead > 0) append("  ↑${ctx.ahead}")
                if (ctx.behind > 0) append("  ↓${ctx.behind}")
                if (ctx.changes > 0) append("  ●${ctx.changes}")
            }
            Chip(branch + extra, Icons.GitBranch, onClick = actions::chipBranch)
        }
        ctx.nodeVersion?.let { Chip("node $it", Icons.Box) }
        ctx.packageManager?.let { pm -> Chip(listOfNotNull(pm, ctx.packageManagerVersion).joinToString(" "), Icons.Zap) }
    }
}
