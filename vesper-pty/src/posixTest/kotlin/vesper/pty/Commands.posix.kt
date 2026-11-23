package vesper.pty

internal actual fun echoAndExit(): SpawnOptions = SpawnOptions.command("/bin/sh", "-c", "echo hi; exit 3")

internal actual fun runForMinutes(): SpawnOptions = SpawnOptions.command("sleep", "600")

internal actual fun flood(): SpawnOptions = SpawnOptions.command("yes", "flood flood flood flood flood flood flood")
