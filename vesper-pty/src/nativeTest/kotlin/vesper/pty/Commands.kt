package vesper.pty

/** Prints `hi` and exits with status 3. */
internal expect fun echoAndExit(): SpawnOptions

/** Runs for minutes without reading its input, unless it is ended. */
internal expect fun runForMinutes(): SpawnOptions

/** Prints output as fast as it can, forever. */
internal expect fun flood(): SpawnOptions
