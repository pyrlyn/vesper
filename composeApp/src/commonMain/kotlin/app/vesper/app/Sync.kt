package app.vesper.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vesper.util.nowMillis
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** File access for folder sync. Blocking; call off the main thread except [pickFolder]. */
interface SyncStore {
    /** Asks the user for a folder; null when cancelled. Runs a modal dialog on the UI thread. */
    fun pickFolder(): String?
    fun read(path: String): String?
    /** Writes atomically, creating parent folders. */
    fun write(path: String, content: String): Boolean
    /** File names in [dir], or empty when it doesn't exist. */
    fun list(dir: String): List<String>
    fun delete(path: String): Boolean
    /** Last-modified time, or null when the file doesn't exist. */
    fun modified(path: String): Long?
    /** "MacBook Pro", for the device list. */
    val deviceName: String
}

/** What syncs, by the toggles in Settings › Sync. Device-specific settings (shell, panels, recent dirs, usage) never do. */
enum class SyncGroup(val key: String, val title: String, val detail: String) {
    Settings("settings", "Settings & themes", "Appearance, fonts, agent options and redaction patterns"),
    Keybindings("keybindings", "Keybindings & workflows", "Including workflow commands; secrets are never stored in them"),
    Hosts("hosts", "Hosts", "Hosts saved in Vesper, without private keys"),
    ;

    /** Settings fields in this group, by their JSON names. */
    val fields: Set<String>
        get() = when (this) {
            Settings -> setOf(
                "theme", "followSystem", "accent", "fontFamily", "fontScale", "ligatures", "confirmQuit", "cursorBlink",
                "material", "glassOpacity", "glassBlur", "glassTint", "reduceTransparency",
                "agentModel", "agentExplainModel", "agentRunMode", "agentAllowDestructive", "agentExplainButton",
                "agentSendOutput", "agentSendGit", "agentSendMonitor", "redactPatterns",
            )
            Keybindings -> setOf("keybindings", "workflows")
            Hosts -> setOf("hosts")
        }
}

/** settings.json in the sync folder. [groups] holds each group's settings fields. */
@Serializable
data class SyncedSettings(
    val updatedAt: Long = 0,
    val device: String = "",
    val groups: Map<String, JsonObject> = emptyMap(),
)

/** devices/<id>.json in the sync folder. */
@Serializable
data class SyncDevice(
    val id: String,
    val name: String,
    val system: String,
    val version: String,
    val syncedAt: Long,
)

private val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/** The synced groups of [settings], each as a JSON object of its fields. */
fun syncGroups(settings: Settings, groups: Collection<SyncGroup>): Map<String, JsonObject> {
    val all = SyncJson.encodeToJsonElement(Settings.serializer(), settings).jsonObject
    return groups.associate { g -> g.key to JsonObject(all.filterKeys { it in g.fields }) }
}

/** [settings] with the fields of [groups] taken from [remote]; fields the remote copy lacks keep their value. */
fun applySyncGroups(settings: Settings, remote: Map<String, JsonObject>, groups: Collection<SyncGroup>): Settings {
    val merged = SyncJson.encodeToJsonElement(Settings.serializer(), settings).jsonObject.toMutableMap()
    for (g in groups) remote[g.key]?.forEach { (k, v) -> if (k in g.fields) merged[k] = v }
    return runCatching { SyncJson.decodeFromJsonElement(Settings.serializer(), JsonObject(merged)) }.getOrDefault(settings)
}

/**
 * Keeps settings in step with a folder the user's devices share (iCloud Drive, Dropbox, a network
 * drive…). The newest write wins; nothing leaves the machine except through that folder.
 */
class SyncController(private val state: AppState, private val scope: CoroutineScope) {
    private val store get() = state.services.sync
    private val settings get() = state.settings

    var devices by mutableStateOf<List<SyncDevice>>(emptyList())
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private var watcher: Job? = null
    private var pending: Job? = null
    private var seenModified: Long? = null
    /** The synced groups as last written or applied, so an applied change isn't written back. */
    private var lastGroups: Map<String, JsonObject>? = null
    private var deviceWrittenAt = 0L

    private val root get() = settings.syncFolder.trimEnd('/', '\\') + "/Vesper"
    private val settingsPath get() = "$root/settings.json"
    private val devicesDir get() = "$root/devices"

    val enabledGroups: List<SyncGroup>
        get() = buildList {
            if (settings.syncSettings) add(SyncGroup.Settings)
            if (settings.syncKeybindings) add(SyncGroup.Keybindings)
            if (settings.syncHosts) add(SyncGroup.Hosts)
        }

