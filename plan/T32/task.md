---
status: not started
priority: P3
complexity: 3
readiness: 0%
---

# T32. Desktop notifications from OSC 9 and OSC 777

Status: not started

The core reports `TT_EVENT_NOTIFICATION` with a body and a title; both shells drop it. Done when a notification a program asks for shows as a system notification on macOS and Windows while the terminal's window is not the active one, is rate-limited so a program cannot flood the desktop, and the policy (when to show, the limit, the text cleaned of control characters and cut to a sane length) is pure code with tests on each platform.

Execution plan. One rule on both platforms: a notification shows only while the terminal's window is not the active (key) window, whichever pane or tab asked; a window the user is looking at needs no pop-up. The text is the core's UTF-8 as is; the policy cleans it. Pure policy, same numbers on both sides: control characters (C0, C1, DEL) become a space for tab and newlines and are dropped otherwise, whitespace is trimmed, a title is cut to 64 and a body to 240 characters (whole characters, never half of a pair or cluster) with an ellipsis, an empty body shows nothing (a title alone is not a notification), the title falls back to the tab or window title the program set, then to "Scull". Rate limit per pane: a burst of 3, then one more every 5 seconds, the extras dropped.
(1) Windows, `Scull.Core/Notifications.cs`: `NotificationLimiter` (token bucket over an injected clock), `Notice` and `NotificationPolicy.Clean`; `PaneEvents.Drain` takes `EventKind.Notification` events through the pane's limiter into `DrainedEvents.Notices`; tests in `NotificationTests` (cleaning, cutting incl. surrogate pairs and CJK, empty body, limiter burst and refill, a real `Terminal` fed OSC 9 and OSC 777). (2) Windows, `Scull.App/DesktopNotifier.cs`: `Microsoft.Windows.AppNotifications` (in the Windows App SDK already used): `Register()` once, a failure or `IsSupported() == false` is warned and the notifier stays off, never fatal; `TerminalWindow` shows a notice when `active` is false, with the title fallback; a click brings the window forward. (3) macOS, `ScullKit/NotificationPolicy.swift`: the same policy and limiter, a `NotificationPoster` protocol, `UserNotificationPoster` over `UNUserNotificationCenter` (authorization asked on the first notice, quiet when refused, and quiet when the process is not an app bundle, as under `swift test`); `TerminalSession.drainEvents` collects the notifications, `TerminalView` posts them when its window is not key; `NotificationTests.swift` with a fake poster. Verify: `./gradlew check`, `swift test` in `macos/`, `dotnet test windows/Scull.slnx`, CI incl. `dotnet build` of the WinUI app, which also builds merged main's `Scull.App`. Real desktop notifications on either system need a human; the Windows toast for an unpackaged app is the least certain part (registration needs the app's identity from its executable).

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
