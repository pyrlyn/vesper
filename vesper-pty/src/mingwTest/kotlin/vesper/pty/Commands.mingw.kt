package vesper.pty

internal actual fun echoAndExit(): SpawnOptions = SpawnOptions.command("cmd.exe", "/C", "echo hi& exit 3")

// ping waits a second between echo requests; it never reads its input.
internal actual fun runForMinutes(): SpawnOptions = SpawnOptions.command("ping", "-n", "600", "127.0.0.1")

// `goto` labels do not run in a `cmd /C` one-liner the way they do in a .bat file. A huge
// `for /L` count keeps writing until the test closes the console.
internal actual fun flood(): SpawnOptions =
    SpawnOptions.command(
        "cmd.exe",
        "/C",
        "for /L %i in (1,1,2000000000) do @echo flood flood flood flood flood flood flood",
    )
