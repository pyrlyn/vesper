---
status: todo
priority: P1
complexity: 3
readiness: 0%
agent:
---

# T12.2. Mouse, paste and focus encoding

The second half of T12.

- `scull-input`:
  - mouse events in X10, normal, button-event and any-event tracking;
  - the default, UTF-8, SGR, urxvt and SGR-pixel encodings;
  - bracketed paste with end-marker stripping;
  - focus reports.
- `scull-term` keeps the mouse modes (`?9`, `?1000`–`?1003`, `?1005`, `?1006`, `?1015`, `?1016`) and alternate scroll (`?1007`), answers DECRQM for them, and encodes mouse, paste and focus against them.

The creator's Rust scull (`crates/scull-input/src/mouse.rs`, `paste.rs`, `focus.rs`, `crates/scull-term/src/input.rs`) is ported, as T12.1 ports the key encoders.

Done when the table-driven tests match xterm's ctlseqs, `./gradlew check` passes and Linux passes in Colima.
