package app.vesper.platform

import kotlinx.serialization.builtins.ListSerializer
import app.vesper.app.HistoryEntry
import app.vesper.app.AppServices
import app.vesper.app.HostEntry
import app.vesper.app.PromptContext
import app.vesper.app.Settings
import app.vesper.app.SystemMonitor
import app.vesper.terminal.LoginEnvironment
import app.vesper.terminal.Pty4jLauncher
import app.vesper.terminal.ShellIntegration
import app.vesper.terminal.TerminalSession
import java.awt.Desktop
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JvmServices : AppServices {
    override val os: HostOs = HostOs.current
    override val homeDir: String = Paths.home.path
    private val settingsFile = File(Paths.configDir, "settings.json")
    private val historyFile = File(Paths.configDir, "history.json")
    override val configPath: String = settingsFile.path
    override val appVersion: String = Paths.appVersion
    override val monitor: SystemMonitor by lazy { OshiMonitor() }
    private val launcher = Pty4jLauncher()

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    override fun createSession(
        id: Int,
        cwd: String,
        shell: String,
        scope: CoroutineScope,
        cols: Int,
        rows: Int,
        colorQuery: (Int) -> String?,
    ): TerminalSession {
        val exe = shell.takeIf { it.isNotBlank() && (File(it).canExecute() || os == HostOs.Windows) } ?: ShellIntegration.defaultShell()
        return TerminalSession(id, ShellIntegration.build(exe, cwd), launcher, scope, cols, rows, colorQuery)
    }

    // ── Settings ─────────────────────────────────────────────────────────

    override fun loadSettings(): Settings = runCatching {
        if (settingsFile.isFile) json.decodeFromString(Settings.serializer(), settingsFile.readText()) else Settings()
    }.getOrElse {
        // Keep the unreadable file for the user instead of silently overwriting it.
        runCatching { settingsFile.copyTo(File(settingsFile.path + ".broken"), overwrite = true) }
        Settings()
    }

    @Synchronized
    override fun saveSettings(settings: Settings) {
        val tmp = File(settingsFile.parentFile, settingsFile.name + ".tmp")
        tmp.writeText(json.encodeToString(Settings.serializer(), settings))
        Files.move(tmp.toPath(), settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // ── History ──────────────────────────────────────────────────────────

    override fun loadHistory(): List<HistoryEntry> = runCatching {
        if (historyFile.isFile) json.decodeFromString(historyListSerializer, historyFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    override fun saveHistory(history: List<HistoryEntry>) {
        historyFile.parentFile?.mkdirs()
        val tmp = File(historyFile.parentFile, historyFile.name + ".tmp")
        tmp.writeText(compactJson.encodeToString(historyListSerializer, history))
        Files.move(tmp.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private val historyListSerializer = ListSerializer(HistoryEntry.serializer())
    private val compactJson = Json { ignoreUnknownKeys = true }

    override fun exportFile(fileName: String, content: String): String? = runCatching {
        val dir = File(Paths.home, "Downloads").takeIf { it.isDirectory } ?: Paths.home
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "-").ifEmpty { "vesper-export.txt" }
        val base = safe.substringBeforeLast('.')
        val ext = safe.substringAfterLast('.', "")
        var file = File(dir, safe)
        var n = 2
        while (file.exists()) file = File(dir, if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext").also { n++ }
        file.writeText(content)
        file.path
    }.getOrNull()

    // ── SSH config ───────────────────────────────────────────────────────

    override fun sshHosts(): List<HostEntry> {
        val config = File(Paths.home, ".ssh/config")
        if (!config.isFile) return emptyList()
        val out = LinkedHashMap<String, HostEntry>()
        parseSshConfig(config, out, depth = 0)
        return out.values.toList()
    }

    private fun parseSshConfig(file: File, out: MutableMap<String, HostEntry>, depth: Int) {
        if (depth > 4 || !file.isFile) return
        var current: List<String> = emptyList()
        val props = HashMap<String, MutableMap<String, String>>()
        fun flush() {
            for (alias in current) {
                val p = props[alias] ?: continue
                out.putIfAbsent(
                    alias,
                    HostEntry(
                        alias = alias,
                        hostName = p["hostname"] ?: "",
                        user = p["user"] ?: "",
                        port = p["port"]?.toIntOrNull() ?: 22,
                        identityFile = p["identityfile"] ?: "",
                        proxyJump = p["proxyjump"] ?: "",
                        source = "ssh-config",
                    ),
                )
            }
        }
        for (raw in runCatching { file.readLines() }.getOrDefault(emptyList())) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(' ').substringBefore('=').substringBefore('\t').lowercase()
            val value = line.substring(key.length).trimStart(' ', '\t', '=').trim().removeSurrounding("\"")
            when (key) {
                "host" -> {
                    flush()
                    current = value.split(Regex("\\s+")).filter { a -> a.none { it in "*?!" } }
                    current.forEach { props.getOrPut(it) { HashMap() } }
                }
                "match" -> {
                    flush()
                    current = emptyList()
                }
                "include" -> value.split(Regex("\\s+")).forEach { pattern -> includeFiles(pattern).forEach { parseSshConfig(it, out, depth + 1) } }
                else -> current.forEach { alias -> props[alias]?.putIfAbsent(key, value) }
            }
        }
        flush()
    }

    private fun includeFiles(pattern: String): List<File> {
        val expanded = pattern.replaceFirst(Regex("^~"), Paths.home.path.replace("\\", "\\\\"))
        val path = if (File(expanded).isAbsolute) File(expanded) else File(Paths.home, ".ssh/$expanded")
        if (!path.name.contains('*') && !path.name.contains('?')) return listOf(path)
        val dir = path.parentFile ?: return emptyList()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:" + path.name)
        return dir.listFiles()?.filter { matcher.matches(it.toPath().fileName) }?.sortedBy { it.name } ?: emptyList()
    }

    // ── Shell history ────────────────────────────────────────────────────

    override fun shellHistory(limit: Int): List<String> {
        val shell = File(ShellIntegration.defaultShell()).name.removeSuffix(".exe").lowercase()
        val lines = when {
            shell == "zsh" -> zshHistory(zshHistoryFile())
            shell == "bash" -> readLenient(File(Paths.home, ".bash_history")).lines().filter { !it.startsWith("#") }
            shell == "fish" -> readLenient(File(Paths.home, ".local/share/fish/fish_history")).lines()
                .filter { it.startsWith("- cmd: ") }.map { it.removePrefix("- cmd: ").replace("\\n", "\n").replace("\\\\", "\\") }
            shell.startsWith("pwsh") || shell.startsWith("powershell") -> readLenient(
                File(System.getenv("APPDATA") ?: "", "Microsoft/Windows/PowerShell/PSReadLine/ConsoleHost_history.txt"),
            ).lines()
            else -> emptyList()
        }
        return lines.map { it.trim() }.filter { it.isNotEmpty() && it.length < 500 }.takeLast(limit)
    }

    private fun zshHistory(file: File): List<String> = zshHistoryEntries(file).map { it.command }

    private class ShellCommand(val command: String, val atSeconds: Long?, val durationSeconds: Long = 0)

    /** zsh history; extended entries (": <start>:<elapsed>;command") carry their time. */
    private fun zshHistoryEntries(file: File): List<ShellCommand> {
        val text = unmetafy(readBytes(file))
        val out = ArrayList<ShellCommand>()
        val sb = StringBuilder()
        var at: Long? = null
        var elapsed = 0L
        for (line in text.lines()) {
            val content = if (sb.isEmpty() && line.startsWith(": ") && line.contains(';')) {
                val meta = line.substring(2).substringBefore(';').split(':')
                at = meta.getOrNull(0)?.trim()?.toLongOrNull()
                elapsed = meta.getOrNull(1)?.trim()?.toLongOrNull() ?: 0
                line.substringAfter(';')
            } else {
                if (sb.isEmpty()) { at = null; elapsed = 0 }
                line
            }
            if (content.endsWith("\\")) {
                sb.append(content.dropLast(1)).append('\n')
            } else {
                sb.append(content)
                out += ShellCommand(sb.toString(), at, elapsed)
                sb.setLength(0)
            }
        }
        return out
    }

    override fun importShellHistory(): List<HistoryEntry> {
        val shell = File(ShellIntegration.defaultShell()).name.removeSuffix(".exe").lowercase()
        val commands: List<ShellCommand> = when (shell) {
            "zsh" -> zshHistoryEntries(zshHistoryFile())
            "bash" -> {
                // With HISTTIMEFORMAT set, bash writes "#<epoch>" before each command.
                var at: Long? = null
                readLenient(File(Paths.home, ".bash_history")).lines().mapNotNull { l ->
                    if (l.startsWith("#") && l.drop(1).all(Char::isDigit) && l.length > 1) { at = l.drop(1).toLongOrNull(); null }
                    else ShellCommand(l, at).also { at = null }
                }
            }
            "fish" -> {
                val out = ArrayList<ShellCommand>()
                var cmd: String? = null
                for (l in readLenient(fishHistoryFile()).lines()) {
                    when {
                        l.startsWith("- cmd: ") -> cmd = l.removePrefix("- cmd: ").replace("\\n", "\n").replace("\\\\", "\\")
                        l.trimStart().startsWith("when: ") && cmd != null -> {
                            out += ShellCommand(cmd!!, l.trim().removePrefix("when: ").toLongOrNull())
                            cmd = null
                        }
                    }
                }
                out
            }
            else -> emptyList()
        }
        return commands
            .filter { it.atSeconds != null && it.command.isNotBlank() && it.command.length < 500 }
            .map { HistoryEntry(it.command.trim(), it.atSeconds!! * 1000, "", null, it.durationSeconds * 1000) }
            .sortedBy { it.at }
    }

    private fun zshHistoryFile() = File(System.getenv("HISTFILE") ?: File(Paths.home, ".zsh_history").path)
    private fun fishHistoryFile() = File(Paths.home, ".local/share/fish/fish_history")

    override val systemInfo: String by lazy {
        val osName = when (os) {
            HostOs.MacOS -> "macOS"
            HostOs.Windows -> "Windows"
            HostOs.Linux -> "Linux"
        }
        "$osName ${System.getProperty("os.version")} · ${System.getProperty("os.arch")} · Java ${System.getProperty("java.version")}"
    }

    override fun checkHostKey(sshArgs: List<String>): app.vesper.app.HostKeyCheck = SshHostKeys.check(sshArgs)

    override fun trustHostKey(check: app.vesper.app.HostKeyCheck.Unknown, remember: Boolean): List<String>? = SshHostKeys.trust(check, remember)

    override fun shellSetup(): app.vesper.app.ShellSetup {
        val path = ShellIntegration.defaultShell()
        val shell = File(path).name.removeSuffix(".exe").lowercase()
        val rc = when (shell) {
            "zsh" -> File(System.getenv("ZDOTDIR")?.takeIf { it.isNotBlank() } ?: Paths.home.path, ".zshrc")
            "bash" -> File(Paths.home, ".bashrc").takeIf { it.isFile } ?: File(Paths.home, ".bash_profile")
            "fish" -> File(Paths.home, ".config/fish/config.fish")
            else -> null
        }?.takeIf { it.isFile }
        val rcText = rc?.let(::readLenient).orEmpty()
        val aliases = rcText.lines().count { Regex("^\\s*(alias|abbr)\\s").containsMatchIn(it) }
        val functions = rcText.lines().count { Regex("^\\s*(function\\s+[\\w.-]+|[\\w.-]+\\s*\\(\\))").containsMatchIn(it) }
        val historyFile = when (shell) {
            "zsh" -> zshHistoryFile()
            "bash" -> File(Paths.home, ".bash_history")
            "fish" -> fishHistoryFile()
            else -> null
        }?.takeIf { it.isFile }
        val shells = if (os == HostOs.Windows) {
            listOf(path)
        } else {
            readLenient(File("/etc/shells")).lines().map { it.trim() }
                .filter { it.startsWith("/") && File(it).canExecute() }
                .ifEmpty { listOf(path) }
        }
        return app.vesper.app.ShellSetup(
            shell = path,
            rcFile = rc?.let { displayPath(it.path) },
            aliases = aliases,
            functions = functions,
            historyFile = historyFile?.let { displayPath(it.path) },
            historyCommands = if (historyFile == null) 0 else shellHistory(1_000_000).size,
            shells = (listOf(path) + shells).distinct(),
        )
    }

    /** zsh stores non-ASCII bytes "metafied": 0x83 followed by byte xor 0x20. */
    private fun unmetafy(bytes: ByteArray): String {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x83 && i + 1 < bytes.size) {
                out.write((bytes[i + 1].toInt() and 0xFF) xor 0x20)
                i += 2
            } else {
                out.write(b)
                i++
            }
        }
        return decodeLenient(out.toByteArray())
    }

    private fun readBytes(file: File): ByteArray = runCatching {
        if (!file.isFile) return ByteArray(0)
        val max = 2L * 1024 * 1024
        file.inputStream().use { input ->
            val len = file.length()
            if (len > max) input.skip(len - max)
            input.readBytes()
        }
    }.getOrDefault(ByteArray(0))

    private fun readLenient(file: File): String = decodeLenient(readBytes(file))

    private fun decodeLenient(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()

    // ── Prompt context ───────────────────────────────────────────────────

    private val toolVersions = ConcurrentHashMap<String, String>()

    override fun probeContext(cwd: String): PromptContext {
        val dir = File(cwd)
        if (!dir.isDirectory) return PromptContext()
        var ctx = PromptContext()
        findUp(dir, ".git")?.let { gitDir ->
            ctx = ctx.copy(gitBranch = readBranch(gitDir))
            run(listOf("git", "status", "--porcelain=v2", "--branch"), dir, 1500)?.let { out ->
                var changes = 0
                for (line in out.lineSequence()) {
                    when {
                        line.startsWith("# branch.head ") -> line.removePrefix("# branch.head ").takeIf { it != "(detached)" }?.let { ctx = ctx.copy(gitBranch = it) }
                        line.startsWith("# branch.ab ") -> {
                            val parts = line.removePrefix("# branch.ab ").split(' ')
                            ctx = ctx.copy(
                                ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0,
                                behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0,
                            )
                        }
                        line.isNotBlank() && !line.startsWith("#") -> changes++
                    }
                }
                ctx = ctx.copy(changes = changes)
            }
        }
        val pkgFile = findUp(dir, "package.json")?.takeIf { it.isFile }
        if (pkgFile != null) {
            val root = pkgFile.parentFile
            val pkg = runCatching { Json.parseToJsonElement(pkgFile.readText()).jsonObject }.getOrNull()
            val scripts = (pkg?.get("scripts") as? JsonObject)?.keys?.toList() ?: emptyList()
            val declared = pkg?.get("packageManager")?.jsonPrimitive?.content
            val pm = declared?.substringBefore('@') ?: when {
                File(root, "pnpm-lock.yaml").exists() -> "pnpm"
                File(root, "yarn.lock").exists() -> "yarn"
                File(root, "bun.lockb").exists() || File(root, "bun.lock").exists() -> "bun"
                else -> "npm"
            }
            val pmVersion = declared?.substringAfter('@', "")?.substringBefore('+')?.ifEmpty { null } ?: toolVersion(pm)
            ctx = ctx.copy(
                nodeVersion = toolVersion("node")?.removePrefix("v"),
                packageManager = pm,
                packageManagerVersion = pmVersion,
                packageScripts = scripts,
            )
        }
        return ctx
    }

    private fun toolVersion(tool: String): String? {
        toolVersions[tool]?.let { return it.ifEmpty { null } }
        val exe = if (os == HostOs.Windows && tool != "node") "$tool.cmd" else tool
        val v = run(listOf(exe, "--version"), Paths.home, 3000)?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        toolVersions[tool] = v
        return v.ifEmpty { null }
    }

    private fun readBranch(gitDirOrFile: File): String? = runCatching {
        val gitDir = if (gitDirOrFile.isFile) {
            File(gitDirOrFile.parentFile, gitDirOrFile.readText().trim().removePrefix("gitdir:").trim()).canonicalFile
        } else {
            gitDirOrFile
        }
        val head = File(gitDir, "HEAD").readText().trim()
        if (head.startsWith("ref: refs/heads/")) head.removePrefix("ref: refs/heads/") else head.take(7)
    }.getOrNull()

    private fun findUp(start: File, name: String): File? {
        var d: File? = start
        while (d != null) {
            val f = File(d, name)
            if (f.exists()) return f
            d = d.parentFile
        }
        return null
    }

    /** Runs a short command with the login PATH; null on failure or timeout. */
    private fun run(cmd: List<String>, dir: File, timeoutMs: Long): String? = runCatching {
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(false)
        LoginEnvironment.path?.let { pb.environment()["PATH"] = it }
        pb.environment()["GIT_OPTIONAL_LOCKS"] = "0"
        val p = pb.start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        reader.join(200)
        if (p.exitValue() != 0) null else out.toString()
    }.getOrNull()

    // ── Desktop ──────────────────────────────────────────────────────────

    override fun exists(path: String): Boolean = File(path).isDirectory

    override fun displayPath(path: String): String = Paths.displayPath(path)

    override fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        launch(
            when (os) {
                HostOs.MacOS -> listOf("open", url)
                HostOs.Windows -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                HostOs.Linux -> listOf("xdg-open", url)
            },
        )
    }

    override fun revealInFileManager(path: String) {
        val f = File(path)
        launch(
            when (os) {
                HostOs.MacOS -> if (f.isDirectory) listOf("open", path) else listOf("open", "-R", path)
                HostOs.Windows -> if (f.isDirectory) listOf("explorer", path) else listOf("explorer", "/select,", path)
                HostOs.Linux -> listOf("xdg-open", if (f.isDirectory) path else (f.parent ?: path))
            },
        )
    }

    private fun launch(cmd: List<String>) {
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().outputStream.close() }
    }

    override fun systemPrefersDark(): Boolean = when (os) {
        HostOs.MacOS -> run(listOf("defaults", "read", "-g", "AppleInterfaceStyle"), Paths.home, 1500)?.trim() == "Dark"
        HostOs.Windows -> run(
            listOf("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"),
            Paths.home,
            1500,
        )?.contains("0x0") ?: true
        HostOs.Linux -> {
            val scheme = run(listOf("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"), Paths.home, 1500)
            val theme = run(listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"), Paths.home, 1500)
            when {
                scheme != null && scheme.contains("dark") -> true
                scheme != null && scheme.contains("light") -> false
                theme != null -> theme.contains("dark", ignoreCase = true)
                else -> true
            }
        }
    }

    private var monoFonts: List<String>? = null

    override fun installedMonoFonts(): List<String> {
        monoFonts?.let { return it }
        val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        val result = families.filter { name ->
            if (name.startsWith(".") || name.equals("Monospaced", true) || name.equals("Dialog", true) || name.equals("DialogInput", true)) return@filter false
            runCatching {
                val fm = g.getFontMetrics(Font(name, Font.PLAIN, 20))
                val font = fm.font
                font.canDisplayUpTo("Wi.m") == -1 && fm.charWidth('i') == fm.charWidth('W') && fm.charWidth('.') == fm.charWidth('m')
            }.getOrDefault(false)
        }.distinct().sorted()
        g.dispose()
        monoFonts = result
        return result
    }
}
