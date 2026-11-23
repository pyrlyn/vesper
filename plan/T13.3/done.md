# T13.3. Done

The T13 events, clipboard answers and link ids over the C ABI, stacked on T13.

## What was done

- `scull.h` is ABI 0.2. Everything new is appended, so a 0.1 host still links and runs.
  - Event kinds `TT_EVENT_DIRECTORY`, `_SHELL_MARK`, `_CLIPBOARD_WRITE`, `_CLIPBOARD_READ`, `_LINK`, `_NOTIFICATION`.
  - `TT_SHELL_*` in `tt_event.detail`. `exit_code` is D's exit code, or `TT_EXIT_CODE_UNKNOWN` when D has none.
  - `tt_event.id` is the link id. `TT_EVENT_TEXT_TITLE` is the second text: a notification's title, or the clipboard targets.
  - `tt_link_span` and `tt_row.links` / `links_len` list each row's linked cells.
- New calls:
  - `tt_term_link_uri` resolves a link id; `TT_EMPTY` once the core freed it.
  - `tt_term_clipboard_reply` and `tt_term_clipboard_deny` answer a clipboard read.
- Clipboard answers go straight to the child's input, whole or not at all.
  - Replies queued in the terminal leave only when the child writes, and a child waiting for its answer writes nothing.
  - `Terminal.clipboardReply` builds the bytes without queueing them.
  - `TT_FULL`: no room now, try again. `TT_CLOSED`: no child.
  - `TT_INVALID`: bad or non-UTF-8 targets, data past the 1 MiB cap (refused before it is read), or an answer larger than the 256 KiB input queue.
- `EventExports.kt` maps every event kind explicitly; the T13 `else -> continue` skip is gone.
- `FrameRow.links` carries the row's link spans; `FrameExports.kt` copies them into reused native buffers.
- `tests/c/two_threads.c` now allows coalesced bells. Under the full gate the feeder outran the UI thread and two bells became one, as the core documents.

## Commits

- `9d2f08d` core: clipboard answer bytes and frame link spans.
- `36e4a9d` ABI 0.2, the exports and their tests.
- `6d02a8d` the two-thread C test and coalesced bells.

## How it was checked

- `ShellEventsTest` (10 tests): every new kind with its detail, id, exit code and both text parts; link URIs (`OK`, `FULL`, `EMPTY`, `INVALID`); frame link spans; clipboard `TT_CLOSED` and `TT_INVALID`.
  - A spawned `/bin/sh` that reads its answer without writing first receives both the answer and the denial.
- `tests/c/events.c` does the same through the header's prototypes under ASan+UBSan and TSan.
- `./gradlew ktlintFormat check` passes on macOS arm64.
- Linux x86_64 in Colima: `vesper-term` 98 and `scull-ffi` 39 tests pass.