    /** Called once settings are loaded. */
    fun start() {
        if (settings.deviceId.isEmpty()) {
            val id = Random.nextBytes(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            state.updateSettings { it.copy(deviceId = id) }
        }
        if (settings.syncFolder.isNotEmpty()) watch()
    }

    /** Lets the user pick the shared folder, then syncs with it. */
    fun chooseFolder() {
        val folder = runCatching { store.pickFolder() }.getOrNull() ?: return
        state.updateSettings { it.copy(syncFolder = folder, syncedAt = 0) }
        lastGroups = null
        seenModified = null
        watch()
    }

    fun stop() {
        watcher?.cancel()
        pending?.cancel()
        state.updateSettings { it.copy(syncFolder = "", syncedAt = 0) }
        devices = emptyList()
        status = null
        lastGroups = null
    }

    fun syncNow() {
        if (settings.syncFolder.isEmpty() || busy) return
        scope.launch { pullThenPush(force = true) }
    }

    /** Removes another device from the list; it shows up again the next time it syncs. */
    fun forget(device: SyncDevice) {
        if (device.id == settings.deviceId) return
        scope.launch {
            withContext(Dispatchers.Default) { store.delete("$devicesDir/${device.id}.json") }
            devices = devices.filter { it.id != device.id }
        }
    }

    /** After any settings change: writes the synced groups when they changed. */
    fun onLocalChange() {
        if (settings.syncFolder.isEmpty()) return
        pending?.cancel()
        pending = scope.launch {
            delay(1_500)
            push(force = false)
        }
    }

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            pullThenPush(force = false)
            while (isActive) {
                delay(15_000)
                val modified = withContext(Dispatchers.Default) { store.modified(settingsPath) }
                if (modified != seenModified) pullThenPush(force = false) else refreshDevices()
            }
        }
    }

    private suspend fun pullThenPush(force: Boolean) {
        busy = true
        try {
            pull()
            push(force)
        } finally {
            busy = false
        }
    }

    /** Applies the folder's settings when another device wrote them after we last synced. */
    private suspend fun pull() {
        val folder = settings.syncFolder
        val remote = withContext(Dispatchers.Default) {
            seenModified = store.modified(settingsPath)
            store.read(settingsPath)?.let { runCatching { SyncJson.decodeFromString(SyncedSettings.serializer(), it) }.getOrNull() }
        }
        if (folder != settings.syncFolder) return
        if (remote == null || remote.device == settings.deviceId || remote.updatedAt <= settings.syncedAt) return
        val groups = enabledGroups
        val next = applySyncGroups(settings, remote.groups, groups).copy(syncedAt = remote.updatedAt)
        lastGroups = syncGroups(next, groups)
        state.updateSettings { next }
        status = "Updated from ${deviceName(remote.device)}"
    }

    /** Writes this device's groups (keeping the folder's copy of groups that are off here) and its device entry. */
    private suspend fun push(force: Boolean) {
        val folder = settings.syncFolder
        if (folder.isEmpty()) return
        val groups = enabledGroups
        val local = syncGroups(settings, groups)
        val changed = local != lastGroups
        val now = nowMillis()
        val ok = withContext(Dispatchers.Default) {
            var ok = true
            if (changed || force) {
                val remote = store.read(settingsPath)?.let { runCatching { SyncJson.decodeFromString(SyncedSettings.serializer(), it) }.getOrNull() }
                val out = SyncedSettings(now, settings.deviceId, (remote?.groups ?: emptyMap()) + local)
                ok = store.write(settingsPath, SyncJson.encodeToString(SyncedSettings.serializer(), out))
                if (ok) seenModified = store.modified(settingsPath)
            }
            // The device entry says when this device last synced; refreshed at most every few minutes otherwise.
            if (ok && (changed || force || now - deviceWrittenAt > 5 * 60_000)) {
                val me = SyncDevice(settings.deviceId, store.deviceName, state.services.systemInfo.substringBefore(" · "), state.services.appVersion, now)
                ok = store.write("$devicesDir/${settings.deviceId}.json", SyncJson.encodeToString(SyncDevice.serializer(), me))
                if (ok) deviceWrittenAt = now
            }
            ok
        }
        if (folder != settings.syncFolder) return
        if (!ok) {
            status = "Couldn't write to the sync folder"
            return
        }
        if (changed || force) {
            lastGroups = local
            state.updateSettings { it.copy(syncedAt = now) }
            if (status == null || force) status = "Synced"
        }
        refreshDevices()
    }

    private suspend fun refreshDevices() {
        val found = withContext(Dispatchers.Default) {
            store.list(devicesDir).filter { it.endsWith(".json") }.mapNotNull { name ->
                store.read("$devicesDir/$name")?.let { runCatching { SyncJson.decodeFromString(SyncDevice.serializer(), it) }.getOrNull() }
            }
        }
        devices = found.sortedWith(compareByDescending<SyncDevice> { it.id == settings.deviceId }.thenByDescending { it.syncedAt })
    }

    private fun deviceName(id: String): String = devices.firstOrNull { it.id == id }?.name ?: "another device"
}
