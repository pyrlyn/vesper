# T8. PTY and I/O thread

Status: not started

`scull-pty`: a reader thread per terminal on top of a native PTY binding (cinterop), bounded work per lock hold, `try_lock` with back-pressure, replies written from the same thread, child exit through the same loop. ConPTY on Windows. Done when a flooding child cannot starve a frame read and a shell runs on both platforms.

Execution plan:
1. Re-check POSIX PTY and ConPTY bindings (research.md §6) against maintained alternatives; pick one and record why.
2. `scull-pty`: spawn a child on a PTY (ConPTY on Windows), a reader thread per terminal that hands bounded chunks to a caller-supplied sink, `try_lock`-style back-pressure through a trait so the crate stays independent of scull-term, replies written from the same thread through a capped queue, resize, and child exit delivered through the same loop.
3. Tests: a flooding child (`yes`) cannot starve a concurrent reader of the shared state; a shell runs `echo` and exits with its status; resize reaches the child; on Windows the same tests through ConPTY.
4. Verify with `./gradlew check`.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
