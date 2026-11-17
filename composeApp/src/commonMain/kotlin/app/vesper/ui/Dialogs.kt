package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.vesper.app.HostVerification
import app.vesper.app.SettingsSection
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.CheckRow
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusLg
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VDialog
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper
import kotlinx.coroutines.delay

/** What this version brought, for About. */
private val WhatsNew = listOf(
    Icons.Sparkles to "Liquid Glass window on macOS, with opacity and blur settings",
    Icons.History to "History: every command with exit code, duration and insights",
    Icons.Search to "Find in output with case and regex options",
    Icons.Keyboard to "Shortcuts you can rebind, with conflict checks",
    Icons.Key to "Host key check before the first SSH connection",
)

@Composable
fun AboutDialog(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val close = { state.aboutOpen = false }
    VDialog(
        "About Vesper",
        onDismiss = close,
        width = 460.dp,
        footer = {
            VButton("Licenses", { close(); state.openSettings(SettingsSection.About) }, icon = Icons.Info, kind = ButtonKind.Ghost)
            VButton(
                if (copied) "Copied" else "Copy system info",
                {
                    clipboard.setText(AnnotatedString("Vesper ${state.services.appVersion} · ${state.services.systemInfo}"))
                    copied = true
                },
                icon = if (copied) Icons.Check else Icons.Copy,
                kind = ButtonKind.Ghost,
            )
            Spacer(Modifier.weight(1f))
            VText("© 2026 Vesper", type.meta, c.textMuted)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(c.accentSoft, RadiusXl).border(1.dp, c.accentStrong.copy(alpha = 0.5f), RadiusXl),
                contentAlignment = Alignment.Center,
            ) { VIcon(Icons.Terminal, 20.dp, c.accentText) }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                VText("Vesper ${state.services.appVersion}", type.title, c.textPrimary)
                SelectionContainer { VText(state.services.systemInfo, type.meta, c.textMuted) }
            }
        }
        Column(
            Modifier.fillMaxWidth().background(c.bgSurface, RadiusLg).border(1.dp, c.borderSubtle, RadiusLg).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Overline("What's new in ${state.services.appVersion.substringBeforeLast('.')}")
            WhatsNew.forEach { (icon, text) ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    VIcon(icon, 14.dp, c.accentText)
                    VText(text, type.bodySmall, c.textSecondary)
                }
            }
        }
    }
}

/** Verify host key: shown for a first connection, after a short wait while checking. */
@Composable
fun HostKeyDialog(state: AppState, verification: HostVerification) {
    val c = Vesper.colors
    val type = Vesper.type
    val check = verification.check
    var slow by remember(verification) { mutableStateOf(false) }
    LaunchedEffect(verification) {
        delay(400)
        slow = true
    }
    val cancel = { state.hostVerification = null }
    if (check == null) {
        if (slow) {
            VDialog("Checking host key", onDismiss = cancel, width = 400.dp, icon = Icons.Key, iconTint = c.accentText) {
                VText("Reading ${verification.host.alias}'s key and looking it up in known_hosts…", type.body, c.textSecondary)
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    var remember by remember(verification) { mutableStateOf(true) }
    VDialog(
        "Verify host key",
        onDismiss = cancel,
        width = 460.dp,
        icon = Icons.Key,
        iconTint = c.warning,
        footer = {
            VText(
                if (check.port == 22) check.host else "${check.host}:${check.port}",
                type.meta,
                c.textMuted,
                Modifier.weight(1f),
            )
            VButton("Cancel", cancel, kind = ButtonKind.Ghost)
            VButton(
                if (verification.saving) "Connecting…" else "Trust & connect",
                { state.trustAndConnect(remember) },
                icon = Icons.Link,
                kind = ButtonKind.Accent,
                enabled = !verification.saving,
            )
        },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(c.warning.copy(alpha = 0.12f), RadiusLg)
                .border(1.dp, c.warning.copy(alpha = 0.55f), RadiusLg)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VIcon(Icons.AlertTriangle, 15.dp, c.warning, Modifier.padding(top = 2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                VText("First connection to ${verification.host.alias}", type.bodyMedium, c.textPrimary)
                VText(
                    "This host isn't in ${state.services.displayPath(check.knownHostsFile)} yet. Compare the fingerprint with your infrastructure docs.",
                    type.caption,
                    c.textSecondary,
                    maxLines = 3,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().background(c.bgInset, RadiusLg).border(1.dp, c.borderSubtle, RadiusLg).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VText("${check.keyType} · ${check.host}${if (check.port == 22) "" else ":${check.port}"}", type.meta, c.textMuted, Modifier.weight(1f))
                VButton("Copy", { clipboard.setText(AnnotatedString(check.fingerprint)) }, icon = Icons.Copy, kind = ButtonKind.Ghost)
            }
            SelectionContainer { VText(check.fingerprint, type.codeSmall, c.textPrimary) }
        }
        CheckRow("Remember this host in known_hosts", remember, { remember = it })
        verification.error?.let { VText(it, type.caption, c.danger) }
    }
}
