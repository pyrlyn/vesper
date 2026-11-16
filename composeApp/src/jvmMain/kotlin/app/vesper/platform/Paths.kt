package app.vesper.platform

import java.io.File

actual fun detectHostOs(): HostOs {
    val name = System.getProperty("os.name").lowercase()
    return when {
        name.contains("mac") || name.contains("darwin") -> HostOs.MacOS
        name.contains("win") -> HostOs.Windows
        else -> HostOs.Linux
    }
}

object Paths {
    const val appVersion = "0.1.0"

    val home: File = File(System.getProperty("user.home"))

    /** ~/.config/vesper on macOS/Linux (XDG aware), %APPDATA%\Vesper on Windows. */
    val configDir: File by lazy {
        val dir = when (HostOs.current) {
            HostOs.Windows -> File(System.getenv("APPDATA") ?: home.path, "Vesper")
            else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path, "vesper")
        }
        dir.apply { mkdirs() }
    }

    fun displayPath(path: String): String {
        val h = home.path
        return if (path == h) "~" else if (path.startsWith(h + File.separator) || path.startsWith("$h/")) "~" + path.substring(h.length) else path
    }
}
