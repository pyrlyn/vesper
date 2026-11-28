package vesper.config

import okio.Path
import okio.Path.Companion.toPath

/**
 * Where the config file lives when the host names no other: `vesper/config.toml` under
 * `%APPDATA%` on Windows, otherwise under `$XDG_CONFIG_HOME` or `~/.config`. macOS uses
 * `~/.config` too, where terminal users look for it. [environment] reads a variable; `null` when
 * the environment has no home to build the path from.
 */
public fun defaultConfigPath(
    windows: Boolean,
    environment: (String) -> String?,
): String? {
    // A relative base would put the file wherever the program happens to run.
    fun absolute(name: String): Path? = environment(name)?.takeIf { it.isNotEmpty() }?.toPath()?.takeIf { it.isAbsolute }
    val base =
        if (windows) {
            absolute("APPDATA")
        } else {
            absolute("XDG_CONFIG_HOME") ?: absolute("HOME")?.div(".config")
        }
    return base?.div("vesper")?.div("config.toml")?.toString()
}
