package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.platform.HostOs
import app.vesper.app.ShareFormat
import app.vesper.app.ShareRequest
import app.vesper.app.ShareTrimLines
import app.vesper.app.shareBlock
import app.vesper.terminal.BlockStatus
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.RadiusMd
import app.vesper.ui.components.Toggle
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VDialog
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatClock
import app.vesper.util.formatDuration

/** Share block (Figma 22): a redacted copy of a finished command, saved or copied. Nothing is uploaded. */
@Composable
fun ShareDialog(state: AppState, req: ShareRequest) {
    val c = Vesper.colors
    val type = Vesper.type
    val clipboard = LocalClipboardManager.current
    val block = req.block
    val services = state.services
    val output = remember(block) { synchronized(block.terminal.lock) { block.terminal.plainText() } }
    val env = remember(block) {
        listOfNotNull(
            services.displayPath(block.cwd),
            req.pane.context.gitBranch,
            req.pane.session.shellName.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
    }
    val meta = remember(block) {
        listOfNotNull(
            block.exitCode?.let { "exit $it" } ?: if (block.status == BlockStatus.Interrupted) "interrupted" else null,
            formatDuration(block.durationMs),
            formatClock(block.startedAt, seconds = false),
        ).joinToString(" · ")
    }
    val shared = remember(req.format, req.includeEnv, req.fullOutput, state.settings.redactPatterns) {
        shareBlock(
            command = block.command,
            output = output,
            meta = meta,
            env = env.takeIf { req.includeEnv },
            format = req.format,
            fullOutput = req.fullOutput,
            patterns = state.settings.redactPatterns,
            stamp = formatClock(block.startedAt, seconds = false).replace(":", ""),
        )
    }
    var saved by remember(shared) { mutableStateOf<String?>(null) }
    var failed by remember(shared) { mutableStateOf(false) }
    var copied by remember(shared) { mutableStateOf(false) }
    val close = { state.sharing = null }

    VDialog(
        "Share block",
        onDismiss = close,
        width = 544.dp,
        icon = Icons.Upload,
        iconTint = c.textPrimary,
        footer = {
            VIcon(Icons.Lock, 12.dp, c.iconDefault)
            VText(
                if (failed) "Couldn't save the file" else "Stays on this Mac · nothing is uploaded",
                type.caption,
                if (failed) c.danger else c.textMuted,
                Modifier.weight(1f),
            )
            VButton("Cancel", close, kind = ButtonKind.Ghost)
            val path = saved
            if (path == null) {
                VButton(
                    "Save to Downloads",
                    {
                        saved = services.exportFile(shared.fileName, shared.content)
                        failed = saved == null
                    },
                    icon = Icons.Download,
                    kind = ButtonKind.Primary,
                )
            } else {
                VButton(if (services.os == HostOs.MacOS) "Show in Finder" else "Show file", { services.revealInFileManager(path) }, icon = Icons.Folder, kind = ButtonKind.Primary)
            }
        },
    ) {
        BlockPreview(block.command, block.status, meta, shared.outputLines)
        SecretsNote(shared.secrets, shared.secretNames)

        Overline("Format")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShareFormat.entries.forEach { f ->
                RadioCard(f.title, ".${f.ext} · ${f.detail}", selected = req.format == f) { req.format = f }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OptionRow("Include environment ($env)", req.includeEnv) { req.includeEnv = it }
            if (shared.totalLines > ShareTrimLines) {
                OptionRow("Include full output (${thousands(shared.totalLines)} lines)", req.fullOutput) { req.fullOutput = it }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).background(c.bgInset, RadiusMd).border(1.dp, c.borderDefault, RadiusMd).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VIcon(if (saved != null) Icons.Check else Icons.File, 14.dp, if (saved != null) c.success else c.iconDefault)
                VText(saved?.let(services::displayPath) ?: "~/Downloads/${shared.fileName}", type.bodySmall, c.textPrimary, maxLines = 1)
            }
            VButton(
                if (copied) "Copied" else "Copy text",
                {
                    clipboard.setText(AnnotatedString(shared.content))
                    copied = true
                },
                icon = if (copied) Icons.Check else Icons.Copy,
            )
        }
    }
}

/** "2,140" without platform formatting. */
private fun thousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

@Composable
private fun BlockPreview(command: String, status: BlockStatus, meta: String, lines: List<String>) {
    val c = Vesper.colors
    val type = Vesper.type
    Column(
        Modifier.fillMaxWidth().background(c.bgSurface, RadiusLg).border(1.dp, c.borderSubtle, RadiusLg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                BlockStatus.Success -> VIcon(Icons.CheckCircle, 16.dp, c.success)
                BlockStatus.Failed -> VIcon(Icons.XCircle, 16.dp, c.danger)
                else -> VIcon(Icons.XCircle, 16.dp, c.warning)
            }
            VText("›", type.command, c.textMuted)
            VText(command, type.command, c.textPrimary, Modifier.weight(1f), maxLines = 1)
            VText(meta, type.meta, c.textMuted, maxLines = 1)
        }
        val shown = lines.take(5)
        if (shown.any { it.isNotBlank() }) {
            Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.forEach { VText(it.ifEmpty { " " }, type.codeSmall, c.textSecondary, maxLines = 1) }
                if (lines.size > shown.size) VText("… ${lines.size - shown.size} more lines", type.codeSmall, c.textMuted)
            }
        }
    }
}

@Composable
private fun SecretsNote(count: Int, names: List<String>) {
    val c = Vesper.colors
    val type = Vesper.type
    val found = count > 0
    val tint = if (found) c.warning else c.success
    Row(
        Modifier.fillMaxWidth().background(if (found) c.warningSoft else c.bgSurface, RadiusLg)
            .border(1.dp, if (found) c.warning else c.borderSubtle, RadiusLg).padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(if (found) Icons.AlertTriangle else Icons.Lock, 16.dp, tint)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(
                when (count) {
                    0 -> "No secrets found"
                    1 -> "1 secret redacted"
                    else -> "$count secrets redacted"
                },
                type.bodyMedium,
                c.textPrimary,
            )
            VText(
                if (found) {
                    "${names.take(3).joinToString(" and ")}${if (names.size > 3) " and more" else ""} ${if (count == 1) "is" else "are"} replaced with •••• in the shared copy"
                } else {
                    "Values matching your redaction patterns would be replaced with ••••"
                },
                type.caption,
                c.textSecondary,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun RadioCard(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val type = Vesper.type
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.bgSurface else c.bgInset.copy(alpha = 0f), RadiusLg)
            .border(1.dp, if (selected) c.borderDefault else c.borderSubtle.copy(alpha = 0f), RadiusLg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).background(if (selected) c.accent else c.bgInset, CircleShape)
                .border(1.dp, if (selected) c.accent else c.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(5.dp).background(c.textOnAccent, CircleShape)) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, type.bodyMedium, c.textPrimary)
            VText(detail, type.caption, c.textMuted)
        }
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VText(label, Vesper.type.body, Vesper.colors.textPrimary, Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.size(12.dp))
        Toggle(checked, onChange)
    }
}
