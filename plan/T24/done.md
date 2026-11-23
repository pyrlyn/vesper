# T24. Reconcile harness eager-wrap with xterm's deferred DECAWM: done

## What was done

- The stub already deferred the wrap: T3 ported the fix of the Rust tree (b22c427), and `tests/harness/wrap.txt` records the deferred grid (`Xbcd`, `abcd`, `Y` for `abcd\rX\nabcdY` on 4 by 3). `scull-term` defers the wrap the same way, so neither the harness nor the core needed a change in behaviour.
- New proof against the real core: `scull-term` `HarnessGoldenTest.theStubWrapFrameIsWhatTheTerminalDraws` feeds the stub's stream to a `Terminal` and expects the golden frame. LNM is set first because the stub's line feed also returns to column 0, as text arrives through a tty with ONLCR.
- `scull-harness` main has `GoldenFrame` and `readGolden`, the frame parsing `Golden.kt` did in the harness tests, now shared by both checks. It refuses a frame of the wrong shape (header, row count, row width, final newline); `StubTest.aGoldenFrameOfTheWrongShapeIsRefused` covers it.
- `controls.txt` stays a stub-only frame: the stub prints the byte after an ESC, the core reads both as one escape sequence. The comment in `HarnessGoldenTest` says so.

## How it was checked

- With `wrap.txt` rewritten to the eager-wrap frame (`abcd`, `X`, `abcd`), both `StubTest.xtermDecawmDefersWrapUntilTheNextPrintable` and the new core test fail, each reporting the deferred grid; the fixture was restored afterwards.
- `./gradlew check` on macOS arm64: ktlint and all tests pass (`scull-harness` 7, `scull-term` 50).
- linuxX64: the `scull-harness` and `scull-term` test binaries pass in an Ubuntu 24.04 container (amd64 on Colima); every module compiles for mingwX64.
