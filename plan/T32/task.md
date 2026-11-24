---
status: todo
priority: P3
complexity: 3
readiness: 0%
agent:
---

# T32. Desktop notifications from OSC 9 and OSC 777

The core reports a notification event with a body and a title; the hosts drop it. Done when a notification a program asks for shows as a system notification on macOS (`UNUserNotificationCenter`) and Windows (app notifications) while the terminal's window is not the active one.

One pure policy for both hosts, in common Kotlin: control characters become a space for tab and newlines and are dropped otherwise, whitespace is trimmed, a title is cut to 64 and a body to 240 characters (whole characters, never half a pair or cluster) with an ellipsis, an empty body shows nothing, the title falls back to the tab or window title, then to "Vesper". Rate limit per pane: a burst of 3, then one more every 5 seconds. A notifier that fails to register is warned about and stays off, never fatal.

Check: `./gradlew check` with tests for cleaning, cutting (surrogate pairs, CJK), the empty body, the limiter and a real terminal fed OSC 9 and OSC 777; real desktop notifications need a human.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
