---
status: in progress
priority: P2
complexity: 2
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T24. Reconcile harness eager-wrap with xterm's deferred DECAWM

The conformance harness must match xterm's deferred wrap (DECAWM): a printable character at the last column does not wrap until the next printable arrives. Today-style eager wrap will fail golden tests against xterm. Keep `scull-term` behaviour documented and fix the harness (or the core, if the tests prove the core is wrong).

## Findings

- T3 ported the fix of the Rust tree (b22c427): `Stub` arms a pending wrap on the last column, `\r` and `\n` clear it, and `tests/harness/wrap.txt` records `abcd\rX\nabcdY` on 4 by 3 (`Xbcd`, `abcd`, `Y`). `StubTest.xtermDecawmDefersWrapUntilTheNextPrintable` checks it.
- `scull-term` (T7) defers the wrap the same way (`Print.kt`, `tests/fixtures/wrap.txt`), but no test yet checks a harness golden frame against the real core, which is the proof this task asks for.
- The stub's `\n` also returns to column 0, as text arrives through a tty with ONLCR. The core does that under LNM (ANSI mode 20).
- `controls.txt` is a stub-only frame: the stub ignores ESC and prints the byte after it, while the core reads ESC Y as an escape sequence.

## Execution plan

1. `scull-harness/src/commonMain/kotlin/scull/harness/Fixtures.kt`: `GoldenFrame` and `readGolden`, the header and row parsing that `Golden.kt` does today, strict about the frame's shape. `Golden.kt` uses it.
2. `scull-harness` tests: a malformed frame is refused.
3. `scull-term/src/commonTest`: the `wrap.txt` stream fed to a `Terminal` under LNM draws the golden frame.
4. Verify: the core test fails against an eager-wrap frame; `./gradlew check` on macOS; the linuxX64 test binaries of `scull-harness` and `scull-term` in a Colima container.

## Acceptance

- [x] Golden fixtures agree with xterm's deferred DECAWM, with tests in `scull-harness`.
- [x] `./gradlew check` passes.
