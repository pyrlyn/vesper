---
status: not started
priority: P2
complexity: 4
readiness: 0%
---

# T31. OSC 8 hyperlinks on both platforms

Status: not started

Programs mark text as a link with OSC 8; the core already keeps the link of each cell and reports `TT_EVENT_LINK`, but a host cannot ask which link a cell holds. Done when Ctrl+click (Cmd+click on macOS) on a linked cell opens its URI through the system, the link under the pointer is underlined while the modifier is held, only schemes a user expects to open are opened (http, https, mailto, file on this machine), and the core part has Kotlin/Native tests and each platform its own.

Execution plan, three slices of at most 500 lines each:

- **T31.1 core.** `Terminal::link_at(row, col)` answers the link id of a viewport cell, and `Terminal::hover_link(id)` marks one link as hovered: every viewport cell carrying it gets a new `FrameCell::LINK` bit through the existing highlight marks, so its rows repaint and a host underlines them without knowing the link's extent (a wrapped URL spans rows). A sweep that frees the hovered id drops the hover, so a reused id is never underlined. FFI in its own module, the link FFI module: `tt_term_link_at(term, row, col, *id)` (`TT_EMPTY` for a cell with no link), `tt_term_link_hover(term, id)` (0 clears), `TT_CELL_LINK`; ABI minor 7 → 8; header and C# bindings regenerated (the bindings generator); Swift `TerminalSession.linkAt/linkURI/hoverLink`, C# `Terminal.LinkAt/LinkUri/HoverLink` and `Cell.IsLink`. Kotlin/Native tests for the lookup, the mark, scrolled history and the sweep; a Swift and a C# test for the wrappers.
- **T31.2 macOS.** A pure `LinkPolicy.openable(_:)` admits http, https, mailto and a `file` URL whose host is empty, `localhost` or this machine's name; everything else is ignored. Cmd+click on a linked cell opens it with `NSWorkspace.open` (never a shell); while Cmd is held the link under the pointer is hovered and the renderer underlines `TT_CELL_LINK` cells. Swift tests for the policy and the gesture's state.
- **T31.3 Windows.** The same policy in `Scull.Core` (`LinkPolicy.Openable`), Ctrl+click opens through `Windows.System.Launcher.LaunchUriAsync`, Ctrl held hovers and the D3D renderer underlines `TT_CELL_LINK` cells. MSTest for the policy and a WARP test for the underline.

- **T31.4 one open rule, in the core (review fixes 1, 2, 4).** What a link may open is decided once, in the Kotlin/Native core: `tt_link_target(uri, machine, *kind, buf, cap, *len)` in `scull-ffi` link-target exports answers `TT_LINK_WEB` (http/https with a host and no userinfo), `TT_LINK_MAIL` (mailto with an address), `TT_LINK_DIRECTORY` or `TT_LINK_FILE` (a file URL with no host, `localhost` or this machine's name, percent-decoded to an existing local path) or `TT_LINK_REFUSED`, and copies out the URI or the path. Refused: other schemes, control characters and whitespace, anything over the core's URI cap, and any file path that could name another machine (a second leading slash or any backslash, which Windows reads as a share: `file:////h/s`, `file://localhost//h/s`, `file:///%5C%5Ch/s`). **Rule for files:** a directory opens in Finder or Explorer; any other file is only revealed and selected there, never opened or run (macOS `activateFileViewerSelecting`, Windows `LaunchFolderPathAsync` with `ItemsToSelect`). Swift `LinkPolicy` and C# `EventPolicy.OpenableLink` are replaced by calls to it; `EventPolicy.PathOfUrl` (OSC 7) gets the same share refusal. Kotlin/Native tests for every case above plus userinfo, scheme case, an over-long URI and both path styles; a host test each for the wrapper.
- **T31.5 gestures that do not leak or stick (review fixes 3, 5, 6).** macOS takes a Cmd+click on a link from press to release as Windows does, so a mouse-reporting program sees no lone release or motion. The underline is cleared on modifier release, pointer exit, focus loss or deactivation, and is recomputed after a scroll and after output under a still pointer; the host no longer trusts a cached id to skip the core call. On Windows the hover rule moves into `Scull.Core` (`LinkHover`) so MSTest covers it. Tests: link over a wide cell (lookup on the spacer, mark span, underline width), the clear on modifier release and pointer exit on both platforms, the macOS click.

Verify per slice: `./gradlew check`, `swift test` in `macos/`, `dotnet test windows/Scull.slnx`, CI on the branch.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native (platform UI stays Swift/C# where this task says so).
- [ ] Tests and the `./gradlew check` gate (plus the platform tests this task names) pass.
