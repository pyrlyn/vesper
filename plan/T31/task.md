---
status: todo
priority: P2
complexity: 4
readiness: 0%
agent:
---

# T31. OSC 8 hyperlinks on both platforms

Programs mark text as a link with OSC 8; the core keeps the link of each cell and reports a link event. Done when Ctrl+click (Cmd+click on macOS) on a linked cell opens its target through the system, the link under the pointer is underlined while the modifier is held, and the gestures neither leak a lone release to a mouse-reporting program nor leave a stale underline.

What a link may open is decided once, in the core: web (http/https with a host and no userinfo), mail (mailto with an address), or a local file or directory (a `file` URL with no host, `localhost` or this machine's name, percent-decoded to an existing path); everything else is refused, including control characters, over-long URIs and any path that could name another machine (`file:////h/s`, backslashes). A directory opens in Finder or Explorer; any other file is only revealed there, never opened or run. The core also answers the link at a cell and marks one hovered link on every cell that carries it, so a wrapped URL underlines whole.

The removed host code in `macos/` and `windows/` at eaeb20f is the reference. The execution plan is written at claim time.

Check: `./gradlew check` with core tests for the lookup, the hover mark, scrolled history, the sweep and every open-rule case, and host tests for the gestures.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.
