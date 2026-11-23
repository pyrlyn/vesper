---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T13. Links and shell integration in the core

Shell integration in `scull-term`: OSC 8 links as per-row ranges, OSC 52 as an event the UI may deny or answer, OSC 7 and OSC 133, OSC 9 and OSC 777 notifications. Done when each has a golden case in `tests/fixtures/shell.txt` and an event in the queue.

Selection and search are T13.2, events over the C ABI are T13.3, and the macOS selection UI is T13.4.

## Findings

- **The grid already keeps links.**
  - `Row.setLink`, `clearLinks`, `linkAt` and `links` store OSC 8 ids as sorted per-row spans.
  - Reflow and resize carry the spans along.
  - `Marks(size)` exists for a terminal-side table to sweep.
- **The events and the fixture exist.**
  - `scull-term/Events.kt` has the capped queue, the coalesced bell and OSC 0/1/2.
  - `tests/fixtures/shell.txt` already holds the cases, written for the Rust core.
  - `ConformanceTest` skips the file until this task.
- **Nothing else to reuse.**
  - No OSC 7/8/52/133 handler exists under apps, packages or tools.
  - Base64 comes from the Kotlin standard library (`kotlin.io.encoding.Base64`).
- **Moved here from T13.3.**
  - The notifications (OSC 9 and 777) and the link-id sweep belong to the core.
  - T13.3 keeps the C ABI.

## Execution plan

1. **OSC dispatch (`Events.kt`).**
   - Read the whole numeric code, not one digit.
   - New events:
     - `Directory` (OSC 7);
     - `ShellMark` (OSC 133 A to D, with D's exit code);
     - `ClipboardWrite` and `ClipboardRead` (OSC 52, base64 decoded, broken data dropped whole);
     - `Notification` (OSC 9 without ConEmu's numeric subcommands, OSC 777 `notify`);
     - `Link` (a new OSC 8 id and its URI).
   - Caps on text and on clipboard bytes, and a byte budget for the queue.
2. **OSC 52 answer.**
   - `Terminal.answerClipboard(targets, bytes)` queues the reply.
   - `denyClipboard(targets)` answers with empty data, so a program waiting for it does not hang.
3. **OSC 8 links (`Links.kt`).**
   - A table of at most 1024 ids keyed by the `id=` parameter and the URI. Printed cells get the open link's span; a print without one clears it.
   - When the table is full, a sweep marks the ids that any row of either screen and the open link still hold, and frees the rest.
   - `Terminal.linkUri(id)` reads a URI back. RIS closes the open link.
4. **Verify.**
   - `shell.txt` joins `ConformanceTest`, with `links:` blocks and the event shapes from `tests/README.md`.
   - Unit tests cover:
     - the clipboard answer and denial;
     - caps and the queue budget;
     - the link sweep past 1024 targets;
     - links across scroll, erase and overwrite.
   - `./gradlew ktlintFormat check`, and Linux in Colima.

## Acceptance

- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
