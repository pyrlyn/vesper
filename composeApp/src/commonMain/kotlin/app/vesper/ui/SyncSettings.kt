package app.vesper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vesper.app.AppState
import app.vesper.app.Settings
import app.vesper.app.SyncGroup
import app.vesper.ui.components.Badge
import app.vesper.ui.components.ButtonKind
import app.vesper.ui.components.IconButton
import app.vesper.ui.components.Overline
import app.vesper.ui.components.RadiusMd
import app.vesper.ui.components.RadiusXl
import app.vesper.ui.components.Toggle
import app.vesper.ui.components.Tone
import app.vesper.ui.components.VButton
import app.vesper.ui.components.VIcon
import app.vesper.ui.components.VText
import app.vesper.ui.icons.Icons
import app.vesper.ui.theme.Vesper
import app.vesper.util.formatAgo

/** Settings › Sync (Figma 26 "Account & sync"), through a folder the user's devices share instead of an account. */
@Composable
internal fun SyncSection(state: AppState) {
    val c = Vesper.colors
    val type = Vesper.type
    val s = state.settings
    val sync = state.sync
    val folder = s.syncFolder
    Header("Sync", "Keep settings the same on your devices through a folder they share. Nothing goes to a Vesper server.")

    Row(
        Modifier.fillMaxWidth().background(c.bgPanel, RadiusXl).border(1.dp, c.borderSubtle, RadiusXl).padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(c.accentSoft, CircleShape).border(1.dp, c.accentDeep, CircleShape),
            contentAlignment = Alignment.Center,
        ) { VIcon(Icons.Folder, 18.dp, c.accentText) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VText(if (folder.isEmpty()) "Sync is off" else "Sync folder", type.title, c.textPrimary)
                if (folder.isNotEmpty()) Badge(folderKind(folder), Tone.Accent)
            }
            if (folder.isEmpty()) {
                VText("Pick a folder on iCloud Drive, Dropbox or a network share that your other devices use too.", type.caption, c.textMuted, maxLines = 2)
            } else {
                VText(state.services.displayPath(folder), type.caption, c.textMuted, maxLines = 1)
                sync.status?.let { VText(it, type.meta, if (it.startsWith("Couldn't")) c.danger else c.textSecondary) }
            }
        }
        if (folder.isEmpty()) {
            VButton("Choose folder…", { sync.chooseFolder() }, icon = Icons.Folder, kind = ButtonKind.Primary)
        } else {
            VButton(if (sync.busy) "Syncing…" else "Sync now", { sync.syncNow() }, icon = Icons.Refresh, enabled = !sync.busy)
            VButton("Change…", { sync.chooseFolder() }, kind = ButtonKind.Ghost)
            VButton("Stop syncing", { sync.stop() }, kind = ButtonKind.Danger)
        }
    }
    Spacer(Modifier.height(2.dp))

    Group("Sync") {
        SyncGroup.entries.forEachIndexed { i, g ->
            SettingRow(g.title, g.detail, last = i == SyncGroup.entries.lastIndex) {
                Toggle(g.isOn(s), { v -> state.updateSettings { g.set(it, v) } })
            }
        }
    }
    VText(
        "Your API key, shell history, recent directories and window layout stay on each device.",
        type.caption,
        c.textMuted,
        Modifier.padding(start = 2.dp, top = 6.dp, bottom = 10.dp),
    )

    if (folder.isNotEmpty()) {
        Group("Devices") {
            val devices = sync.devices
            if (devices.isEmpty()) {
                VText("Looking for devices…", type.caption, c.textMuted, Modifier.padding(14.dp))
            }
            devices.forEachIndexed { i, d ->
                val me = d.id == s.deviceId
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.background(c.bgElevated, RadiusMd).padding(7.dp)) { VIcon(Icons.Cpu, 14.dp, c.iconDefault) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        VText(d.name, type.bodyMedium, c.textPrimary, maxLines = 1)
                        VText("${d.system} · Vesper ${d.version}", type.caption, c.textMuted, maxLines = 1)
                    }
                    if (me) {
                        Badge("this device", Tone.Success)
                    } else {
                        VText("synced ${formatAgo(d.syncedAt)}", type.meta, c.textMuted)
                        IconButton(Icons.X, { sync.forget(d) }, size = 14.dp)
                    }
                }
                if (i < devices.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            }
        }
    }
}

private fun SyncGroup.isOn(s: Settings): Boolean = when (this) {
    SyncGroup.Settings -> s.syncSettings
    SyncGroup.Keybindings -> s.syncKeybindings
    SyncGroup.Hosts -> s.syncHosts
}

private fun SyncGroup.set(s: Settings, on: Boolean): Settings = when (this) {
    SyncGroup.Settings -> s.copy(syncSettings = on)
    SyncGroup.Keybindings -> s.copy(syncKeybindings = on)
    SyncGroup.Hosts -> s.copy(syncHosts = on)
}

/** "iCloud Drive", "Dropbox"… from where the folder is. */
private fun folderKind(path: String): String = when {
    "Mobile Documents" in path || "iCloud" in path -> "iCloud Drive"
    "Dropbox" in path -> "Dropbox"
    "Google Drive" in path || "GoogleDrive" in path -> "Google Drive"
    "OneDrive" in path -> "OneDrive"
    path.startsWith("/Volumes/") || path.startsWith("\\\\") -> "network"
    else -> "local folder"
}
