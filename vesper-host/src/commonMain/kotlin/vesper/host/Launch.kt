package vesper.host

/**
 * Variables the shell gets on top of the app's own environment. An app started from Finder or
 * the Dock inherits no locale, and without one the shell and most programs fall back to ASCII.
 */
public fun launchEnvironment(inherited: (String) -> String?): Map<String, String> =
    buildMap {
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("TERM_PROGRAM", "vesper")
        if (listOf("LC_ALL", "LC_CTYPE", "LANG").all { inherited(it).isNullOrEmpty() }) put("LANG", "en_US.UTF-8")
    }
