# T9. Done

## What was done

`scull-term` now holds the picture during synchronized output, finds the damage between two pictures and copies the viewport into a UI-owned frame, ported from the Rust `scull-term` T9 at 69d9be4.

- `Sync.kt`: `SyncGate` with Alacritty's caps, `MAX_SYNC_BYTES` (2 MiB) and `MAX_SYNC_HOLD` (150 ms). `Terminal.syncHeld(now)` and `syncDeadline` tell the I/O thread whether to wake the UI and how long it may sleep; past either cap the hold ends and DECRQM reports mode 2026 as reset. A full reset ends the hold too.
- `Damage.kt`: `RowStamp(id, generation)`, `Scroll(start, end, from)` and `Damage.compute(old, new, full)`. A row found unchanged elsewhere joins a coalesced scroll; any other changed row is dirty. A first picture, a resize or a screen switch is full damage. `Terminal.scrollDisplay(delta)` moves the viewport, and viewport scrolls come out as scroll damage like region scrolls.
- `Frame.kt`: `Frame` keeps flat cell buffers (code point, style id, width, flags), per-row text with `TextRun`s split on style, width and blank cells, the styles its cells use and the cursor (null when hidden or scrolled off). `Terminal.updateFrame(frame, now)` returns false and clears the damage while the picture is held; otherwise it moves the scrolled rows inside the frame, every scroll reading the previous picture, and copies only the dirty rows from the grid.
- `scull-unicode` gains `String.firstCodePoint()`, reusing its surrogate decoding, for a cluster cell's code point.
- The random stream generator moved from `RandomStreamTest` to the test `Support.kt`, shared by both property tests.

## Commits

On branch `t9-frame`:

- 84d9776 synchronized output with byte and time caps
- 21ecf4a row and scroll damage from row stamps
- 9a1f23f UI-owned frame snapshot with text runs
- 892f641 dirty-row repaint equals a full repaint

Each commit compiles and passes ktlint on its own.

## How it was checked

- `./gradlew ktlintFormat check` on macOS: BUILD SUCCESSFUL, 270 tests, 0 failures; 80 of them in `scull-term`.
- The `scull-term` linuxX64 test binary in Colima (ubuntu:24.04, emulated amd64): 80 tests, 0 failures.
- `FrameRepaintTest`: 400 seeded cases of up to 23 steps on screens up to 15 × 6, with random streams, whole sequences and viewport scrolls, and pauses of up to 199 ms. After every step the canvas repainted from the damage equals a fresh frame, the cursor and stamps match, every cell's style matches the grid, and a held picture never changes. It ran in 1.4 s on macOS and 16.8 s in the emulated Linux container.
- Unit tests ported from Rust: the sync gate, terminal holds (ESU, time cap with the DECRQM reply, byte cap exactly at and past the cap, fresh caps for a new hold, full reset), damage on stamps and on a terminal (linefeed, region scroll, printing, viewport scroll), and the frame (runs, erased cells, wide cells, clusters, full updates, scrolls, holds, cursor).
- Mutation checks, restored afterwards:
  - Replaying only the first scroll fails `FrameRepaintTest` and `everyScrollOfOneUpdateMovesItsRows`.
  - Writing each scroll before reading the next one's source fails `scrollsReadThePreviousPictureNotEachOther`. The random test missed this mutant, since two downward scrolls in one update are rare, so that deterministic test was added for it.
- Windows: CI builds and runs the mingwX64 tests; it has not been run locally.
