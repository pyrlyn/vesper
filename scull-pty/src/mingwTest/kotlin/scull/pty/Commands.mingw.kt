package scull.pty

internal actual fun echoAndExit(): SpawnOptions = SpawnOptions.command("cmd.exe", "/C", "echo hi& exit 3")

// ping waits a second between echo requests; it never reads its input.
internal actual fun runForMinutes(): SpawnOptions = SpawnOptions.command("ping", "-n", "600", "127.0.0.1")

// A counted infinite `for /L` with a zero step is not reliable across cmd versions. A goto
// loop keeps writing until the test closes the console.
internal actual fun flood(): SpawnOptions =
    SpawnOptions.command("cmd.exe", "/C", "echo off & :loop & echo flood flood flood flood flood flood flood & goto loop")
