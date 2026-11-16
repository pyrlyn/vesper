package app.vesper.platform

enum class HostOs {
    MacOS,
    Windows,
    Linux;

    /** Label for the primary shortcut modifier: ⌘ on macOS, Ctrl elsewhere. */
    val mod: String get() = if (this == MacOS) "⌘" else "Ctrl+"

    val shift: String get() = if (this == MacOS) "⇧" else "Shift+"

    val alt: String get() = if (this == MacOS) "⌥" else "Alt+"

    companion object {
        val current: HostOs by lazy { detectHostOs() }
    }
}

expect fun detectHostOs(): HostOs
