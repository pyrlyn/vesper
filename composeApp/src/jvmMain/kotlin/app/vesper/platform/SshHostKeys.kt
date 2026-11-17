package app.vesper.platform

import app.vesper.app.HostKeyCheck
import app.vesper.terminal.LoginEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Host key checks with the system's OpenSSH tools: ssh -G, ssh-keygen and ssh-keyscan. */
internal object SshHostKeys {
    /** Key types in the order OpenSSH prefers them. */
    private val Preferred = listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-512", "ssh-rsa")
    private val HostName = Regex("[A-Za-z0-9._:%-]+")

    fun check(sshArgs: List<String>): HostKeyCheck {
        val target = sshArgs.lastOrNull() ?: return HostKeyCheck.Skipped("no host")
        if (target.startsWith("-")) return HostKeyCheck.Skipped("unusual host name")
        val config = run(listOf("ssh", "-G") + sshArgs, 5_000)?.let(::parseConfig)
            ?: return HostKeyCheck.Skipped("ssh isn't available")
        if (config.isSet("proxyjump") || config.isSet("proxycommand")) return HostKeyCheck.Skipped("connects through a jump host")
        if (config.isSet("hostkeyalias")) return HostKeyCheck.Skipped("uses a host key alias")
        if (config["stricthostkeychecking"] in setOf("false", "no", "off")) return HostKeyCheck.Known
        val host = config["hostname"] ?: return HostKeyCheck.Skipped("no host name")
        if (!HostName.matches(host) || host.startsWith("-")) return HostKeyCheck.Skipped("unusual host name")
        val port = config["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22

        val files = (config["userknownhostsfile"].orEmpty().split(' ') + config["globalknownhostsfile"].orEmpty().split(' '))
            .filter { it.isNotBlank() && it != "none" }
            .map(::expandHome)
        val name = if (port == 22) host else "[$host]:$port"
        if (files.any { File(it).isFile && run(listOf("ssh-keygen", "-F", name, "-f", it), 3_000) != null }) return HostKeyCheck.Known

        val lines = run(listOf("ssh-keyscan", "-T", "5", "-p", port.toString(), host), 9_000).orEmpty()
            .lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.split(' ').size >= 3 }
        if (lines.isEmpty()) return HostKeyCheck.Skipped("couldn't reach $host to read its key")
        val line = Preferred.firstNotNullOfOrNull { t -> lines.firstOrNull { it.split(' ')[1] == t } } ?: lines.first()
        val (type, fingerprint) = fingerprint(line) ?: return HostKeyCheck.Skipped("couldn't read the host key")
        val userFile = config["userknownhostsfile"].orEmpty().split(' ').firstOrNull { it.isNotBlank() && it != "none" }
        return HostKeyCheck.Unknown(host, port, type, fingerprint, lines, expandHome(userFile ?: "~/.ssh/known_hosts"))
    }

    fun trust(check: HostKeyCheck.Unknown, remember: Boolean): List<String>? = runCatching {
        val keys = check.keyLines.joinToString("\n", postfix = "\n")
        if (remember) {
            val file = File(check.knownHostsFile)
            val dir = file.parentFile
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
                restrict(dir, "rwx------")
            }
            val existing = if (file.isFile) file.readText() else ""
            file.appendText(if (existing.isEmpty() || existing.endsWith("\n")) keys else "\n$keys")
            restrict(file, "rw-------")
            listOf("-o", "StrictHostKeyChecking=yes")
        } else {
            // Only this connection reads these keys; createTempFile makes the file owner-only.
            val file = Files.createTempFile("vesper-known-hosts", "").toFile()
            file.deleteOnExit()
            file.writeText(keys)
            listOf("-o", "UserKnownHostsFile=${file.path}", "-o", "StrictHostKeyChecking=yes")
        }
    }.getOrNull()

    /** ("ED25519", "SHA256:…") for one known_hosts line. */
    private fun fingerprint(line: String): Pair<String, String>? {
        val tmp = Files.createTempFile("vesper-key", ".pub").toFile()
        return try {
            tmp.writeText(line + "\n")
            val out = run(listOf("ssh-keygen", "-l", "-f", tmp.path), 3_000)?.trim() ?: return null
            val parts = out.split(' ')
            val fp = parts.getOrNull(1)?.takeIf { it.startsWith("SHA256:") } ?: return null
            val type = out.substringAfterLast('(', "").substringBefore(')').ifEmpty { line.split(' ')[1] }
            type to fp
        } finally {
            tmp.delete()
        }
    }

    private fun parseConfig(text: String): Map<String, String> = text.lines()
        .mapNotNull { l -> l.indexOf(' ').takeIf { it > 0 }?.let { l.substring(0, it).lowercase() to l.substring(it + 1).trim() } }
        .toMap()

    private fun Map<String, String>.isSet(key: String): Boolean = get(key).let { it != null && it != "none" }

    private fun expandHome(path: String): String = when {
        path == "~" -> Paths.home.path
        path.startsWith("~/") -> File(Paths.home, path.substring(2)).path
        else -> path
    }

    private fun restrict(file: File, perms: String) {
        if (HostOs.current != HostOs.Windows) runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms)) }
    }

    /** stdout of [cmd], or null when it fails, exits non-zero or runs past [timeoutMs]. */
    private fun run(cmd: List<String>, timeoutMs: Long): String? = runCatching {
        val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
        LoginEnvironment.path?.let { pb.environment()["PATH"] = it }
        val p = pb.start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return null
        }
        reader.join(500)
        if (p.exitValue() != 0) null else out.toString()
    }.getOrNull()
}
