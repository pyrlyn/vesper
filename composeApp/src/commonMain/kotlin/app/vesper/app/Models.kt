package app.vesper.app

import app.vesper.platform.HostOs
import app.vesper.terminal.TerminalSession
import app.vesper.ui.theme.AccentId
import app.vesper.ui.theme.ThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class Workflow(
    val name: String,
    val steps: List<String>,
    val description: String = "",
    val requiresApproval: Boolean = false,
)

@Serializable
data class RecentDir(val path: String, val lastUsed: Long)

@Serializable
data class SavedHost(
    val alias: String,
    val host: String,
    val user: String = "",
    val port: Int = 22,
    val tag: String = "",
    val group: String = "Saved",
    val identityFile: String = "",
    val jumpHost: String = "",
    val startupCommand: String = "",
    val confirmDestructive: Boolean = true,
    val forwardAgent: Boolean = false,
)

@Serializable
data class Settings(
    val theme: ThemeId = ThemeId.VesperDark,
    val followSystem: Boolean = false,
    val accent: AccentId = AccentId.Violet,
    val fontFamily: String = "JetBrains Mono",
    val fontScale: Float = 1f,
    val ligatures: Boolean = false,
    val showMonitor: Boolean = true,
    val showSidebar: Boolean = true,
    val confirmQuit: Boolean = true,
    val shell: String = "",
    val cursorBlink: Boolean = true,
    val recentDirs: List<RecentDir> = emptyList(),
    val workflows: List<Workflow> = DefaultWorkflows,
    val hosts: List<SavedHost> = emptyList(),
    val onboarded: Boolean = false,
    val material: WindowMaterial = WindowMaterial.Glass,
    /** Glass opacity, 0.2–1 (the design's default is 58%). */
    val glassOpacity: Float = 0.58f,
    /** Blur of what is behind the window, in points. */
    val glassBlur: Int = 48,
    val glassTint: Boolean = true,
    val reduceTransparency: Boolean = false,
    /** Shortcut overrides: action name → combo text ("shift+D"), "" for none. */
    val keybindings: Map<String, String> = emptyMap(),
    /** Model for "#" questions. */
    val agentModel: String = AgentModel.Opus.id,
    /** Model for error explanations (Settings › AI agent › Complex tasks). */
    val agentExplainModel: String = AgentModel.Opus.id,
    val agentRunMode: AgentRunMode = AgentRunMode.Ask,
    val agentAllowDestructive: Boolean = false,
    /** An Explain button on blocks that exit non-zero. */
    val agentExplainButton: Boolean = true,
    val agentSendOutput: Boolean = true,
    val agentSendGit: Boolean = true,
    val agentSendMonitor: Boolean = false,
    val redactPatterns: List<String> = DefaultRedactPatterns,
    val agentRecent: List<String> = emptyList(),
    val agentUsage: AgentUsage = AgentUsage(),
    /** Folder the user's devices share for sync (Settings › Sync); empty when sync is off. */
    val syncFolder: String = "",
    val syncSettings: Boolean = true,
    val syncKeybindings: Boolean = true,
    val syncHosts: Boolean = false,
    /** This device in the sync folder's device list. */
    val deviceId: String = "",
    /** When the synced settings this device has were written. */
    val syncedAt: Long = 0,
)

/** Window material (Settings › Appearance › Window material). Glass is macOS-only for now. */
@Serializable
enum class WindowMaterial { Solid, Glass }

val DefaultWorkflows = listOf(
    Workflow("Git: sync branch", listOf("git pull --rebase", "git push"), "Rebase on upstream, then push"),
    Workflow("Disk usage", listOf("df -h"), "Free space on mounted volumes"),
    Workflow("Listening ports", listOf("lsof -iTCP -sTCP:LISTEN -n -P"), "Processes accepting TCP connections"),
)

/** A host to connect to: from ~/.ssh/config or saved in Vesper. */
data class HostEntry(
    val alias: String,
    val hostName: String,
    val user: String,
    val port: Int,
    val identityFile: String,
    val proxyJump: String,
    val source: String,
    val tag: String = "",
    val group: String = "SSH config",
) {
    val address: String get() = buildString {
        if (user.isNotEmpty()) append(user).append('@')
        append(hostName.ifEmpty { alias })
        if (port != 22) append(':').append(port)
    }
}

/** A finished command. [exitCode] is null when the shell did not report one. */
@Serializable
data class HistoryEntry(
    val command: String,
    val at: Long,
    val cwd: String,
    val exitCode: Int? = null,
    val durationMs: Long = 0,
) {
    val failed: Boolean get() = exitCode != null && exitCode != 0
}

