# T12. Done

Closed through its two subtasks:

- [T12.1](../T12.1/done.md): key encoding. It covers legacy xterm, the kitty keyboard protocol with a flag stack per screen, and win32-input-mode.
- [T12.2](../T12.2/done.md): mouse in every tracking mode and encoding (SGR-pixel included), bracketed paste with marker stripping, and focus reports.

Both are wired into `scull-term`, checked by table-driven tests taken from xterm's ctlseqs and the kitty specification, and covered by seeded property tests that no encoder throws. `./gradlew check` passes on macOS, and the tests pass on Linux in Colima.
