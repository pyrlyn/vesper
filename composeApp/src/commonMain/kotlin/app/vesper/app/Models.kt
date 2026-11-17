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

data class HistoryEntry(val command: String, val at: Long, val cwd: String)

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