/** What the prompt chips show for the current directory. */
data class PromptContext(
    val gitBranch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val changes: Int = 0,
    val nodeVersion: String? = null,
    val packageManager: String? = null,
    val packageManagerVersion: String? = null,
    val packageScripts: List<String> = emptyList(),
)

data class ProcessInfo(val pid: Long, val name: String, val cpu: Double, val memBytes: Long)

data class PortInfo(val port: Int, val protocol: String, val address: String, val pid: Long, val process: String)

data class MonitorSnapshot(
    val cpuTotal: Double = 0.0,
    val cpuHistory: List<Double> = emptyList(),
    val perCore: List<Double> = emptyList(),
    val coreCount: Int = 0,
    val memUsed: Long = 0,
    val memCached: Long = 0,
    val memTotal: Long = 0,
    val swapUsed: Long = 0,
    val processes: List<ProcessInfo> = emptyList(),
    val netDownBps: Double = 0.0,
    val netUpBps: Double = 0.0,
    val netHistory: List<Double> = emptyList(),
)

interface SystemMonitor {
    val snapshot: StateFlow<MonitorSnapshot>
    fun start(scope: CoroutineScope)
    suspend fun listeningPorts(): List<PortInfo>
    fun kill(pid: Long): Boolean
}

/** Platform services the UI depends on. */
interface AppServices {
    val os: HostOs
    val homeDir: String
    val configPath: String
    val appVersion: String
    val monitor: SystemMonitor
    val agent: AgentService
    val sync: SyncStore

    fun createSession(
        id: Int,
        cwd: String,
        shell: String,
        scope: CoroutineScope,
        cols: Int,
        rows: Int,
        colorQuery: (Int) -> String?,
    ): TerminalSession

    fun loadSettings(): Settings
    fun saveSettings(settings: Settings)
    fun loadHistory(): List<HistoryEntry>
    fun saveHistory(history: List<HistoryEntry>)

    /** Writes [content] to a new file named like [fileName] in Downloads (or home); returns its path. */
    fun exportFile(fileName: String, content: String): String?
    /** "macOS 15.2 · aarch64 · Java 21.0.5", for About. */
    val systemInfo: String
    /** Host key check before an SSH connection, for ssh arguments like ["-p", "2222", "prod"]. Blocking. */
    fun checkHostKey(sshArgs: List<String>): HostKeyCheck
    /**
     * Trusts the keys [check] found: adds them to known_hosts when [remember], else to a file only this connection uses.
     * Returns the ssh options that make ssh accept exactly those keys, or null when they could not be saved.
     */
    fun trustHostKey(check: HostKeyCheck.Unknown, remember: Boolean): List<String>?
    /** What onboarding can show about the user's shell setup. Blocking. */
    fun shellSetup(): ShellSetup
    /** Time-stamped commands from the login shell's history file, oldest first. Blocking. */
    fun importShellHistory(): List<HistoryEntry>
    fun sshHosts(): List<HostEntry>
    fun shellHistory(limit: Int): List<String>

    /** Blocking; call off the main thread. */
    fun probeContext(cwd: String): PromptContext
    fun exists(path: String): Boolean
    fun displayPath(path: String): String
    fun openUrl(url: String)
    fun revealInFileManager(path: String)
    fun systemPrefersDark(): Boolean
    fun installedMonoFonts(): List<String>
}

/** Result of checking a host's key against known_hosts before connecting. */
sealed interface HostKeyCheck {
    /** Already in known_hosts, or the user's ssh config turns checking off. */
    data object Known : HostKeyCheck

    /** First connection: the key the host presents now, to compare and trust. */
    data class Unknown(
        val host: String,
        val port: Int,
        /** "ED25519" */
        val keyType: String,
        /** "SHA256:…" */
        val fingerprint: String,
        /** Lines in known_hosts format for every key the host offered. */
        val keyLines: List<String>,
        val knownHostsFile: String,
    ) : HostKeyCheck

    /** Vesper cannot check this host itself (jump host, ssh tools missing, unreachable); ssh asks in the terminal. */
    data class Skipped(val reason: String) : HostKeyCheck
}

data class ShellSetup(
    val shell: String,
    /** e.g. "~/.zshrc", null when there is none */
    val rcFile: String?,
    val aliases: Int,
    val functions: Int,
    val historyFile: String?,
    val historyCommands: Int,
    /** Shells from /etc/shells (or found on PATH on Windows). */
    val shells: List<String>,
)
