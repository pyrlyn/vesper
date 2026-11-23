# T8. Done

## What was done

`scull-pty` runs a child on a pseudo-terminal (ConPTY on Windows) behind four threads per terminal, ported from the Rust `scull-pty` at 69d9be4:

- `scull-pty-read` reads output into a queue capped at `MAX_PENDING_READ_BYTES` (1 MiB); a full queue blocks the reader, the kernel buffer fills and the child blocks in `write`.
- `scull-pty-io` takes the caller's `FairLock`, feeds the `Sink` at most `MAX_LOCKED_BYTES` (64 KiB) per hold, moves the sink's replies into the write queue (capped at 256 KiB, clamped whatever the sink returns), releases the lock and then calls the wakeup.
- `scull-pty-write` writes replies and input in 4 KiB chunks; a failed write drops the queue and refuses more input.
- `scull-pty-wait` waits for the child without reaping it (`waitid(WNOWAIT)`), so a hangup is never sent to a reused pid; the exit reaches the sink after the output (EOF, `EXIT_IDLE` of quiet, or `EXIT_DRAIN_MAX`).
- `Pty`: `writeInput` (partial) and `writeInputWhole` (all-or-nothing), `resize`, `pauseOutput`/`resumeOutput`, `kill`, `close` (hangup, then up to `SHUTDOWN_GRACE` for the threads; a stuck one is left detached and never calls the sink again).
- `FairLock`: a FIFO ticket lock, so the I/O thread that just released the lock cannot take it again ahead of a waiting frame read.
- C only where Kotlin cannot be: `sys.def` (a mutex with monotonic timed condition waits, detached named threads), `posix.def` (open, fork and exec with async-signal-safe calls only, `setsid` + `TIOCSCTTY`, exec errors through a close-on-exec pipe), `conpty.def` (`CreatePseudoConsole`, `CreateProcessW`, `ClosePseudoConsole` on its own thread).
- `gradle.properties`: `kotlin.mpp.enableCInteropCommonization=true`, so the shared `nativeMain` and `posixMain` source sets resolve the shims.
- research.md §6 and toolchain.md record the binding choice; no new dependency.

## Commits

- 3e51412 T8: bounded queues, fair lock and I/O loop for the PTY threads
- 97c42ea T8: spawn children on a Unix PTY and on ConPTY
- 5edecf4 T8: record the PTY binding choice

## How it was checked

- `./gradlew ktlintFormat check` on macOS: BUILD SUCCESSFUL, 240 tests, 0 failures; 46 of them in `scull-pty`.
- linuxX64 test binary of `scull-pty` in a Colima container (`ubuntu:24.04`, `linux/amd64`): 46 tests passed.
- `:scull-pty:linkDebugTestMingwX64` links `test.exe`. **Not run**: there is no Windows machine or wine here. CI runs `./gradlew check` on `windows-2025`, which runs these tests through ConPTY.
- Starvation test (`yes` floods while the test thread reads a "frame" under the same lock every 2 ms, at least 2 s and 100 frames):

  | Host | Frames | Bytes fed | Worst frame wait | Worst hold |
  | --- | --- | --- | --- | --- |
  | macOS arm64 | 813 | 227,141,930 | 247 µs | 22,528 B |
  | Linux x64 (Colima, emulated amd64) | 875 | 143,458,318 | 471 µs | 49,808 B |

  The limit is 50 ms per frame read and 64 KiB per hold.
- Shell tests: `echo hi; exit 3` gives output then status 3 and nothing after the exit; a missing program fails to spawn; `close` ends `sleep 600` well within 4 × `SHUTDOWN_GRACE` with no exit event; `stty size` sees the initial size and a resize (31 101); a reply reaches a child waiting in `read`; environment and working directory reach the child; a signal is reported; ^C interrupts the child; input is cut to the write queue.
- Mutation checks (restored afterwards):
  - `takeBatch` without the budget: `IoLoopTest.everyHoldStaysWithinTheBudgetAndTheExitComesLast` (a hold fed 655,380 bytes) and `SharedTest.aBatchStaysWithinTheBudgetAndNeverSplitsOrReordersAChunk` fail.
  - Without `TIOCSCTTY`: `PosixPtyTest.controlCInterruptsTheChild` fails on macOS and Linux; `aChildIsTheLeaderOfItsOwnSessionOnThePty` fails on Linux only (macOS still opens `/dev/tty`).
