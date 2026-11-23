# T13.3. Events over the C ABI

Status: not started

`tt_term_poll_event` kinds for title, directory, shell mark, clipboard read and write, link and notification, with their text copied out by a sized call; exports to answer or deny a clipboard read and to resolve a link id. The core events (notifications included) and the link-id sweep are T13; the macOS part moved to T14. Verify: Kotlin/Native and C tests, `./gradlew check`, the C ABI sanitizer test.

Plan: `scull-ffi` events: append-only `TT_EVENT_*` kinds over the `tt_event`, `tt_term_poll_event` and `tt_term_event_text` that T10 shipped (a notification's title as a second text part); `tt_term_clipboard_reply` / `_deny` hand the answer to the child; `tt_term_link_uri`. The frame carries each row's link spans, which `Frame` does not copy yet. `EventExports.kt` drops the `else -> continue` that T13 left so a C host is not shown kinds it has no numbers for. ABI minor bump, a new `tests/c/events.c` under ASan/UBSan and TSan.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
