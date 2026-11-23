package scull.pty

internal actual fun echoAndExit(): SpawnOptions = SpawnOptions.command("cmd.exe", "/C", "echo hi& exit 3")

// ping waits a second between echo requests; it never reads its input.
internal actual fun runForMinutes(): SpawnOptions = SpawnOptions.command("ping", "-n", "600", "127.0.0.1")

internal actual fun flood(): SpawnOptions = SpawnOptions.command("cmd.exe", "/C", "for /L %i in (1,0,2) do @echo flood flood flood")
