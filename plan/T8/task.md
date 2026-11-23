---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T8. PTY and I/O thread

`vesper-pty`: a child on a native pseudo-terminal (ConPTY on Windows), threads that move its output into a caller-supplied sink in bounded holds of a shared lock, back-pressure so a flooding child cannot starve a frame read, replies written back through a capped queue, resize, and the child's exit delivered after its last output. Done when a flooding child cannot starve a frame read and a shell runs on both platforms.

## Findings

- The design is the Rust PTY layer at 69d9be4 (its `pty` crate), ported: a read thread, an I/O loop thread, a write thread and a wait thread per terminal; bounded queues between them are the back-pressure; at most `MAX_LOCKED_BYTES` per lock hold; replies go through the write queue; the exit is ordered after the output (`EXIT_IDLE`, `EXIT_DRAIN_MAX`); pause and resume for interactive resize; a shutdown grace after which stuck threads are left detached.
- Binding choice (research.md §6 named `portable-pty`, which is Rust):
  - `org.jetbrains.pty4j:pty4j` is JVM-only (JNA; repository language Java, last push 2026-10-02, https://github.com/JetBrains/pty4j, checked 2026-11-23). Not usable from Kotlin/Native.
  - `io.github.ll11l1lillil1lll.kpty:kpty` 0.0.1 (one release, 2026-01-09, https://klibs.io/package/io.github.ll11l1lillil1lll.kpty/kpty, checked 2026-11-23) calls `forkpty()` from Kotlin. After `fork` in a multi-threaded process only async-signal-safe calls are allowed until `exec` (POSIX `fork`, https://pubs.opengroup.org/onlinepubs/9799919799/functions/fork.html); a Kotlin/Native child would run runtime code (allocation, GC safepoints) there. It also has no bounded I/O or exit ordering. Maintenance **unverified**.
  - Chosen: a small C shim in cinterop `.def` files. The fork-to-exec path is C with only async-signal-safe calls; Kotlin owns everything else. POSIX: `posix_openpt`, `grantpt`, `unlockpt`, `TIOCSWINSZ`, `setsid` + `TIOCSCTTY`, an exec-error pipe with `O_CLOEXEC`, `waitid(WNOWAIT)` before reaping so a hangup never reaches a reused pid. Windows: `CreatePseudoConsole` + `CreateProcessW` with `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE` (https://learn.microsoft.com/en-us/windows/console/creating-a-pseudoconsole-session, checked 2026-11-23).
- The lock is a FIFO ticket `FairLock` rather than a mutex: the I/O thread releases and retakes it every hold, and a plain mutex lets it barge ahead of a waiting frame read (the Rust code used `parking_lot`'s `unlock_fair` for the same reason).
- Windows: there is no Windows machine or wine here. The mingwX64 code and tests are compiled, not run; that is stated in done.md.

## Execution plan

1. Common code in `nativeMain`: limits, the `Sink` interface, errors, spawn options, a C-backed monitor and detached threads (`sys.def`), `FairLock`, a byte ring, the shared state, the I/O loop and the worker loops, the public `Pty`.
2. `posixMain`: `posix.def` and `PosixChild`. `mingwMain`: `conpty.def` and `ConptyChild`.
3. Tests ported from the Rust crate: shared-state queues and ordering, I/O loop holds and replies, workers, `FairLock`, `ByteRing`, a shell (`echo` and exit status, missing program, close, resize reaching `stty size`, replies, input cap) and the starvation test with a flooding `yes`.
4. Verify: `./gradlew ktlintFormat check` on macOS; the linuxX64 test binary in a Colima container; `compileTestKotlinMingwX64` and the mingw test link.

## Acceptance

- [x] The PTY layer above is implemented in Kotlin/Native with C only for the fork-to-exec path and OS calls.
- [x] A flooding child cannot starve a concurrent frame read (starvation test on macOS and Linux).
- [x] A shell runs, echoes and exits with its status on macOS and Linux; the Windows path compiles.
- [x] `./gradlew check` passes.
