# T13. Done

Links and shell integration in the core (`vesper-term`), stacked on T10.

## What was done

- `OscDispatch.kt` reads the whole numeric OSC code and turns OSC 7, 9, 52, 133 and 777 into events.
  - The events are `Directory`, `ShellMark`, `ClipboardWrite`, `ClipboardRead` and `Notification`.
  - ConEmu's numbered OSC 9 subcommands and other OSC 777 commands are ignored.
  - Base64 that does not decode drops the whole OSC 52 write.
- OSC 52 answers go through `Terminal.answerClipboard(targets, data)` and `denyClipboard(targets)`.
  - The answer ends the way the read did (BEL or ST).
  - A host answer may pass the 4096-byte reply cap; PTY-driven replies may not.
- `Links.kt` numbers OSC 8 links and frees the numbers no cell uses.
  - At most 1024 ids, keyed by the `id=` parameter and the URI. A new id raises `Event.Link`.
  - Printed cells take the open link's span; cells printed with no link open lose theirs.
  - A full table sweeps with `Marks` over every row of both screens plus the open link.
  - A sweep that frees little holds off the next sweep for 64 refused opens.
  - `Terminal.linkUri(id)` reads a URI back. RIS closes the open link.
- Caps:
  - 1024 bytes for titles and notifications;
  - 4096 bytes for URIs;
  - 1 MiB of clipboard data;
  - a 2 MiB byte budget on the event queue, on top of the 32-event cap.
- `vesper-ffi` skips the new events until T13.3 gives them ABI kinds. T13.3 now also covers the link spans in the frame.
- Reused: the grid's link spans and `Marks`, and `kotlin.io.encoding.Base64`. No other OSC handler existed under apps, packages or tools.

## How it was checked

- `tests/fixtures/shell.txt` now runs in `ConformanceTest` with `links:` blocks, and every case passes.
- `OscTest` (10 tests) covers:
  - the clipboard answer, the denial and the target checks;
  - the caps and the queue's byte budget;
  - link keys;
  - links across scroll, erase, overwrite and RIS;
  - the sweep past 1024 ids, and a table full of live ids.
- `./gradlew ktlintFormat check` is green on macOS: 384 tests, 0 failures, and the C sanitizer runs pass.
- On Linux in Colima, `vesper-term` (98 tests) and `vesper-ffi` (29 tests) pass.
