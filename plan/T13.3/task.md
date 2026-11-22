# T13.3. Events over the C ABI

Status: not started

OSC 9 and OSC 777 notifications into the queue; `tt_term_poll_event` kinds for title, directory, shell mark, clipboard read and write, link and notification, with their text copied out by a sized call; exports to answer or deny a clipboard read and to resolve a link id; link ids reclaimed by a sweep of ids no row holds, so OSC 8 keeps working past 1024 targets. macOS `TerminalSession` sets the window title and writes OSC 52 writes to the pasteboard; reads are denied until a setting allows them. Verify: Kotlin/Native and C tests, `./gradlew check`, the C ABI sanitizer test, `swift test` in `macos/`.

Plan: (a) the OSC handler: OSC 9 text (ConEmu `9;N` subcommands ignored) and OSC 777 `notify;title;body` become `Event::Notification`; golden cases in `shell.txt`. (b) `Links` keeps freed slots: when no id is free and enough opens passed since the last sweep, the ids held by any row of either screen (and the open link) are marked with `scull_grid::Marks` and the rest freed. (c) `scull-ffi` events: append-only `TT_EVENT_*` kinds, `tt_event` gains `serial`, `id`, `detail`, `text_len`, `title_len`; the last polled event's text stays in the handle and `tt_term_event_text(serial, part, ...)` copies it with the `tt_term_read_text` sized pattern (one shared helper); `tt_term_clipboard_reply` / `_deny` hand the answer to the child; `tt_term_link_uri`. ABI minor 6, the bindings generator, a new `tests/c/events.c` under ASan/UBSan and TSan. (d) macOS: `TerminalSession` keeps title and working directory, writes OSC 52 to a pasteboard (`NSPasteboard.general` by default), denies reads; `TerminalView` puts the title on its window; Swift tests on a named pasteboard.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
