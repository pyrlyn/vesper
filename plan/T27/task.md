---
status: in progress
priority: P2
complexity: 2
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T27. Powerline and Nerd Font symbols on macOS

Prompts such as oh-my-posh and powerlevel10k print Powerline separators and Nerd Font icons from the Private Use Area. SF Mono has none of them and the system fallback finds none, so they show as `?` boxes unless the user installed a Nerd Font. Done when they draw with no font installed, as in Ghostty and Warp: the macOS renderer (T15) falls back to a bundled Symbols Nerd Font Mono (MIT), recorded in `toolchain.md`.

Check: a snapshot of a Powerline prompt with no Nerd Font installed.

Acceptance:
- [ ] The work described above is implemented in Kotlin/Native.
- [ ] Tests and the `./gradlew check` gate (plus the host tests this task names) pass.

## Execution plan

1. Commit `SymbolsNerdFontMono-Regular.ttf` and its MIT `LICENSE` from nerd-fonts v3.5.1 under `vesper-macos/fonts/` (git blob hashes checked against the tag).
2. Embed the font in every macOS binary (the app and the test executable) with the linker's `-sectcreate __TEXT __nerd_symbols`, as Info.plist is embedded; no file has to sit next to the app.
3. `Symbols.kt`: read the section of the running image with `getsectiondata` and make a CoreText font descriptor from it, without copying; null when the section is missing, so the system fallback still works.
4. `TextStyle`: each face gets the bundled font as its cascade list, so CoreText reaches it before the system fallback. Both the Metal renderer (`RunShaper`) and the CoreText fallback draw through these faces. Powerline separators stay sprites. Check that emoji still come from the colour emoji font.
5. Tests: Nerd Font icons and Powerline extras shape from the bundled font, emoji do not; a Metal snapshot of a Powerline prompt (`macos-powerline.png`) with ink in the icon cells.
6. `toolchain.md` row for the font.

Verify: `./gradlew ktlintFormat check linkDebugTestLinuxX64` on macOS, the Linux tests in Colima.
