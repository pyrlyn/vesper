---
status: in progress
priority: P1
complexity: 3
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T13.3. Events over the C ABI

The shell-integration events T13 added to the core reach a C host:

- `tt_term_poll_event` kinds for:
  - directory and shell mark;
  - clipboard write and read;
  - link and notification.
- Their text comes through `tt_term_event_text`, a notification's title as a second part.
- New calls answer or deny a clipboard read and resolve a link id.
- Each frame row lists its link spans.

The core events (notifications included) and the link-id sweep are T13; the macOS part moved to T14.

## Findings

- **What exists to build on.**
  - `tt_event` already reserves `id` and `title_len` for these kinds (T10).
  - `EventExports.kt` skips the new events with `else -> continue`.
  - `Frame` copies no link spans yet.
- **Answers must not wait for the child.**
  - Replies queued in the terminal reach the PTY only when the child writes again.
  - A program waiting for its OSC 52 answer writes nothing, so it would never get one.
  - So an answer goes straight to the PTY input queue, whole or not at all (`writeInputWhole`).
  - That queue holds 256 KiB, so a larger answer can never be sent; the host denies instead.

## Execution plan

1. **Core: `Terminal.clipboardReply(targets, data)`.**
   - It builds the answer bytes without queueing them; `answerClipboard` uses it.
   - `FrameRow.links` copies the row's link spans when the row is painted.
2. **`scull.h`, ABI 0.2.** The new constants, all appended:
   - `TT_EVENT_DIRECTORY`, `_SHELL_MARK`, `_CLIPBOARD_WRITE`, `_CLIPBOARD_READ`, `_LINK`, `_NOTIFICATION`;
   - `TT_SHELL_*`;
   - `TT_EVENT_TEXT_TITLE`, the second part (a notification's title, or the clipboard targets).
3. **`scull.h`, fields and calls.**
   - `tt_event.id` is the link id. `exit_code` is D's exit code, or `TT_EXIT_CODE_UNKNOWN` when D carries none.
   - `tt_link_span` and `tt_row.links` / `links_len`.
   - `tt_term_clipboard_reply`, `tt_term_clipboard_deny` and `tt_term_link_uri`.
4. **Exports** in `EventExports.kt` and `FrameExports.kt`; the skip goes.
5. **Verify.**
   - Kotlin/Native tests: every kind and its text parts, reply/deny on a spawned child that reads the answer, `TT_CLOSED` without a child, `TT_FULL`/`TT_INVALID`, link URIs, and frame link spans.
   - `tests/c/events.c` under ASan+UBSan and TSan.
   - `./gradlew ktlintFormat check`, and Linux in Colima.

## Acceptance

- [x] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [x] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
