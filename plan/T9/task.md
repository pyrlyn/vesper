---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T9. Damage, synchronized output, frame snapshot

Row damage and scroll damage; mode 2026 with byte and time caps; a UI-owned frame that copies only changed rows into flat buffers under a short lock, with text runs for platform shaping. Done when the property test holds: repainting dirty rows over the previous frame equals a full repaint.

## Findings

- The design is the Rust `vesper-term` T9 at 69d9be4 (commits b8fee66, 11d3ae3, 4308c21, f539f4c: `sync.rs`, `damage.rs`, `frame.rs`), ported.
- No grid change is needed: every `vesper-grid` `Row` already has a stable `RowId` and a `generation` bumped by each visible change. Two pictures' `(id, generation)` stamps give the damage: a row whose id shows up elsewhere with the same generation moved unchanged (scroll damage), any other changed row is dirty. Matching on ids makes region scrolls, full-screen scrolls and viewport scrolling one case.
- Mode 2026 holds only the frame; the bytes reach the grid at once (foot, Contour). The caps are Alacritty's: 2 MiB and 150 ms; past either the hold ends and DECRQM reads the mode as reset. The clock is passed in, so tests are deterministic.
- Row ids are unique per grid only, so a screen switch repaints everything.
- Frame text offsets are in UTF-16 units of the row's `String` (Kotlin); the C ABI (T10) decides its own encoding.

## Execution plan

1. T9.1 in `vesper-term`: `Sync.kt` (`SyncGate`, `MAX_SYNC_BYTES`, `MAX_SYNC_HOLD`), `Damage.kt` (`RowStamp`, `Scroll`, `Damage.compute`), and `Terminal.syncHeld(now)`, `syncDeadline()`, `scrollDisplay(delta)`.
2. T9.2 `Frame.kt`: flat cell buffers (code point, style id, width, flags), per-row text and runs split on style, width and blank cells, a style table refreshed for the rows copied, the cursor, and `Terminal.updateFrame(frame, now)` that applies scroll damage inside the frame and copies only dirty rows.
3. Tests: unit tests per file ported from Rust; a seeded property test (random streams, whole sequences, viewport scrolls, pauses past the hold cap) that repaints a model canvas from the damage alone and compares it with a fresh full frame after every step. The random stream generator is shared with `RandomStreamTest`, not copied.
4. Verify: `./gradlew ktlintFormat check`; the linuxX64 test binary of `vesper-term` in a Colima container; a mutation of the scroll replay must fail the property test.

## Acceptance

- [x] Damage and synchronized output are implemented in `vesper-term`.
- [x] The frame snapshot copies only changed rows and moves scrolled ones.
- [x] The property test holds: repainting the damage over the last picture equals a full repaint.
- [x] `./gradlew check` passes.
